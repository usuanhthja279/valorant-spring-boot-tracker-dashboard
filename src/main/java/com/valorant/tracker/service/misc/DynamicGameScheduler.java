package com.valorant.tracker.service.misc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import com.valorant.tracker.model.EsportsMatchRecord;
import com.valorant.tracker.repository.EsportsMatchRecordRepository;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class DynamicGameScheduler {

    private static final Logger log = LoggerFactory.getLogger(DynamicGameScheduler.class);

    private final LiquipediaEsportsScheduleScraper scraper;
    private final EsportsMatchRecordRepository matchRepository;

    /**
     * Games for which we want dynamic esports scheduling.
     */
    private static final List<String> ESPORTS_GAMES = List.of(
            "VALORANT",
            "COUNTER-STRIKE 2",
            "LEAGUE OF LEGENDS",
            "DOTA 2",
            "FORTNITE",
            "RAINBOW SIX SIEGE",
            "ROCKET LEAGUE",
            "APEX LEGENDS",
            "OVERWATCH 2",
            "EA SPORTS FC",
            "PUBG",
            "CALL OF DUTY",
            "MOBILE LEGENDS",
            "FREE FIRE"
    );

    /**
     * Games currently having an active S/A-tier match.
     */
    private final Set<String> activeGames =
            ConcurrentHashMap.newKeySet();

    /** All currently active Liquipedia matches per game. Multiple matches may overlap. */
    private final ConcurrentHashMap<String, List<LiquipediaEsportsScheduleScraper.EsportsMatch>> activeMatches =
            new ConcurrentHashMap<>();

    public DynamicGameScheduler(
            LiquipediaEsportsScheduleScraper scraper,
            EsportsMatchRecordRepository matchRepository) {

        this.scraper = scraper;
        this.matchRepository = matchRepository;
    }

    /**
     * Populate the scheduler immediately at application startup so an active
     * match does not wait for the first 5-minute scheduled refresh.
     */
    /**
     * Run after Hibernate and the idempotent schema repair are ready.
     * This avoids the startup race where the scheduler queried
     * esports_matches before newly-added score/status columns existed.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void initializeSchedule() {
        refreshSchedule();
    }

    /**
     * Check Liquipedia every 5 minutes.
     */
    @Scheduled(fixedRateString = "${tracker.esports.schedule-check-ms:300000}")
    @Transactional
    public synchronized void refreshSchedule() {
        log.info("Refreshing esports game schedule...");
        for (String game : ESPORTS_GAMES) {
            try {
                List<LiquipediaEsportsScheduleScraper.EsportsMatch> scrapedMatches =
                        scraper.getUpcomingAndLive(game).stream()
                                .filter(match -> match.startTime() != null)
                                .toList();

                // One Liquipedia row must produce one persistence operation.
                // This also protects against duplicate DOM rows from a single scrape.
                List<LiquipediaEsportsScheduleScraper.EsportsMatch> scheduledMatches =
                        new java.util.ArrayList<>(
                                scrapedMatches.stream()
                                        .collect(java.util.stream.Collectors.toMap(
                                                LiquipediaEsportsScheduleScraper.EsportsMatch::matchId,
                                                match -> match,
                                                (first, second) -> second,
                                                java.util.LinkedHashMap::new))
                                        .values());

                persistMatchCatalog(game, scheduledMatches);

                List<LiquipediaEsportsScheduleScraper.EsportsMatch> matches =
                        scheduledMatches.stream()
                                .filter(match -> match.isActive(java.time.Instant.now()))
                                .sorted(java.util.Comparator.comparing(
                                        LiquipediaEsportsScheduleScraper.EsportsMatch::startTime))
                                .toList();

                boolean active = !matches.isEmpty();
                boolean wasActive = activeGames.contains(game);

                if (active) {
                    activeGames.add(game);
                    activeMatches.put(game, List.copyOf(matches));
                    if (!wasActive) {
                        log.info("ESPORTS GAME STARTED: {} | {} active matches", game, matches.size());
                    }
                    for (var match : matches) {
                        log.debug("ACTIVE MATCH: {} | {} vs {} | {}",
                                match.matchId(), match.team1(), match.team2(), match.tournament());
                    }
                } else if (wasActive) {
                    activeGames.remove(game);
                    activeMatches.remove(game);
                    log.info("ESPORTS GAME STOPPED: {}", game);
                } else {
                    activeMatches.remove(game);
                }
            } catch (Exception e) {
                log.warn("Failed to check esports schedule for game={}: {}", game, e.getMessage());
            }
        }

        log.info("Currently active esports games: {}", activeGames);
    }

    private void persistMatchCatalog(String game, List<LiquipediaEsportsScheduleScraper.EsportsMatch> matches) {
        OffsetDateTime seenAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        for (var match : matches) {
            if (match.matchId() == null || match.matchId().isBlank()) continue;
            EsportsMatchRecord record = matchRepository.findById(match.matchId()).orElse(null);

            // Older builds used the full Liquipedia match URL as the database
            // primary key. Migrate that legacy record to the normalized
            // match:<hash> identity when the same match URL is seen again.
            if (record == null && match.matchUrl() != null && !match.matchUrl().isBlank()) {
                List<EsportsMatchRecord> legacy = matchRepository.findByMatchUrl(match.matchUrl());
                if (!legacy.isEmpty()) {
                    for (EsportsMatchRecord oldRecord : legacy) {
                        if (!match.matchId().equals(oldRecord.getMatchId())) {
                            matchRepository.delete(oldRecord);
                        }
                    }
                }
            }

            if (record == null) {
                record = new EsportsMatchRecord(match.matchId(), game, match.tournament(), match.tier(),
                        match.team1(), match.team2(), match.team1Score(), match.team2Score(),
                        match.bestOf(), match.winner(), match.finished(), match.startTime(), match.endTime(),
                        match.status(), match.tournamentUrl(), match.matchUrl(), seenAt);
            } else {
                record.update(game, match.tournament(), match.tier(), match.team1(), match.team2(),
                        match.team1Score(), match.team2Score(), match.bestOf(), match.winner(), match.finished(),
                        match.startTime(), match.endTime(), match.status(), match.tournamentUrl(), match.matchUrl(), seenAt);
            }
            matchRepository.save(record);
        }
    }

    /**
     * Returns true when the game currently has
     * an active esports match.
     */
    public boolean isGameActive(String game) {

        if (game == null) {
            return false;
        }

        return activeGames.contains(normalizeGame(game));
    }

    /** Returns all active Liquipedia matches for a game. */
    public List<LiquipediaEsportsScheduleScraper.EsportsMatch> getActiveMatches(String game) {
        if (game == null) {
            return List.of();
        }
        return activeMatches.getOrDefault(normalizeGame(game), List.of());
    }

    /** Backward-compatible convenience method returning the first active match. */
    public Optional<LiquipediaEsportsScheduleScraper.EsportsMatch> getActiveMatch(String game) {
        return getActiveMatches(game).stream().findFirst();
    }

    public boolean isManagedGame(String game) {

        if (game == null) {
            return false;
        }

        return ESPORTS_GAMES.contains(normalizeGame(game));
    }

    private String normalizeGame(String game) {

        String value = game.trim()
                .toUpperCase()
                .replace('_', ' ');

        return switch (value) {
            case "CS2", "COUNTER STRIKE", "COUNTER-STRIKE" ->
                    "COUNTER-STRIKE 2";

            case "LOL" ->
                    "LEAGUE OF LEGENDS";

            case "DOTA2" ->
                    "DOTA 2";

            default ->
                    value;
        };
    }

    /**
     * Returns a snapshot of currently active games.
     */
    public Set<String> getActiveGames() {

        return Set.copyOf(activeGames);
    }

    /**
     * Returns all games managed by this scheduler.
     */
    public List<String> getManagedGames() {

        return ESPORTS_GAMES;
    }

    /**
     * Manually refresh the schedule.
     *
     * Useful for testing from a controller.
     */
    public void refreshNow() {
        refreshSchedule();
    }
}
