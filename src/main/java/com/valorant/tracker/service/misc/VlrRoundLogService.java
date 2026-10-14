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

/** Fetches a single VLR game's Logs tab and returns parsed, inspectable log content. */
@Service
public class VlrRoundLogService {
    private static final Logger log = LoggerFactory.getLogger(VlrRoundLogService.class);
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(15);
    private static final Pattern MATCH_PATH = Pattern.compile("^/\\d+/[^?#]+/?$");
    private static final Pattern GAME_ID_PATTERN = Pattern.compile("\\d{1,12}");
    private static final int MAX_ENTRIES = 500;
    private final WebClient webClient;

    public VlrRoundLogService(WebClient.Builder builder) {
        this.webClient = builder
                .codecs(c -> c.defaultCodecs().maxInMemorySize(5 * 1024 * 1024))
                .defaultHeader("User-Agent", "Mozilla/5.0 (compatible; StremngVlrLogLookup/1.0)")
                .defaultHeader("Accept", "text/html,application/xhtml+xml")
                .build();
    }

    public Map<String, Object> fetchRoundLogs(String rawMatchUrl, String rawGameId) {
        return fetchRoundLogs(rawMatchUrl, rawGameId, null);
    }

    public Map<String, Object> fetchRoundLogs(String rawMatchUrl, String rawGameId, String rawRound) {
        String matchUrl = validateMatchUrl(rawMatchUrl);
        String gameId = validateGameId(rawGameId);
        String round = validateRound(rawRound);
        String logsUrl = matchUrl + "/?game=" + gameId + "&tab=logs" + (round == null ? "" : "&round=" + round);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceUrl", logsUrl);
        result.put("matchUrl", matchUrl);
        result.put("gameId", gameId);
        if (round != null) result.put("requestedRound", round);
        result.put("fetchedAt", Instant.now().toString());

        try {
            String html = webClient.get().uri(logsUrl).retrieve()
                    .onStatus(status -> status.isError(), response -> response.createException())
                    .bodyToMono(String.class).timeout(FETCH_TIMEOUT)
                    .onErrorMap(TimeoutException.class, e -> new IllegalStateException("VLR request timed out"))
                    .block(FETCH_TIMEOUT);
            if (html == null || html.isBlank()) throw new IllegalStateException("VLR returned an empty page");

            Document document = Jsoup.parse(html, logsUrl);
            result.put("pageTitle", document.title());
            result.put("httpContentLoaded", true);

            List<Map<String, String>> entries = extractLogEntries(document);
            List<Map<String, String>> roundSummaries = extractRoundSummaries(document, gameId);
            List<Map<String, Object>> roundDetails = extractRoundDetails(document, gameId);
            // A round query must filter the returned collections, not merely set VLR's
            // selected-round URL parameter. Keep the full page only when round is omitted.
            if (round != null) {
                roundSummaries.removeIf(item -> !round.equals(item.get("round")));
                roundDetails.removeIf(item -> !round.equals(item.get("round")));
            }
            int parsedRoundEventCount = roundDetails.stream()
                    .mapToInt(detail -> ((Number) detail.getOrDefault("eventCount", 0)).intValue())
                    .sum();
            result.put("status", !roundSummaries.isEmpty() || !roundDetails.isEmpty() || !entries.isEmpty()
                    ? "LOGS_FOUND" : "PAGE_LOADED_NO_LOG_ENTRIES");
            // `entries` is the legacy page-wide selector result. Round events are parsed
            // from the actual VLR round markup, so expose both counts distinctly.
            result.put("entryCount", parsedRoundEventCount);
            result.put("entries", entries);
            result.put("legacyEntryCount", entries.size());
            result.put("roundSummaryCount", roundSummaries.size());
            result.put("roundSummaries", roundSummaries);
            Map<String, Object> completion = detectMapCompletion(roundSummaries);
            result.put("mapCompleted", completion.get("completed"));
            if (completion.get("winner") != null) result.put("mapWinner", completion.get("winner"));
            if (completion.get("score") != null) result.put("mapFinalScore", completion.get("score"));
            result.put("roundDetailCount", roundDetails.size());
            result.put("roundDetails", roundDetails);
            result.put("diagnostics", buildDiagnostics(document, parsedRoundEventCount));
            if (roundSummaries.isEmpty() && roundDetails.isEmpty() && entries.isEmpty()) {
                result.put("message", "The page loaded, but no round summaries or details were found. Check gameId and the returned page markup.");
                result.put("visibleTextSample", visibleTextSample(document));
            }
            return result;
        } catch (Exception e) {
            log.warn("VLR round-log lookup failed for game {}: {}", gameId, e.toString());
            result.put("status", "FETCH_FAILED");
            result.put("entryCount", 0);
            result.put("entries", List.of());
            result.put("error", safeMessage(e));
            return result;
        }
    }


