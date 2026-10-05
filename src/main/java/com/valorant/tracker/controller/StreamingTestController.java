package com.valorant.tracker.controller;

import com.valorant.tracker.model.LiveStream;
import com.valorant.tracker.service.api.KickService;
import com.valorant.tracker.service.api.YouTubeService;
import com.valorant.tracker.service.scraper.KickScraperService;
import com.valorant.tracker.service.api.TwitchService;
import com.valorant.tracker.service.scraper.YouTubeScraperService;
import java.util.List;
import java.util.Map;

import com.valorant.tracker.service.selenium.KickSeleniumDiscoveryService;
import com.valorant.tracker.service.selenium.TwitchSeleniumDiscoveryService;
import com.valorant.tracker.service.selenium.YouTubeSeleniumWorldwideDiscoveryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/streams")
public class StreamingTestController {
  private final YouTubeService youTubeService;
  private final TwitchService twitchService;
  private final KickService kickService;

  private final YouTubeSeleniumWorldwideDiscoveryService youTubeSeleniumWorldwideDiscoveryService;
  private final TwitchSeleniumDiscoveryService twitchSeleniumDiscoveryService;
  private final KickSeleniumDiscoveryService kickSeleniumDiscoveryService;
  private final YouTubeScraperService youTubeScraperService;
  private final KickScraperService kickScraperService;

  public StreamingTestController(
          YouTubeService youTubeService, KickService kickService, YouTubeSeleniumWorldwideDiscoveryService youTubeSeleniumWorldwideDiscoveryService, TwitchSeleniumDiscoveryService twitchSeleniumDiscoveryService, KickSeleniumDiscoveryService kickSeleniumDiscoveryService, YouTubeScraperService youTubeScraperService,
          TwitchService twitchService,
          KickScraperService kickScraperService) {
      this.youTubeService = youTubeService;
      this.kickService = kickService;
      this.youTubeSeleniumWorldwideDiscoveryService = youTubeSeleniumWorldwideDiscoveryService;
      this.twitchSeleniumDiscoveryService = twitchSeleniumDiscoveryService;
      this.kickSeleniumDiscoveryService = kickSeleniumDiscoveryService;
      this.youTubeScraperService = youTubeScraperService;
      this.twitchService = twitchService;
      this.kickScraperService = kickScraperService;
  }

  @GetMapping("/youtube/rest")
  public List<LiveStream> getYouTubeStreams(@RequestParam String gameCategory) {
    return youTubeService.fetch(gameCategory);
  }

  @GetMapping("/twitch/rest")
  public List<LiveStream> getTwitchStreams(@RequestParam String gameCategory) {
    return twitchService.fetch(gameCategory.toUpperCase());
  }

  @GetMapping("/kick/rest")
  public List<LiveStream> getKickStreams(@RequestParam String gameCategory) {
    return kickService.fetch(gameCategory.toUpperCase());
  }

  @GetMapping("/youtube/scraped")
  public List<LiveStream> getScrapedYouTubeStreams(
          @RequestParam(required = false) String pageUrl,
          @RequestParam(defaultValue = "false") boolean validateWithApi) {
    return youTubeScraperService.fetch(pageUrl, validateWithApi);
  }

  // TODO : Implement Twitch scraper service and uncomment the line below
  @GetMapping("/twitch/scraped")
  public List<LiveStream> getScrapedTwitchStreams(
          @RequestParam(required = false) String pageUrl,
          @RequestParam(defaultValue = "false") boolean validateWithApi) {
//    return twitchService.fetch(pageUrl, validateWithApi);
    return null;
  }

  @GetMapping("/kick/scraped")
  public List<LiveStream> getScrapedKickStreams(
          @RequestParam(required = false) String pageUrl,
          @RequestParam(defaultValue = "false") boolean validateWithApi) {
    return kickScraperService.fetch(pageUrl, validateWithApi);
  }

  @GetMapping("/youtube/selenium-scraped")
  public Map<String, Object> seleniumYouTube(
          @RequestParam(required = false, defaultValue = "worldwide") String region,
          @RequestParam(defaultValue = "100") int maxStreams,
          @RequestParam(required = false) String pageUrl,
          @RequestParam(defaultValue = "false") boolean validateWithApi) {
    return youTubeSeleniumWorldwideDiscoveryService.discover(maxStreams, pageUrl, region, validateWithApi);
  }

  @GetMapping("/twitch/selenium-scraped")
  public Map<String, Object> seleniumTwitch(
          @RequestParam(defaultValue = "100") int maxStreams,
          @RequestParam(required = false) String pageUrl,
          @RequestParam(defaultValue = "false") boolean validateWithApi) {
    return twitchSeleniumDiscoveryService.discover(maxStreams, pageUrl, validateWithApi);
  }

  @GetMapping("/kick/selenium-scraped")
  public Map<String, Object> discover(
          @RequestParam(defaultValue = "50") int maxStreams,
          @RequestParam(required = false) String pageUrl,
          @RequestParam(defaultValue = "false") boolean validateWithApi) {
    return kickSeleniumDiscoveryService.discover(maxStreams, pageUrl, validateWithApi);
  }
}
