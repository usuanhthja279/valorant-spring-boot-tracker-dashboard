package com.valorant.tracker.controller;

import com.valorant.tracker.model.LiveStream;
import com.valorant.tracker.service.KickScraperService;
import com.valorant.tracker.service.TwitchService;
import com.valorant.tracker.service.YouTubeScraperService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/streams")
public class StreamingController {
  private final YouTubeScraperService youTubeScraperService;
  private final TwitchService twitchService;
  private final KickScraperService kickScraperService;

  public StreamingController(
      YouTubeScraperService youTubeScraperService,
      TwitchService twitchService,
      KickScraperService kickScraperService) {
    this.youTubeScraperService = youTubeScraperService;
    this.twitchService = twitchService;
    this.kickScraperService = kickScraperService;
  }

  @GetMapping("/youtube")
  public List<LiveStream> getYouTubeStreams() {
    return youTubeScraperService.fetch();
  }

  @GetMapping("/youtube/scraped")
  public List<LiveStream> getScrapedYouTubeStreams() {
    return youTubeScraperService.fetch();
  }

  @GetMapping("/twitch")
  public List<LiveStream> getTwitchStreams() {
    return twitchService.fetch();
  }

  @GetMapping("/kick")
  public List<LiveStream> getKickStreams() {
    return kickScraperService.fetch();
  }

  @GetMapping("/kick/scraped")
  public List<LiveStream> getScrapedKickStreams() {
    return kickScraperService.fetch();
  }
}
