package com.valorant.tracker.controller.live_tracking;

import com.valorant.tracker.service.misc.VlrRoundLogService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Manual endpoint for retrieving and inspecting one VLR game's round-log page. */
@RestController
@RequestMapping("/api/vlr/matches")
public class VlrRoundLogController {
    private final VlrRoundLogService vlrRoundLogService;

    public VlrRoundLogController(VlrRoundLogService vlrRoundLogService) {
        this.vlrRoundLogService = vlrRoundLogService;
    }

    /** Example: /api/vlr/matches/round-logs?matchUrl=...&gameId=283283 */
    @GetMapping("/round-logs")
    public Map<String, Object> getRoundLogs(
            @RequestParam String matchUrl,
            @RequestParam String gameId,
            @RequestParam(required = false) String round) {
        try {
            return vlrRoundLogService.fetchRoundLogs(matchUrl, gameId, round);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
