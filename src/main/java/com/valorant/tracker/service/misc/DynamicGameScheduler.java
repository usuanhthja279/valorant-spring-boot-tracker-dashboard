package com.valorant.tracker.service.misc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
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

    public DynamicGameScheduler(
            LiquipediaEsportsScheduleScraper scraper) {

        this.scraper = scraper;
    }

    /**
     * Check Liquipedia every 5 minutes.
     */
    @Scheduled(fixedRateString = "${tracker.esports.schedule-check-ms:300000}")
    public void refreshSchedule() {
        log.info("Refreshing esports game schedule...");
        for (String game : ESPORTS_GAMES) {
            try {
                boolean active = scraper.hasActiveMatch(game);
                boolean wasActive = activeGames.contains(game);
                if (active && !wasActive) {
                    activeGames.add(game);
                    log.info("ESPORTS GAME STARTED: {}", game);
                } else if (!active && wasActive) {
                    activeGames.remove(game);
                    log.info("ESPORTS GAME STOPPED: {}", game);
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
