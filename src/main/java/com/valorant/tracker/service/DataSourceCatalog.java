package com.valorant.tracker.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class DataSourceCatalog {
  private final Sources sources;

  public DataSourceCatalog(ObjectMapper mapper) {
    try (var input = new ClassPathResource("data-sources.json").getInputStream()) {
      sources = mapper.readValue(input, Sources.class);
    } catch (IOException error) {
      throw new IllegalStateException("Could not load data-sources.json", error);
    }
    validate(sources);
  }

  public List<String> youtubeScraperSearchUrls() {
    return sources.youtube().scraperSearchUrls();
  }

  public String youtubeWatchPageUrl(String videoId) {
    return sources.youtube().watchPageUrlTemplate().replace("{videoId}", videoId);
  }

  public String youtubeApiSearchUrl() {
    return sources.youtube().apiSearchUrl();
  }

  public String youtubeApiVideosUrl() {
    return sources.youtube().apiVideosUrl();
  }

  public String twitchAuthUrl() {
    return sources.twitch().authUrl();
  }

  public String twitchGameUrl() {
    return sources.twitch().gameUrl();
  }

  public String twitchStreamsUrl() {
    return sources.twitch().streamsUrl();
  }

  public String kickScraperUrl() {
    return sources.kick().scraperUrl();
  }

  public String kickStreamsUrl() {
    return sources.kick().streamsUrl();
  }

  public String kickAuthUrl() {
    return sources.kick().authUrl();
  }

  private static void validate(Sources sources) {
    if (sources == null
        || sources.youtube() == null
        || sources.youtube().scraperSearchUrls() == null
        || sources.youtube().scraperSearchUrls().isEmpty()
        || sources.youtube().watchPageUrlTemplate() == null
        || sources.youtube().apiSearchUrl() == null
        || sources.youtube().apiVideosUrl() == null
        || sources.twitch() == null
        || sources.twitch().authUrl() == null
        || sources.twitch().gameUrl() == null
        || sources.twitch().streamsUrl() == null
        || sources.kick() == null
        || sources.kick().scraperUrl() == null
        || sources.kick().authUrl() == null
        || sources.kick().streamsUrl() == null) {
      throw new IllegalStateException("data-sources.json is missing required source URLs");
    }
    if (sources.youtube().scraperSearchUrls().stream().anyMatch(String::isBlank)) {
      throw new IllegalStateException("data-sources.json contains a blank YouTube source URL");
    }
  }

  private record Sources(YouTube youtube, Twitch twitch, Kick kick) {}

  private record YouTube(
      List<String> scraperSearchUrls,
      String watchPageUrlTemplate,
      String apiSearchUrl,
      String apiVideosUrl) {}

  private record Twitch(String authUrl, String gameUrl, String streamsUrl) {}

  private record Kick(String scraperUrl, String authUrl, String streamsUrl) {}
}
