package com.valorant.tracker.controller.live_tracking;

import com.valorant.tracker.service.misc.VlrGameUrlService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/** Manual endpoint for discovering game IDs/URLs on an already-discovered VLR match page. */
@RestController
@RequestMapping("/api/vlr/matches")
public class VlrGameUrlController {
    private final VlrGameUrlService vlrGameUrlService;

    public VlrGameUrlController(VlrGameUrlService vlrGameUrlService) {
        this.vlrGameUrlService = vlrGameUrlService;
    }

    /** Example: /api/vlr/matches/game-urls?matchUrl=https%3A%2F%2Fwww.vlr.gg%2F754736%2Fteam-vitality-vs-loud-valorant-champions-2026-ubf */
    @GetMapping("/game-urls")
    public Map<String, Object> findGameUrls(@RequestParam String matchUrl) {
        try {
            return vlrGameUrlService.findGameUrls(matchUrl);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }
}
