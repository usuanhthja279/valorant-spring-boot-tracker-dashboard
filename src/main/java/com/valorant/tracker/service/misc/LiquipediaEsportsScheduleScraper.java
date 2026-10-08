package com.valorant.tracker.service.misc;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LiquipediaEsportsScheduleScraper {

    private static final Logger log =
            LoggerFactory.getLogger(LiquipediaEsportsScheduleScraper.class);

    private static final String BASE = "https://liquipedia.net/";

    private static final Duration DEFAULT_CACHE =
            Duration.ofMinutes(5);

    private final WebClient webClient;

    private static final Map<String, String> WIKIS = Map.ofEntries(
            Map.entry("VALORANT", "valorant"),
            Map.entry("COUNTER-STRIKE", "counterstrike"),
            Map.entry("COUNTER-STRIKE 2", "counterstrike"),
            Map.entry("LEAGUE OF LEGENDS", "leagueoflegends"),
            Map.entry("DOTA 2", "dota2"),
            Map.entry("FORTNITE", "fortnite"),
            Map.entry("RAINBOW SIX SIEGE", "rainbowsix"),
            Map.entry("ROCKET LEAGUE", "rocketleague"),
            Map.entry("APEX LEGENDS", "apexlegends"),
            Map.entry("OVERWATCH 2", "overwatch"),
            Map.entry("EA SPORTS FC", "easportsfc"),
            Map.entry("PUBG", "pubg"),
            Map.entry("CALL OF DUTY", "callofduty"),
            Map.entry("MOBILE LEGENDS", "mobilelegends"),
            Map.entry("FREE FIRE", "freefire")
    );

    private final Duration cacheDuration;
    private final String liquipediaCookie;

    private final Map<String, CacheEntry> cache =
            new ConcurrentHashMap<>();

    private final Map<String, LogoCacheEntry> logoCache =
            new ConcurrentHashMap<>();

    public LiquipediaEsportsScheduleScraper(
            WebClient.Builder webClientBuilder,
            @Value("${tracker.liquipedia.cache-minutes:5}")
            long cacheMinutes,
            @Value("${tracker.liquipedia.cookie:}")
            String liquipediaCookie) {

        this.cacheDuration =
                cacheMinutes > 0
                        ? Duration.ofMinutes(cacheMinutes)
                        : DEFAULT_CACHE;

        this.liquipediaCookie = liquipediaCookie;

        this.webClient = webClientBuilder
                .codecs(configurer ->
                        configurer.defaultCodecs()
                                .maxInMemorySize(10 * 1024 * 1024))
                .defaultHeader(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                "AppleWebKit/537.36 " +
                                "Chrome/154.0.0.0 Safari/537.36")
                .defaultHeader(
                        "Accept",
                        "text/html,application/xhtml+xml")
                .build();
    }


    /**
     * Return the actual team-logo image URLs used by Liquipedia's Main_Page
     * match rows. Keys are the exact displayed team names (for example
     * "100T", "G2", "Spirit", "M80").
     *
     * This deliberately uses the image URL from the match row instead of
     * guessing a filename from the team name. That matters because Liquipedia
     * can use aliases, sponsored names, or different logo filenames.
     */
    public Map<String, String> getTeamLogoUrls(String game) {
        String normalized = normalizeGame(game);
        LogoCacheEntry cached = logoCache.get(normalized);
        if (cached != null && Instant.now().isBefore(cached.expiresAt())) {
            return cached.logos();
        }

        String wiki = WIKIS.get(normalized);
        if (wiki == null) {
            return Collections.emptyMap();
        }

        try {
            String url = BASE + wiki + "/Main_Page";
            String html = fetch(url);
            Document document = Jsoup.parse(html, url);

            Map<String, String> logos = new LinkedHashMap<>();

            for (Element row : document.select("div.match-info")) {
                Elements opponents = row.select(".match-info-header-opponent");

                for (Element opponent : opponents) {
                    Element name = opponent.selectFirst(".name");
                    Element image = opponent.selectFirst(
                            ".team-template-image-icon img[src], img[src]");

                    if (name == null || image == null) {
                        continue;
                    }

                    String team = name.text().trim();
                    String logo = image.absUrl("src");

                    if (!team.isBlank() && !logo.isBlank()) {
                        logos.putIfAbsent(team, logo);
                    }
                }
            }

            Map<String, String> immutable = Map.copyOf(logos);
            logoCache.put(normalized,
                    new LogoCacheEntry(
                            Instant.now().plus(cacheDuration),
                            immutable));

            return immutable;
        } catch (Exception e) {
            log.debug(
                    "Could not load Liquipedia team logos for game={}: {}",
                    game,
                    e.getMessage());
            return Collections.emptyMap();
        }
    }

    public List<EsportsMatch> getUpcomingMatches(String game) {

        OffsetDateTime now =
                OffsetDateTime.now(ZoneOffset.UTC);

        OffsetDateTime activeFrom =
                now.minusHours(4);

        OffsetDateTime upcomingUntil =
                now.plusDays(7);

        return getUpcomingAndLive(game).stream()
                .filter(match -> match.startTime() != null)
                .filter(match ->
                        !match.startTime().isBefore(activeFrom)
                                && !match.startTime()
                                .isAfter(upcomingUntil))
                .sorted(
                        Comparator.comparing(
                                EsportsMatch::startTime))
                .toList();
    }

    public List<EsportsMatch> getUpcomingAndLive(String game) {
        String normalized = normalizeGame(game);
        CacheEntry current = cache.get(normalized);
        if (current != null && Instant.now().isBefore(current.expiresAt())) {
            return current.matches();
        }

        List<EsportsMatch> scraped = scrape(normalized);
        cache.put(normalized, new CacheEntry(Instant.now().plus(cacheDuration), scraped));
        return scraped;
    }

    /**
     * Force a fresh Main_Page scrape for manual/API diagnostics.
     * This bypasses the normal scraper cache and replaces the cached result.
     */
    public List<EsportsMatch> refresh(String game) {
        return getUpcomingAndLive(game);
    }

    public boolean hasActiveMatch(String game) {

        Instant now = Instant.now();

        return getUpcomingAndLive(game).stream()
                .filter(match -> match.startTime() != null)
                .filter(match -> match.isActive(now))
                .findFirst()
                .map(match -> {

                    log.info(
                            "Active esports match: game={} " +
                                    "tournament={} {} vs {} " +
                                    "startTime={} tier={}",
                            game,
                            match.tournament(),
                            match.team1(),
                            match.team2(),
                            match.startTime(),
                            match.tier());

                    return true;
                })
                .orElse(false);
    }

    /**
     * Main-page-first scraping.
     *
     * No separate S/A-tier category pages are required.
     */
    private List<EsportsMatch> scrape(String game) {

        String wiki = WIKIS.get(normalizeGame(game));

        if (wiki == null) {
            log.warn("No Liquipedia wiki configured for game={}", game);
            return Collections.emptyList();
        }

        try {
            /*
             * Fetch Main_Page ONCE.
             */
            String url = BASE + wiki + "/Main_Page";
            String html = fetch(url);
            Document document = Jsoup.parse(html, url);

            /*
             * Extract tournament -> tier information
             * from the same Main_Page.
             */
            Map<String, String> tournamentTiers = extractTournamentTiers(document, wiki);
            List<EsportsMatch> result = new ArrayList<>();

            /*
             * Parse match rows directly from Main_Page.
             */
            for (Element row : document.select("div.match-info")) {
                EsportsMatch match = parseMatch(game, row, tournamentTiers);
                if (match == null) {
                    continue;
                }

                if (isEligibleTier(game, match.tier())) {
                    result.add(match);
                }
            }

            result.sort(Comparator.comparing(EsportsMatch::startTime));
            log.info("Liquipedia {} schedule: {} eligible matches", game, result.size());
            return List.copyOf(result);
        } catch (Exception e) {
            log.warn("Liquipedia scrape failed for game={}: {}", game, e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Extract tournament tier information from Main_Page.
     *
     * Examples:
     *
     * Dota 2:
     *   Tier 1
     *   Tier 2
     *   Tier 3
     *   Tier 4
     *
     * Other games:
     *   S-Tier
     *   A-Tier
     */
    private Map<String, String> extractTournamentTiers(
            Document document,
            String wiki) {

        Map<String, String> result =
                new HashMap<>();

        for (Element anchor :
                document.select("a[href]")) {

            String href =
                    stripFragment(
                            anchor.absUrl("href"));

            if (href.isBlank()) {
                continue;
            }

            if (!href.contains("/" + wiki + "/")) {
                continue;
            }

            String tier =
                    findTierNearElement(anchor);

            if (tier == null) {
                continue;
            }

            /*
             * Ignore obvious non-tournament links.
             */
            if (href.contains("/Match:")
                    || href.contains("Category:")
                    || href.endsWith("/Main_Page")) {
                continue;
            }

            result.put(href, tier);
        }

        return result;
    }

    /**
     * Looks around a tournament link for the tier badge.
     *
     * This intentionally walks up a few DOM levels because
     * Liquipedia's tournament-list HTML can vary between wikis.
     */
    private String findTierNearElement(Element anchor) {

        Element current = anchor;

        for (int level = 0; level < 6 && current != null; level++) {

            String text =
                    current.text()
                            .replace('\u00A0', ' ')
                            .trim();

            String tier =
                    detectTier(text);

            if (tier != null) {
                return tier;
            }

            current = current.parent();
        }

        return null;
    }

    /**
     * Converts visible Liquipedia tier text into
     * our internal tier representation.
     */
    private String detectTier(String text) {

        if (text == null || text.isBlank()) {
            return null;
        }

        String normalized =
                text.toUpperCase(Locale.ROOT)
                        .replace('\u00A0', ' ');

        /*
         * Dota 2
         */
        if (normalized.matches(".*\\bTIER\\s*1\\b.*")) {
            return "T1";
        }

        if (normalized.matches(".*\\bTIER\\s*2\\b.*")) {
            return "T2";
        }

        if (normalized.matches(".*\\bTIER\\s*3\\b.*")) {
            return "T3";
        }

        if (normalized.matches(".*\\bTIER\\s*4\\b.*")) {
            return "T4";
        }

        /*
         * S/A system
         */
        if (normalized.matches(".*\\bS[- ]?TIER\\b.*")) {
            return "S";
        }

        if (normalized.matches(".*\\bA[- ]?TIER\\b.*")) {
            return "A";
        }

        return null;
    }

    /**
     * Determines which tier system is valid for the game.
     */
    private boolean isEligibleTier(String game, String tier) {

        if (tier == null) {
            return false;
        }

        String normalized = normalizeGame(game);

        /*
         * Dota 2:
         * Tier 1 + Tier 2
         */
        if ("DOTA 2".equals(normalized)) {
            return "T1".equals(tier) || "T2".equals(tier);
        }

        /*
         * Other esports:
         * S + A
         */
        return "S".equals(tier) || "A".equals(tier);
    }

    private EsportsMatch parseMatch(
            String game,
            Element row,
            Map<String, String> tournamentTiers) {

        try {
            /*
             * Do not require .match-info-countdown to exist.
             * Upcoming rows normally have a timer; completed rows can expose
             * their final score/winner without the same countdown structure.
             */
            Element timer = row.selectFirst(
                    ".match-info-countdown .timer-object[data-timestamp]");

            if (timer == null) {
                timer = row.selectFirst(".timer-object[data-timestamp]");
            }

            if (timer == null) {
                timer = row.selectFirst("[data-timestamp]");
            }

            if (timer == null) {
                return null;
            }

            String timestampValue = timer.attr("data-timestamp").trim();
            if (timestampValue.isBlank()) {
                return null;
            }

            long epoch = Long.parseLong(timestampValue);

            OffsetDateTime start = OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(epoch),
                    ZoneOffset.UTC);

            Element tournamentAnchor = row.selectFirst(
                    ".match-info-tournament-name a[href]");

            if (tournamentAnchor == null) {
                return null;
            }

            String tournamentUrl = stripFragment(
                    tournamentAnchor.absUrl("href"));

            String tournament = tournamentAnchor.text().trim();

            if (tournament.isBlank()) {
                tournament = tournamentAnchor.attr("title").trim();
            }

            String tier = tournamentTiers.get(tournamentUrl);

            if (tier == null) {
                return null;
            }

            Elements teams = row.select(
                    ".match-info-header-opponent .name");

            String team1 = teams.size() > 0
                    ? teams.get(0).text().trim()
                    : "TBD";

            String team2 = teams.size() > 1
                    ? teams.get(1).text().trim()
                    : "TBD";

            String matchUrl = "";

            Element matchLink = row.selectFirst(
                    ".match-page-button a[href], " +
                            "a[href*='/Match:']");

            if (matchLink != null) {
                matchUrl = stripFragment(
                        matchLink.absUrl("href"));
            }

            Integer team1Score = null;
            Integer team2Score = null;

            Elements scoreNodes = row.select(
                    ".match-info-header-scoreholder-score");

            if (scoreNodes.size() >= 2) {
                team1Score = parseScore(scoreNodes.get(0).text());
                team2Score = parseScore(scoreNodes.get(1).text());
            }

            String bestOf = "";
            Element formatElement = row.selectFirst(
                    ".match-info-header-scoreholder-lower");

            if (formatElement != null) {
                bestOf = formatElement.text().trim();

                if (bestOf.startsWith("(")
                        && bestOf.endsWith(")")) {
                    bestOf = bestOf.substring(
                            1,
                            bestOf.length() - 1).trim();
                }

                bestOf = normalizeBestOf(bestOf);
            }

            /*
             * Liquipedia marks completed rows with data-finished="finished".
             * Check both the timestamp element and the complete match row.
             */
            boolean finished =
                    "finished".equalsIgnoreCase(
                            timer.attr("data-finished"))
                            || "finished".equalsIgnoreCase(
                            row.attr("data-finished"))
                            || !row.select(
                            "[data-finished='finished']").isEmpty();

            String winner = null;

            // Liquipedia marks the winning numeric score with the
            // match-info-header-winner class. Map that marker back to team1/team2.
            if (scoreNodes.size() >= 2) {
                boolean team1Winner =
                        scoreNodes.get(0).hasClass("match-info-header-winner");

                boolean team2Winner =
                        scoreNodes.get(1).hasClass("match-info-header-winner");

                if (team1Winner) {
                    winner = team1;
                } else if (team2Winner) {
                    winner = team2;
                }
            }

            /*
             * Liquipedia also marks the winning team container itself.
             * Use that as a fallback if the score winner marker is absent.
             */
            if (winner == null) {
                Elements opponents = row.select(
                        ".match-info-header-opponent");

                if (opponents.size() >= 2) {
                    if (opponents.get(0)
                            .hasClass("match-info-header-winner")) {
                        winner = team1;
                    } else if (opponents.get(1)
                            .hasClass("match-info-header-winner")) {
                        winner = team2;
                    }
                }
            }

            /*
             * A winner marker plus two numeric scores is sufficient to classify
             * the row as completed even if data-finished is absent.
             */
            if (!finished
                    && winner != null
                    && team1Score != null
                    && team2Score != null) {
                finished = true;
            }

            OffsetDateTime now =
                    OffsetDateTime.now(ZoneOffset.UTC);

            String status;

            if (finished) {
                status = "COMPLETED";
            } else if (start.isAfter(now)) {
                status = "UPCOMING";
            } else {
                /*
                 * Do not assume every past-start match is live. Liquipedia can
                 * occasionally omit data-finished from the main-page row while
                 * the match page already knows the real phase.
                 */
                status = resolvePastMatchStatus(matchUrl);
            }

            String matchId = buildMatchId(
                    game,
                    tournament,
                    team1,
                    team2,
                    start,
                    matchUrl);

            OffsetDateTime end = null;

            return new EsportsMatch(
                    matchId,
                    game,
                    tournament,
                    tier,
                    team1,
                    team2,
                    team1Score,
                    team2Score,
                    bestOf,
                    winner,
                    finished,
                    start,
                    end,
                    status,
                    tournamentUrl,
                    matchUrl);

        } catch (Exception e) {

            log.debug(
                    "Failed to parse Liquipedia match row for game={}: {}",
                    game,
                    e.getMessage());

            return null;
        }
    }

    /**
     * Resolve an ambiguous past-start match from its Liquipedia Match page.
     *
     * Main_Page normally exposes data-finished and the winner marker, but
     * those fields are not guaranteed to be present on every row. In that
     * case we must not manufacture LIVE merely because kickoff is in the past.
     */
    private String resolvePastMatchStatus(String matchUrl) {
        if (matchUrl == null || matchUrl.isBlank()) {
            return "UNKNOWN";
        }

        try {
            WebClient.RequestHeadersSpec<?> request =
                    webClient.get().uri(matchUrl);

            if (liquipediaCookie != null && !liquipediaCookie.isBlank()) {
                request = request.header(
                        HttpHeaders.COOKIE,
                        liquipediaCookie);
            }

            String html = request
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(8));

            if (html == null || html.isBlank()) {
                return "UNKNOWN";
            }

            Document document = Jsoup.parse(html);

            Element result = document.selectFirst(
                    ".match-bm-match-header-result-text");

            if (result != null) {
                String phase = result.text()
                        .trim()
                        .toLowerCase(Locale.ROOT);

                if (phase.contains("live")
                        || phase.contains("ongoing")) {
                    return "LIVE";
                }

                if (phase.contains("finished")
                        || phase.contains("completed")) {
                    return "COMPLETED";
                }

                if (phase.contains("upcoming")) {
                    return "UPCOMING";
                }
            }

            /*
             * MatchPage pages also expose the finished state through the
             * result/winner structure. A winner on a past match is definitive.
             */
            if (!document.select(
                    ".match-bm-match-header-opponent.match-info-header-winner, " +
                            ".match-info-header-winner").isEmpty()) {
                return "COMPLETED";
            }

        } catch (Exception e) {
            log.debug(
                    "Could not resolve Liquipedia match phase from {}: {}",
                    matchUrl,
                    e.getMessage());
        }

        /*
         * A past match with no explicit completion marker is treated as LIVE.
         * Liquipedia can temporarily omit the finished marker on a live row;
         * using UNKNOWN here would make a genuinely long-running match disappear
         * from the active scheduler simply because the kickoff time is old.
         *
         * Explicit COMPLETED/winner/score evidence above still takes priority.
         */
        return "LIVE";
    }

    private String normalizeBestOf(String value) {

        if (value == null || value.isBlank()) {
            return "";
        }

        String normalized = value.trim()
                .replaceAll("\\s+", "")
                .toUpperCase(Locale.ROOT);

        if (normalized.matches("BO\\d+")) {
            return "Bo" + normalized.substring(2);
        }

        if (normalized.matches("B\\d+")) {
            return "Bo" + normalized.substring(1);
        }

        return value.trim();
    }

    private Integer parseScore(String value) {
        try {
            String v = value == null ? "" : value.trim();
            return v.matches("\\d+") ? Integer.valueOf(v) : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    private String buildMatchId(
            String game,
            String tournament,
            String team1,
            String team2,
            OffsetDateTime start,
            String matchUrl) {

        // Always use one internal ID format for every game.
        // Never expose the Liquipedia URL itself as matchId because the
        // match-detail API, persisted records and StreamSample.matchId must
        // all use the same namespace.
        String key;
        if (matchUrl != null && !matchUrl.isBlank()) {
            key = normalizeIdentity(matchUrl);
        } else {
            // Some Liquipedia pages (notably some CS2 rows) do not expose a
            // Match: URL. Use the stable match attributes as the fallback.
            key = String.join("|",
                    normalizeGame(game),
                    normalizeIdentity(tournament),
                    normalizeIdentity(team1),
                    normalizeIdentity(team2),
                    start.toInstant().toString());
        }

        return "match:" + Integer.toUnsignedString(key.hashCode(), 36);
    }

    private String normalizeIdentity(String value) {
        return value == null
                ? ""
                : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", " ");
    }

    private String fetch(String url) {

        WebClient.RequestHeadersSpec<?> request =
                webClient.get()
                        .uri(url)
                        .header(
                                HttpHeaders.ACCEPT,
                                "text/html,application/xhtml+xml," +
                                        "application/xml;q=0.9," +
                                        "image/avif,image/webp," +
                                        "image/apng,*/*;q=0.8")
                        .header(
                                HttpHeaders.ACCEPT_LANGUAGE,
                                "en-US,en;q=0.7")
                        .header(
                                HttpHeaders.CACHE_CONTROL,
                                "max-age=0")
                        .header(
                                HttpHeaders.USER_AGENT,
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                                        "AppleWebKit/537.36 " +
                                        "(KHTML, like Gecko) " +
                                        "Chrome/154.0.0.0 " +
                                        "Safari/537.36")
                        .header(
                                "sec-ch-ua",
                                "\"Chromium\";v=\"154\", " +
                                        "\"Google Chrome\";v=\"154\", " +
                                        "\"Not A(Brand\";v=\"99\"")
                        .header(
                                "sec-ch-ua-mobile",
                                "?0")
                        .header(
                                "sec-ch-ua-platform",
                                "\"Windows\"")
                        .header(
                                "sec-fetch-dest",
                                "document")
                        .header(
                                "sec-fetch-mode",
                                "navigate")
                        .header(
                                "sec-fetch-site",
                                "none")
                        .header(
                                "sec-fetch-user",
                                "?1")
                        .header(
                                "sec-gpc",
                                "1")
                        .header(
                                "upgrade-insecure-requests",
                                "1");

        if (liquipediaCookie != null
                && !liquipediaCookie.isBlank()) {

            request =
                    request.header(
                            HttpHeaders.COOKIE,
                            liquipediaCookie);
        }

        return request
                .retrieve()
                .bodyToMono(String.class)
                .block();
    }

    private String normalizeGame(String game) {

        if (game == null) {
            return "";
        }

        String value =
                game.trim()
                        .toUpperCase(Locale.ROOT)
                        .replace('_', ' ');

        if ("CS2".equals(value)
                || "COUNTER STRIKE".equals(value)) {

            return "COUNTER-STRIKE 2";
        }

        if ("COUNTER-STRIKE".equals(value)) {
            return "COUNTER-STRIKE 2";
        }

        if ("LOL".equals(value)) {
            return "LEAGUE OF LEGENDS";
        }

        if ("DOTA2".equals(value)) {
            return "DOTA 2";
        }

        return value;
    }

    private String stripFragment(String href) {

        if (href == null) {
            return "";
        }

        int hash =
                href.indexOf('#');

        return hash >= 0
                ? href.substring(0, hash)
                : href;
    }

    private record CacheEntry(
            Instant expiresAt,
            List<EsportsMatch> matches) {
    }

    private record LogoCacheEntry(
            Instant expiresAt,
            Map<String, String> logos) {
    }

    public record EsportsMatch(
            String matchId,
            String game,
            String tournament,
            String tier,
            String team1,
            String team2,
            Integer team1Score,
            Integer team2Score,
            String bestOf,
            String winner,
            boolean finished,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            String status,
            String tournamentUrl,
            String matchUrl) {

        /**
         * Liquipedia is the source of truth for match state.
         *
         * UPCOMING is never active. COMPLETED/finished is never active.
         * A match is active only while the scraper has classified it as LIVE.
         *
         * There is deliberately no start + 4 hour fallback here: that old
         * heuristic caused completed matches to remain active and made the
         * scheduler collect a game after its esports match had ended.
         */
        public boolean isActive(Instant now) {
            if (finished || "COMPLETED".equalsIgnoreCase(status)
                    || "UPCOMING".equalsIgnoreCase(status)) {
                return false;
            }
            return "LIVE".equalsIgnoreCase(status)
                    && startTime != null
                    && !startTime.toInstant().isAfter(now);
        }
    }
}