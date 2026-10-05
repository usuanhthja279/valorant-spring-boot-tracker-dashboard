package com.valorant.tracker.controller;

import com.valorant.tracker.service.selenium.KickSeleniumDiscoveryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manual Selenium test endpoint only; it is not wired into production tracker data. */
@RestController
@RequestMapping("/api/test/kick/selenium")
public class KickSeleniumDiscoveryController {
    private final KickSeleniumDiscoveryService service;

    public KickSeleniumDiscoveryController(KickSeleniumDiscoveryService service) {
        this.service = service;
    }

    @GetMapping("/viewers-high-to-low")
    public Map<String, Object> discover(
            @RequestParam(defaultValue = "50") int maxStreams,
            @RequestParam(required = false) String pageUrl) {
        return service.discover(maxStreams, pageUrl);
    }
}
