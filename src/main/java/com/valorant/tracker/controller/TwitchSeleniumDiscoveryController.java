package com.valorant.tracker.controller;

import com.valorant.tracker.service.selenium.TwitchSeleniumDiscoveryService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Manual Twitch Selenium discovery endpoint. */
@RestController
@RequestMapping("/api/test/twitch/selenium")
public class TwitchSeleniumDiscoveryController {
  private final TwitchSeleniumDiscoveryService service;

  public TwitchSeleniumDiscoveryController(TwitchSeleniumDiscoveryService service) {
    this.service = service;
  }

  @GetMapping("/viewers-high-to-low")
  public Map<String, Object> discover(
      @RequestParam(defaultValue = "100") int maxStreams,
      @RequestParam(required = false) String pageUrl) {
    return service.discover(maxStreams, pageUrl);
  }
}
