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

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Manual endpoint service to discover VLR game IDs and per-game URLs from a match page. */
@Service
public class VlrGameUrlService {
    private static final Logger log = LoggerFactory.getLogger(VlrGameUrlService.class);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern MATCH_PATH = Pattern.compile("^/\\d+/[^?#]+/?$");
    private static final Pattern GAME_ID = Pattern.compile("(?:[?&]|^)game=(\\d+)(?:&|$)");
    private final WebClient webClient;

    public VlrGameUrlService(WebClient.Builder builder) {
        this.webClient = builder
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; StremngMatchLookup/1.0)")
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .build();
    }

    public Map<String, Object> findGameUrls(String rawMatchUrl) {
        String matchUrl = validateMatchUrl(rawMatchUrl);
        Instant fetchedAt = Instant.now();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceUrl", matchUrl);
        result.put("fetchedAt", fetchedAt.toString());
        try {
            String html = webClient.get().uri(matchUrl).retrieve()
                    .onStatus(status -> status.isError(), response -> response.createException())
                    .bodyToMono(String.class).timeout(FETCH_TIMEOUT)
                    .onErrorMap(TimeoutException.class, e -> new IllegalStateException("VLR request timed out"))
                    .block(FETCH_TIMEOUT);
            if (html == null || html.isBlank()) throw new IllegalStateException("VLR returned an empty page");

            Document document = Jsoup.parse(html, matchUrl);
            Set<String> ids = new LinkedHashSet<>();
            Elements links = document.select("a[href]");
            for (Element link : links) {
                String href = link.attr("href");
                Matcher matcher = GAME_ID.matcher(href);
                if (matcher.find()) ids.add(matcher.group(1));
            }

            // VLR sometimes renders game selectors as buttons/data attributes rather than links.
            for (Element element : document.select("[data-game], [data-game-id], [data-gameid]")) {
                for (String attr : List.of("data-game", "data-game-id", "data-gameid")) {
                    String value = element.attr(attr).trim();
                    if (value.matches("\\d+")) ids.add(value);
                }
            }

            List<Map<String, String>> games = new ArrayList<>();
            String baseUrl = matchUrl.replaceAll("[?#].*$", "").replaceAll("/$", "");
            for (String id : ids) {
                String gameUrl = baseUrl + "/?game=" + id;
                Map<String, String> game = new LinkedHashMap<>();
                game.put("gameId", id);
                game.put("gameUrl", gameUrl);
                game.put("logsUrl", gameUrl + "&tab=logs");
                games.add(game);
            }

            result.put("status", games.isEmpty() ? "NO_GAMES_FOUND" : "GAMES_FOUND");
            result.put("gameCount", games.size());
            result.put("games", games);
            if (games.isEmpty()) result.put("message", "Match page loaded, but no game IDs were found in links or data attributes.");
            return result;
        } catch (Exception e) {
            log.warn("VLR game URL lookup failed for {}: {}", matchUrl, e.toString());
            result.put("status", "FETCH_FAILED");
            result.put("gameCount", 0);
            result.put("games", List.of());
            result.put("error", safeMessage(e));
            return result;
        }
    }

    private static String validateMatchUrl(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("matchUrl is required");
        if (raw.length() > 500) throw new IllegalArgumentException("matchUrl must be 500 characters or fewer");
        URI uri;
        try { uri = URI.create(raw.trim()); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("matchUrl is not a valid URL"); }
        String host = uri.getHost();
        if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null
                || !(host.equalsIgnoreCase("vlr.gg") || host.equalsIgnoreCase("www.vlr.gg"))
                || uri.getUserInfo() != null || uri.getPort() != -1
                || !MATCH_PATH.matcher(uri.getPath() == null ? "" : uri.getPath()).matches()) {
            throw new IllegalArgumentException("matchUrl must be an HTTPS VLR match URL, e.g. https://www.vlr.gg/754736/team-vs-team");
        }
        return "https://www.vlr.gg" + uri.getPath().replaceAll("/$", "");
    }

    private static String safeMessage(Exception e) {
        if (e instanceof WebClientResponseException responseException)
            return "VLR returned HTTP " + responseException.getStatusCode().value();
        String message = Objects.toString(e.getMessage(), "Request failed");
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
