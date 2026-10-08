package com.valorant.tracker.controller;

import com.valorant.tracker.service.misc.DynamicGameScheduler;
import com.valorant.tracker.service.misc.LiquipediaEsportsScheduleScraper;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;

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
     * Diagnostic endpoint for the Liquipedia Main_Page parser.
     *
     * Examples:
     * GET /api/esports/schedule/test?game=COUNTER-STRIKE 2
     * GET /api/esports/schedule/test?game=counter-strike&refresh=true
     * GET /api/esports/schedule/test?game=VALORANT&status=LIVE
     *
     * This tests the Liquipedia parser directly. It does NOT read
     * esports_matches from the database.
     */
    @GetMapping("/test")
    public ResponseEntity<?> testSchedule(
            @RequestParam(defaultValue = "VALORANT") String game,
            @RequestParam(defaultValue = "false") boolean refresh,
            @RequestParam(defaultValue = "all") String status) {

        List<LiquipediaEsportsScheduleScraper.EsportsMatch> matches = refresh
                ? scraper.refresh(game)
                : scraper.getUpcomingAndLive(game);

        String wantedStatus = status == null
                ? "ALL"
                : status.trim().toUpperCase(Locale.ROOT);

        List<Map<String, Object>> rows = matches.stream()
                .filter(match ->
                        "ALL".equals(wantedStatus)
                                || wantedStatus.equals(
                                String.valueOf(match.status())
                                        .toUpperCase(Locale.ROOT)))
                .map(match -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("matchId", match.matchId());
                    row.put("game", match.game());
                    row.put("status", match.status());
                    row.put("finished", match.finished());
                    row.put("team1", match.team1());
                    row.put("team2", match.team2());
                    row.put("team1Score", match.team1Score());
                    row.put("team2Score", match.team2Score());
                    row.put("winner", match.winner());
                    row.put("bestOf", match.bestOf());
                    row.put("tournament", match.tournament());
                    row.put("tier", match.tier());
                    row.put("startTime", match.startTime());
                    row.put("endTime", match.endTime());
                    row.put("matchUrl", match.matchUrl());
                    row.put("tournamentUrl", match.tournamentUrl());
                    return row;
                })
                .toList();

        long live = rows.stream()
                .filter(r -> "LIVE".equals(r.get("status")))
                .count();
        long upcoming = rows.stream()
                .filter(r -> "UPCOMING".equals(r.get("status")))
                .count();
        long completed = rows.stream()
                .filter(r -> "COMPLETED".equals(r.get("status")))
                .count();
        long unknown = rows.stream()
                .filter(r -> "UNKNOWN".equals(r.get("status")))
                .count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("game", game);
        response.put("source", "Liquipedia Main_Page");
        response.put("refreshed", refresh);
        response.put("checkedAt", Instant.now());
        response.put("total", rows.size());
        response.put("live", live);
        response.put("upcoming", upcoming);
        response.put("completed", completed);
        response.put("unknown", unknown);
        response.put("matches", rows);

        return ResponseEntity.ok(response);
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
        return ResponseEntity.ok(scheduler.getActiveGames());
    }

    public record SchedulerStatus(
            String game,
            boolean active) {
    }
}