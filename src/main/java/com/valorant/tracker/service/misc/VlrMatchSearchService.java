package com.valorant.tracker.service.misc;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Manual, low-frequency VLR match-page lookup used to validate matching by team names.
 * This is intentionally not a background poller. Confirm VLR's permission/terms
 * before enabling recurring automated collection.
 */
@Service
public class VlrMatchSearchService {
    private static final Logger log = LoggerFactory.getLogger(VlrMatchSearchService.class);
    private static final String MATCHES_URL = "https://www.vlr.gg/matches";
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration CACHE_TTL = Duration.ofSeconds(10);
    private static final Pattern MATCH_PATH = Pattern.compile("^/\\d+/[^?#]+/?$");

    private final WebClient webClient;
    private volatile Instant cacheExpiresAt = Instant.EPOCH;
    private volatile List<VlrMatch> cachedMatches = List.of();

    public VlrMatchSearchService(WebClient.Builder builder) {
        this.webClient = builder
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; StremngMatchLookup/1.0)")
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .build();
    }

    public Map<String, Object> findMatch(String team1, String team2) {
        String requestedTeam1 = requireTeam(team1, "team1");
        String requestedTeam2 = requireTeam(team2, "team2");
        if (normalize(requestedTeam1).equals(normalize(requestedTeam2))) {
            throw new IllegalArgumentException("team1 and team2 must be different teams");
        }

        Instant fetchedAt = Instant.now();
        List<VlrMatch> pageMatches;
        try {
            pageMatches = getMatches();
        } catch (Exception e) {
            log.warn("VLR match lookup failed: {}", e.toString());
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("status", "FETCH_FAILED");
            error.put("sourceUrl", MATCHES_URL);
            error.put("team1", requestedTeam1);
            error.put("team2", requestedTeam2);
            error.put("error", safeMessage(e));
            error.put("fetchedAt", fetchedAt.toString());
            return error;
        }

        List<VlrMatch> exactMatches = new ArrayList<>();
        List<VlrMatch> partialCandidates = new ArrayList<>();
        for (VlrMatch match : pageMatches) {
            boolean firstFound = teamMatches(requestedTeam1, match.team1())
                    || teamMatches(requestedTeam1, match.team2());
            boolean secondFound = teamMatches(requestedTeam2, match.team1())
                    || teamMatches(requestedTeam2, match.team2());
            if (firstFound && secondFound) {
                exactMatches.add(match);
            } else if (firstFound || secondFound) {
                partialCandidates.add(match);
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", exactMatches.isEmpty() ? "NOT_FOUND" : "MATCH_FOUND");
        result.put("sourceUrl", MATCHES_URL);
        result.put("team1", requestedTeam1);
        result.put("team2", requestedTeam2);
        result.put("pageMatchCount", pageMatches.size());
        result.put("matchCount", exactMatches.size());
        result.put("matches", exactMatches);
        result.put("partialCandidates", partialCandidates.stream().limit(10).toList());
        result.put("fetchedAt", fetchedAt.toString());
        return result;
    }

    private synchronized List<VlrMatch> getMatches() {
        Instant now = Instant.now();
        if (now.isBefore(cacheExpiresAt)) {
            return cachedMatches;
        }

        String html = webClient.get()
                .uri(MATCHES_URL)
                .retrieve()
                .onStatus(status -> status.isError(), response -> response.createException())
                .bodyToMono(String.class)
                .timeout(FETCH_TIMEOUT)
                .onErrorMap(TimeoutException.class, e -> new IllegalStateException("VLR request timed out"))
                .block(FETCH_TIMEOUT);

        if (html == null || html.isBlank()) {
            throw new IllegalStateException("VLR returned an empty page");
        }

        Document document = Jsoup.parse(html, MATCHES_URL);
        Elements cards = document.select(".wf-module-item.match-item");
        List<VlrMatch> parsed = new ArrayList<>();
        for (Element card : cards) {
            VlrMatch match = parseCard(card);
            if (match != null) parsed.add(match);
        }

        // An empty parse is treated as a possible markup change, not a valid empty schedule.
        if (cards.isEmpty() || parsed.isEmpty()) {
            throw new IllegalStateException("VLR page loaded but no match cards could be parsed; markup may have changed");
        }

        cachedMatches = List.copyOf(parsed);
        cacheExpiresAt = Instant.now().plus(CACHE_TTL);
        log.info("Parsed {} match cards from VLR /matches", parsed.size());
        return cachedMatches;
    }

    private VlrMatch parseCard(Element card) {
        String team1 = text(card.selectFirst(".match-item-vs-team-name"));
        String team2 = "";
        Elements teamNames = card.select(".match-item-vs-team-name");
        if (teamNames.size() >= 2) {
            team1 = clean(teamNames.get(0).text());
            team2 = clean(teamNames.get(1).text());
        } else {
            // Fallback for small markup changes: use team containers in the VS block.
            Elements teams = card.select(".match-item-vs-team");
            if (teams.size() >= 2) {
                team1 = clean(teams.get(0).text());
                team2 = clean(teams.get(1).text());
            }
        }
        if (team1.isBlank() || team2.isBlank()) return null;

        String href = null;
        for (Element link : card.select("a[href]")) {
            String candidate = link.attr("href").trim();
            if (MATCH_PATH.matcher(candidate).matches()) {
                href = candidate;
                break;
            }
        }
        if (href == null) return null;

        String absoluteUrl = href.startsWith("http") ? href : "https://www.vlr.gg" + href;
        String event = text(card.selectFirst(".match-item-event"));
        String status = text(card.selectFirst(".match-item-time"));
        String score = text(card.selectFirst(".match-item-score"));
        return new VlrMatch(team1, team2, absoluteUrl, event, status, score);
    }

    private static boolean teamMatches(String requested, String actual) {
        String a = normalize(requested);
        String b = normalize(actual);
        if (a.isBlank() || b.isBlank()) return false;
        if (a.equals(b)) return true;
        // Basic alias-friendly match: "G2 Esports" vs "G2". Keep it conservative.
        return (a.length() >= 3 && b.length() >= 3 && (a.startsWith(b) || b.startsWith(a)));
    }

    private static String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^\\p{L}\\p{N}]", "")
                .trim();
    }

    private static String requireTeam(String value, String field) {
        String cleaned = clean(value);
        if (cleaned.isBlank()) throw new IllegalArgumentException(field + " is required");
        if (cleaned.length() > 100) throw new IllegalArgumentException(field + " must be 100 characters or fewer");
        return cleaned;
    }

    private static String text(Element element) {
        return element == null ? "" : clean(element.text());
    }

    private static String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String safeMessage(Exception e) {
        if (e instanceof WebClientResponseException responseException) {
            return "VLR returned HTTP " + responseException.getStatusCode().value();
        }
        String message = Objects.toString(e.getMessage(), "Request failed");
        return message.length() > 180 ? message.substring(0, 180) : message;
    }

    public record VlrMatch(String team1, String team2, String matchUrl,
                           String event, String displayedTime, String score) {}
}
