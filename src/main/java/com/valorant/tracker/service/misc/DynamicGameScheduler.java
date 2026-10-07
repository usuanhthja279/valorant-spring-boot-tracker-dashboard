package com.valorant.tracker.service.misc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import com.valorant.tracker.model.EsportsMatchRecord;
import com.valorant.tracker.repository.EsportsMatchRecordRepository;
import java.time.OffsetDateTime;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Component;

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
     * Games currently within the match collection window.
     *
     * The window starts 5 minutes before scheduled kickoff and ends at the
     * parsed match end time (or the 4-hour fallback when no end time exists).
     * UI "Live now" must still be based on actual live stream data.
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
    @PostConstruct
    public void initializeSchedule() {
        refreshSchedule();
    }

    /**
     * Check Liquipedia every 2 minutes. The scheduler also initializes at
     * startup, so the first collection window is not delayed until the next
     * scheduled refresh.
     */
    @Scheduled(fixedRateString = "${tracker.esports.schedule-check-ms:120000}")
    @Transactional
    public void refreshSchedule() {
        log.info("Refreshing esports game schedule...");
        for (String game : ESPORTS_GAMES) {
            try {
                List<LiquipediaEsportsScheduleScraper.EsportsMatch> scheduledMatches =
                        scraper.getUpcomingAndLive(game).stream()
                                .filter(match -> match.startTime() != null)
                                .toList();

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
                        match.team1(), match.team2(), match.startTime(), match.endTime(), match.status(),
                        match.tournamentUrl(), match.matchUrl(), seenAt);
            } else {
                record.update(game, match.tournament(), match.tier(), match.team1(), match.team2(),
                        match.startTime(), match.endTime(), match.status(), match.tournamentUrl(), match.matchUrl(), seenAt);
            }
            matchRepository.save(record);
        }
    }

    /**
     * Returns true when the game is inside an esports match collection window.
     * This can be up to 5 minutes before kickoff.
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
