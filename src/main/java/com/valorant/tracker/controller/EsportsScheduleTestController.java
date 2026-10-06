package com.valorant.tracker.controller;

import com.valorant.tracker.service.misc.DynamicGameScheduler;
import com.valorant.tracker.service.misc.LiquipediaEsportsScheduleScraper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/esports/schedule")
public class EsportsScheduleTestController {

    private final LiquipediaEsportsScheduleScraper scraper;
    private final DynamicGameScheduler scheduler;

    public EsportsScheduleTestController(
            LiquipediaEsportsScheduleScraper scraper,
            DynamicGameScheduler scheduler) {

        this.scraper = scraper;
        this.scheduler = scheduler;
    }

    /**
     * Test Liquipedia scraper.
     *
     * GET /api/esports/schedule/test?game=VALORANT
     */
    @GetMapping("/test")
    public ResponseEntity<?> testSchedule(@RequestParam(defaultValue = "VALORANT") String game) {
        return ResponseEntity.ok(scraper.getUpcomingMatches(game));
    }

    /**
     * Get currently active esports games.
     *
     * GET /api/esports/schedule/active
     */
    @GetMapping("/active")
    public ResponseEntity<Set<String>> activeGames() {

        return ResponseEntity.ok(
                scheduler.getActiveGames()
        );
    }

    /**
     * Check whether a specific game is active.
     *
     * GET /api/esports/schedule/status?game=VALORANT
     */
    @GetMapping("/status")
    public ResponseEntity<SchedulerStatus> status(
            @RequestParam String game) {

        return ResponseEntity.ok(
                new SchedulerStatus(
                        game,
                        scheduler.isGameActive(game)
                )
        );
    }

    /**
     * Get all games managed by the scheduler.
     *
     * GET /api/esports/schedule/games
     */
    @GetMapping("/games")
    public ResponseEntity<List<String>> games() {

        return ResponseEntity.ok(
                scheduler.getManagedGames()
        );
    }

    /**
     * Force an immediate schedule refresh.
     *
     * POST /api/esports/schedule/refresh
     */
    @PostMapping("/refresh")
    public ResponseEntity<Set<String>> refresh() {

        scheduler.refreshNow();

        return ResponseEntity.ok(
                scheduler.getActiveGames()
        );
    }

    public record SchedulerStatus(
            String game,
            boolean active
    ) {
    }
}