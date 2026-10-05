package com.valorant.tracker.controller;

import com.valorant.tracker.service.tracker.SeleniumTrackerService;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Manual endpoint for the Selenium aggregate tracker.
 * It is intentionally separate from the existing /api/collect flow.
 */
@RestController
@RequestMapping("/api/selenium-tracker")
public class SeleniumTrackerController {
  private final SeleniumTrackerService tracker;

  public SeleniumTrackerController(SeleniumTrackerService tracker) {
    this.tracker = tracker;
  }

  @PostMapping("/collect")
  public Map<String, Object> collect() {
    return tracker.collect();
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    return Map.of(
        "running", true,
        "lastRun", tracker.getLastRun() == null ? "not-run" : tracker.getLastRun(),
        "providers", tracker.getProviderHealth());
  }
}
