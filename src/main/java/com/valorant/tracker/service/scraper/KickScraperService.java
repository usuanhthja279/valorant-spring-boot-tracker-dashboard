package com.valorant.tracker.service.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valorant.tracker.model.LiveStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.valorant.tracker.service.tracker.DataSourceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class KickScraperService {

  private static final Logger logger = LoggerFactory.getLogger(KickScraperService.class);
  private static final String STREAMS_MARKER = "\\\"livestreams\\\":[";
  private static final int MAX_PAGE_SIZE_BYTES = 2 * 1024 * 1024;
  private static final int TOP_DISPLAY_COUNT = 20;

  private final WebClient client;
  private final ObjectMapper mapper;

  public KickScraperService(WebClient.Builder webClientBuilder, ObjectMapper mapper, DataSourceCatalog sources) {
    this.client = webClientBuilder
            .clone()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_PAGE_SIZE_BYTES))
            .build();
    this.mapper = mapper;
  }

  public List<LiveStream> fetch(String game, String pageUrl, boolean validateWithApi) {

    String html = client.get()
            .uri(pageUrl)
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "text/html,application/xhtml+xml")
            .retrieve()
            .bodyToMono(String.class)
            .block();

    if (html == null || html.isBlank()) {
      throw new IllegalStateException("Kick category page returned an empty response");
    }

    try {
      String encodedStreams = extractStreamsArray(html);
      String streamsJson = mapper.readTree("\"" + encodedStreams + "\"").asText();
      JsonNode streams = mapper.readTree(streamsJson);
      List<LiveStream> results = new ArrayList<>();

      for (JsonNode stream : streams) {
        JsonNode channel = stream.path("channel");
        String slug = channel.path("slug").asText("");
        String username = channel.path("username").asText(slug);
        String channelTitle = username.isBlank() ? slug : username;
        String streamId = stream.path("id").asText("");
        if (streamId.isBlank() || channelTitle.isBlank()) {
          continue;
        }

        results.add(new LiveStream(
                "Kick",
                streamId,
                channel.path("id").asText(""),
                channelTitle,
                stream.path("title").asText(""),
                stream.path("viewer_count").asLong(0),
                "https://kick.com/" + (slug.isBlank() ? channelTitle : slug)));
      }

      results.sort(Comparator.comparingLong(LiveStream::viewers).reversed());
      logger.info("Kick category scraper returned {} streams for game {}", results.size(), game);
      logger.info("Total viewers across collected streams: {} for game {}", results.stream().mapToLong(LiveStream::viewers).sum(), game);

//      logger.info("Kick Top 20 streams for game {}:", game);
//      for (int index = 0; index < Math.min(TOP_DISPLAY_COUNT, results.size()); index++) {
//        LiveStream stream = results.get(index);
//        logger.info("{}. {} -> {} viewers | {}",
//            index + 1,
//            stream.channelTitle(),
//            stream.viewers(),
//            stream.title());
//      }
      return List.copyOf(results);
    } catch (Exception e) {
      logger.error("Failed to parse Kick category page for game {}: ", game, e);
      throw new IllegalStateException("Failed to parse Kick category page", e);
    }
  }

  private String extractStreamsArray(String html) {
    int markerIndex = html.indexOf(STREAMS_MARKER);
    if (markerIndex < 0) {
      throw new IllegalStateException("Kick category page did not contain livestream data");
    }

    int start = markerIndex + STREAMS_MARKER.length() - 1;
    boolean inString = false;
    int depth = 0;

    for (int index = start; index < html.length(); index++) {
      char current = html.charAt(index);

      if (current == '"') {
        int precedingBackslashes = 0;
        for (int previous = index - 1;
            previous >= start && html.charAt(previous) == '\\';
            previous--) {
          precedingBackslashes++;
        }

        // Kick embeds JSON inside a serialized React Flight string.
        if (precedingBackslashes == 1) {
          inString = !inString;
        } else if (precedingBackslashes == 0 && !inString) {
          inString = true;
        }
        continue;
      }

      if (inString) {
        continue;
      } else if (current == '[') {
        depth++;
      } else if (current == ']') {
        depth--;
        if (depth == 0) {
          return html.substring(start, index + 1);
        }
      }
    }

    throw new IllegalStateException("Kick category livestream data was incomplete");
  }
}
