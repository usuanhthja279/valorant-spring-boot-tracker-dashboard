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

        if (current != null &&
                Instant.now().isBefore(current.expiresAt())) {

            return current.matches();
        }

        List<EsportsMatch> scraped =
                scrape(normalized);

        cache.put(
                normalized,
                new CacheEntry(
                        Instant.now().plus(cacheDuration),
                        scraped));

        return scraped;
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

        String wiki =
                WIKIS.get(normalizeGame(game));

        if (wiki == null) {

            log.warn(
                    "No Liquipedia wiki configured for game={}",
                    game);

            return Collections.emptyList();
        }

        try {

            /*
             * Fetch Main_Page ONCE.
             */
            String url =
                    BASE + wiki + "/Main_Page";

            String html = fetch(url);

            Document document =
                    Jsoup.parse(html, url);

            /*
             * Extract tournament -> tier information
             * from the same Main_Page.
             */
            Map<String, String> tournamentTiers =
                    extractTournamentTiers(
                            document,
                            wiki);

            List<EsportsMatch> result =
                    new ArrayList<>();

            /*
             * Parse match rows directly from Main_Page.
             */
            for (Element row :
                    document.select("div.match-info")) {

                EsportsMatch match =
                        parseMatch(
                                game,
                                row,
                                tournamentTiers);

                if (match == null) {
                    continue;
                }

                if (isEligibleTier(game, match.tier())) {
                    result.add(match);
                }
            }

            result.sort(
                    Comparator.comparing(
                            EsportsMatch::startTime));

            log.info(
                    "Liquipedia {} schedule: {} eligible matches",
                    game,
                    result.size());

            return List.copyOf(result);

        } catch (Exception e) {

            log.warn(
                    "Liquipedia scrape failed for game={}: {}",
                    game,
                    e.getMessage());

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
    private boolean isEligibleTier(
            String game,
            String tier) {

        if (tier == null) {
            return false;
        }

        String normalized =
                normalizeGame(game);

        /*
         * Dota 2:
         * Tier 1 + Tier 2
         */
        if ("DOTA 2".equals(normalized)) {

            return "T1".equals(tier)
                    || "T2".equals(tier);
        }

        /*
         * Other esports:
         * S + A
         */
        return "S".equals(tier)
                || "A".equals(tier);
    }

    private EsportsMatch parseMatch(
            String game,
            Element row,
            Map<String, String> tournamentTiers) {

        try {

            Element timer =
                    row.selectFirst(
                            ".match-info-countdown " +
                                    ".timer-object[data-timestamp]");

            if (timer == null) {
                return null;
            }

            long epoch =
                    Long.parseLong(
                            timer.attr("data-timestamp"));

            OffsetDateTime start =
                    OffsetDateTime.ofInstant(
                            Instant.ofEpochSecond(epoch),
                            ZoneOffset.UTC);

            Element tournamentAnchor =
                    row.selectFirst(
                            ".match-info-tournament-name a[href]");

            if (tournamentAnchor == null) {
                return null;
            }

            String tournamentUrl =
                    stripFragment(
                            tournamentAnchor.absUrl("href"));

            String tournament =
                    tournamentAnchor.text().trim();

            if (tournament.isBlank()) {
                tournament =
                        tournamentAnchor.attr("title");
            }

            String tier =
                    tournamentTiers.get(tournamentUrl);

            /*
             * If the tournament wasn't found in the
             * tournament list, don't invent a tier.
             */
            if (tier == null) {
                return null;
            }

            Elements teams =
                    row.select(
                            ".match-info-header-opponent .name");

            String team1 =
                    teams.size() > 0
                            ? teams.get(0).text().trim()
                            : "TBD";

            String team2 =
                    teams.size() > 1
                            ? teams.get(1).text().trim()
                            : "TBD";

            String matchUrl = "";

            Element matchLink =
                    row.selectFirst(
                            ".match-page-button a[href], " +
                                    "a[href*='/Match:']");

            if (matchLink != null) {
                matchUrl =
                        stripFragment(
                                matchLink.absUrl("href"));
            }

            String status =
                    start.isAfter(
                            OffsetDateTime.now(
                                    ZoneOffset.UTC))
                            ? "UPCOMING"
                            : "LIVE_OR_RECENT";

            String matchId = buildMatchId(
                    game, tournament, team1, team2, start, matchUrl);

            OffsetDateTime end = start.plusHours(4);

            return new EsportsMatch(
                    matchId,
                    game,
                    tournament,
                    tier,
                    team1,
                    team2,
                    start,
                    end,
                    status,
                    tournamentUrl,
                    matchUrl);

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

        if (matchUrl != null && !matchUrl.isBlank()) {
            return matchUrl;
        }

        String key = String.join("|",
                normalizeGame(game),
                normalizeIdentity(tournament),
                normalizeIdentity(team1),
                normalizeIdentity(team2),
                start.toInstant().toString());

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

    public record EsportsMatch(
            String matchId,
            String game,
            String tournament,
            String tier,
            String team1,
            String team2,
            OffsetDateTime startTime,
            OffsetDateTime endTime,
            String status,
            String tournamentUrl,
            String matchUrl) {

        public boolean isActive(Instant now) {

            Instant start =
                    startTime.toInstant();

            /*
             * Match is considered active from kickoff
             * until 4 hours after kickoff.
             */
            return !start.isAfter(now)
                    && start.plus(
                            Duration.ofHours(4))
                    .isAfter(now);
        }
    }
}