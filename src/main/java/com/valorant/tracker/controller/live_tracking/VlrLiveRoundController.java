package com.valorant.tracker.controller.live_tracking;

import com.valorant.tracker.service.misc.VlrLiveRoundTrackerService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;

@RestController
@RequestMapping("/api/vlr/live")
public class VlrLiveRoundController {
    private final VlrLiveRoundTrackerService tracker;
    public VlrLiveRoundController(VlrLiveRoundTrackerService tracker) { this.tracker = tracker; }

    @GetMapping("/{matchId}")
    public Map<String, Object> state(@PathVariable String matchId) {
        try { return tracker.getState(matchId); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    @PostMapping("/register")
    public Map<String, Object> register(@RequestParam String matchId,
                                        @RequestParam(defaultValue = "VALORANT") String game,
                                        @RequestParam String matchUrl,
                                        @RequestParam String gameId) {
        try { return tracker.register(matchId, game, matchUrl, gameId); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }

    @PostMapping("/{matchId}/{gameId}/stop")
    public Map<String, Object> stop(@PathVariable String matchId, @PathVariable String gameId) {
        try { return tracker.stop(matchId, gameId); }
        catch (IllegalArgumentException e) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage()); }
    }
}
