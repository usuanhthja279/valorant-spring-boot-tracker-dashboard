package com.valorant.tracker.controller;

import com.valorant.tracker.service.selenium.YouTubeSeleniumWorldwideDiscoveryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manual test endpoint only. This service is not wired into production tracker data. */
@RestController
@RequestMapping("/api/test/youtube/selenium")
public class YouTubeSeleniumWorldwideDiscoveryController {
  private final YouTubeSeleniumWorldwideDiscoveryService service;

  public YouTubeSeleniumWorldwideDiscoveryController(YouTubeSeleniumWorldwideDiscoveryService service) {
    this.service = service;
  }

  @GetMapping("/worldwide")
  public Map<String, Object> discover(
      @RequestParam(defaultValue = "100") int maxStreams,
      @RequestParam(required = false) String pageUrl,
      @RequestParam(defaultValue = "true") boolean validateWithApi) {
    return service.discover(maxStreams, pageUrl, validateWithApi);
  }
}
