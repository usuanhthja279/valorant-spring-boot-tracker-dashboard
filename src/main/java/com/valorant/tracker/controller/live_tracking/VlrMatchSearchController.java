package com.valorant.tracker.controller.live_tracking;

import com.valorant.tracker.service.misc.VlrMatchSearchService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * Isolated controller for manually testing VLR.gg match discovery by team names.
 * This endpoint is not wired into DynamicGameScheduler.
 */
@RestController
@RequestMapping("/api/vlr/matches")
public class VlrMatchSearchController {

    private final VlrMatchSearchService vlrMatchSearchService;

    public VlrMatchSearchController(VlrMatchSearchService vlrMatchSearchService) {
        this.vlrMatchSearchService = vlrMatchSearchService;
    }

    /**
     * Search VLR's matches page for a match involving both teams.
     * Example: /api/vlr/matches/search?team1=G2%20Esports&team2=T1
     */
    @GetMapping("/search")
    public Map<String, Object> searchMatch(
            @RequestParam String team1,
            @RequestParam String team2) {
        try {
            return vlrMatchSearchService.findMatch(team1, team2);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
