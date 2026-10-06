package com.valorant.tracker.service.misc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
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
            LiquipediaEsportsScheduleScraper scraper) {

        this.scraper = scraper;
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
     * Check Liquipedia every 5 minutes.
     */
    @Scheduled(fixedRateString = "${tracker.esports.schedule-check-ms:300000}")
    public void refreshSchedule() {
        log.info("Refreshing esports game schedule...");
        for (String game : ESPORTS_GAMES) {
            try {
                List<LiquipediaEsportsScheduleScraper.EsportsMatch> matches =
                        scraper.getUpcomingAndLive(game).stream()
                                .filter(match -> match.startTime() != null)
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