    /** Only a cumulative score with a 13+ score and a two-round lead can finish a map. */
    private static Map<String, Object> detectMapCompletion(List<Map<String, String>> summaries) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("completed", false);
        Map<String, String> latest = summaries.stream()
                .filter(x -> x.get("round") != null && x.get("round").matches("\\d+"))
                .max(java.util.Comparator.comparingInt(x -> Integer.parseInt(x.get("round"))))
                .orElse(null);
        if (latest == null) return result;
        Matcher m = Pattern.compile("(?i)^Round\\s+\\d+:\\s*(.*?)\\s+(\\d+)\\s*[–—-]\\s*(\\d+)\\s+(.*?)(?:,|$)").matcher(latest.getOrDefault("summary", ""));
        if (!m.find()) return result;
        String team1 = m.group(1).trim();
        int score1 = Integer.parseInt(m.group(2));
        int score2 = Integer.parseInt(m.group(3));
        String team2 = m.group(4).trim();
        boolean completed = Math.max(score1, score2) >= 13 && Math.abs(score1 - score2) >= 2;
        result.put("completed", completed);
        result.put("score", score1 + "-" + score2);
        if (completed) result.put("winner", score1 > score2 ? team1 : team2);
        return result;
    }

    private static List<Map<String, String>> extractRoundSummaries(Document document, String gameId) {
        Element map = document.selectFirst(".vm-stats-game[data-game-id=" + cssQuote(gameId) + "]");
        if (map == null) map = document.selectFirst(".vm-stats.mod-tab-logs[data-game-id=" + cssQuote(gameId) + "]");
        if (map == null) map = document;
        List<Map<String, String>> summaries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Element round : map.select(".vlr-rounds-row-col.js-lg-round[data-round]")) {
            String number = round.attr("data-round").trim();
            String title = normalize(round.attr("title"));
            if (number.isBlank()) continue;
            String key = number + "|" + title;
            if (!seen.add(key)) continue;
            Map<String, String> item = new LinkedHashMap<>();
            item.put("round", number);
            if (!title.isBlank()) item.put("summary", title);
            String classes = round.className();
            if (!classes.isBlank()) item.put("cssClass", classes);
            summaries.add(item);
        }
        return summaries;
    }

    private static List<Map<String, Object>> extractRoundDetails(Document document, String gameId) {
        Element map = document.selectFirst(".vm-stats-game[data-game-id=" + cssQuote(gameId) + "]");
        if (map == null) map = document;
        Elements rounds = map.select(".lg-rounds .lg-round[data-round]");
        if (rounds.isEmpty()) rounds = document.select(".lg-rounds .lg-round[data-round]");
        List<Map<String, Object>> result = new ArrayList<>();
        for (Element round : rounds) {
            Map<String, Object> item = new LinkedHashMap<>();
            String number = round.attr("data-round").trim();
            item.put("round", number);
            item.put("active", round.hasClass("mod-active"));
            Element summary = document.selectFirst(".vlr-rounds-row-col.js-lg-round[data-round=" + cssQuote(number) + "]");
            if (summary != null && !summary.attr("title").isBlank()) item.put("summary", normalize(summary.attr("title")));

            List<Map<String, String>> events = new ArrayList<>();
            Set<String> seenEvents = new LinkedHashSet<>();
            // Parse individual kill and spike-objective rows together. Selecting the
            // shared .lg-row elements preserves VLR's chronological DOM order and avoids
            // treating the aggregate .lg-main timeline as one giant event.
            for (Element eventRow : round.select(".lg-body .lg-row.mod-kill, .lg-body .lg-row.mod-spike")) {
                String rowText = normalize(eventRow.text());
                Element clock = eventRow.selectFirst(".lg-clock");
                String timestamp = clock == null ? "" : normalize(clock.text());
                if (timestamp.isBlank()) {
                    Matcher timestampMatcher = Pattern.compile("\\b(\\d{1,2}:\\d{2})\\b").matcher(rowText);
                    timestamp = timestampMatcher.find() ? timestampMatcher.group(1) : "";
                }
                if (timestamp.isBlank() || rowText.isBlank()) continue;
                String key = timestamp + "|" + rowText;
                if (!seenEvents.add(key)) continue;
                Map<String, String> event = new LinkedHashMap<>();
                event.put("timestamp", timestamp);
                event.put("text", rowText);
                String type = "KILL";
                if (eventRow.hasClass("mod-spike")) {
                    String objectiveText = normalize(Objects.toString(
                            eventRow.selectFirst(".lg-spike-what") == null ? "" :
                                    eventRow.selectFirst(".lg-spike-what").text(), rowText));
                    String lowerObjective = objectiveText.toLowerCase(java.util.Locale.ROOT);
                    if (lowerObjective.contains("planted")) type = "SPIKE_PLANTED";
                    else if (lowerObjective.contains("defused") || lowerObjective.contains("defuse")) type = "SPIKE_DEFUSED";
                    else if (lowerObjective.contains("exploded")) type = "SPIKE_EXPLODED";
                    else type = "SPIKE_OBJECTIVE";
                }
                event.put("type", type);
                if (!eventRow.className().isBlank()) event.put("cssClass", eventRow.className());
                events.add(event);
                if (events.size() >= MAX_ENTRIES) break;
            }
            item.put("eventCount", events.size());
            item.put("events", events);

            // The aggregate timeline carries objective events such as Spike planted/defused.
            // Preserve it separately so those events are available without polluting kill rows.
            List<String> objectiveTimelines = new ArrayList<>();
            for (Element timeline : round.select(".lg-body .lg-main.mod-side")) {
                String timelineText = normalize(timeline.text());
                if (!timelineText.isBlank() && !objectiveTimelines.contains(timelineText)) {
                    objectiveTimelines.add(timelineText);
                }
            }
            item.put("objectiveTimelineCount", objectiveTimelines.size());
            item.put("objectiveTimelines", objectiveTimelines);

            List<Map<String, String>> players = new ArrayList<>();
            for (Element player : round.select(".lg-mir .lg-mir-row")) {
                Map<String, String> p = new LinkedHashMap<>();
                Element name = player.selectFirst(".lg-mir-name");
                Element agent = player.selectFirst("img.lg-agent");
                Element credits = player.selectFirst(".lg-mir-vals");
                if (name != null && !normalize(name.text()).isBlank()) p.put("player", normalize(name.text()));
                if (agent != null) {
                    String agentName = firstNonBlank(agent.attr("title"), agent.attr("alt"));
                    if (!agentName.isBlank()) p.put("agent", agentName);
                }
                if (credits != null) p.put("economyText", normalize(credits.text()));
                String side = player.parent() == null ? "" : player.parent().className();
                if (!side.isBlank()) p.put("sideClass", side);
                if (!p.isEmpty()) players.add(p);
            }
            item.put("playerCount", players.size());
            item.put("players", players);
            result.add(item);
        }
        return result;
    }

    private static String cssQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static List<Map<String, String>> extractLogEntries(Document document) {
        // Retain the broad legacy extraction as a compatibility fallback.
        String[] selectors = {".vm-stats-game-log .vm-stats-game-log-item", ".vm-stats-game-log-item",
                ".vm-stats-game-log > *", ".round-log .round-log-item", "[class*=log-item]"};
        List<Map<String, String>> entries = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String selector : selectors) {
            for (Element element : document.select(selector)) {
                String text = normalize(element.text());
                if (text.isBlank() || text.length() < 3 || !seen.add(text.toLowerCase())) continue;
                Map<String, String> entry = new LinkedHashMap<>();
                entry.put("text", text.length() > 1000 ? text.substring(0, 1000) : text);
                String time = firstNonBlank(element.attr("data-time"), element.attr("data-timestamp"), element.attr("title"));
                if (!time.isBlank()) entry.put("time", time);
                String round = firstNonBlank(element.attr("data-round"), element.attr("data-round-number"));
                if (!round.isBlank()) entry.put("round", round);
                if (!element.className().isBlank()) entry.put("cssClass", element.className());
                entries.add(entry);
                if (entries.size() >= MAX_ENTRIES) return entries;
            }
        }
        return entries;
    }

    private static Map<String, Object> buildDiagnostics(Document document, int entryCount) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("bodyTextLength", document.body() == null ? 0 : document.body().text().length());
        diagnostics.put("parsedRoundEventCount", entryCount);
        diagnostics.put("legacyEntrySelectorsMatched", document.select(".vm-stats-game-log .vm-stats-game-log-item, .vm-stats-game-log-item, .vm-stats-game-log > *, .round-log .round-log-item, [class*=log-item]").size());
        diagnostics.put("roundSummaryElements", document.select(".vlr-rounds-row-col.js-lg-round[data-round]").size());
        diagnostics.put("roundDetailElements", document.select(".lg-rounds .lg-round[data-round]").size());
        diagnostics.put("mirPlayerRows", document.select(".lg-mir .lg-mir-row").size());
        diagnostics.put("timestampCandidates", document.select(".lg-rounds *").stream().filter(e -> e.ownText().trim().matches("\\d{1,2}:\\d{2}")).count());
        diagnostics.put("logRelatedElements", document.select("[class*=log], [id*=log], [class*=round], [id*=round]").size());
        diagnostics.put("tables", document.select("table").size());
        diagnostics.put("linksWithGameParameter", document.select("a[href*=game=]").size());
        return diagnostics;
    }

    private static String visibleTextSample(Document document) {
        if (document.body() == null) return "";
        String text = normalize(document.body().text());
        return text.length() > 1200 ? text.substring(0, 1200) : text;
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

    private static String validateGameId(String raw) {
        if (raw == null || !GAME_ID_PATTERN.matcher(raw.trim()).matches())
            throw new IllegalArgumentException("gameId must contain 1 to 12 digits");
        return raw.trim();
    }

    private static String validateRound(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (!raw.trim().matches("\\d{1,3}"))
            throw new IllegalArgumentException("round must be a positive integer");
        int value = Integer.parseInt(raw.trim());
        if (value < 1 || value > 100)
            throw new IllegalArgumentException("round must be between 1 and 100");
        return Integer.toString(value);
    }

    private static String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private static String safeMessage(Exception e) {
        if (e instanceof WebClientResponseException responseException)
            return "VLR returned HTTP " + responseException.getStatusCode().value();
        String message = Objects.toString(e.getMessage(), "Request failed");
        return message.length() > 180 ? message.substring(0, 180) : message;
    }
}
