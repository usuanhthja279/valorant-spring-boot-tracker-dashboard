package com.valorant.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valorant.tracker.model.LiveStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class YouTubeService {

  private static final Logger logger = LoggerFactory.getLogger(YouTubeService.class);

  private static final int SEARCH_PAGE_SIZE = 50;
  private static final int VIDEO_BATCH_SIZE = 50;

  private final WebClient client;
  private final ObjectMapper mapper;

  @Value("${tracker.youtube.refresh-interval-ms:3600000}")
  private long refreshIntervalMs;

  private List<LiveStream> cachedStreams = List.of();
  private long lastFetchAttemptMs;
  private boolean missingApiKeyWarningLogged;

  @Value("${tracker.youtube.api-key:}")
  private String apiKey;

  public YouTubeService(WebClient.Builder webClientBuilder) {

    this.client = webClientBuilder.baseUrl("https://www.googleapis.com/youtube/v3").build();

    this.mapper = new ObjectMapper();
  }

  public synchronized List<LiveStream> fetch() {

    if (apiKey == null || apiKey.isBlank()) {

      if (!missingApiKeyWarningLogged) {

        logger.warn(
            "YouTube collection is disabled because " + "YOUTUBE_API_KEY is not configured.");

        missingApiKeyWarningLogged = true;
      }

      return List.of();
    }

    long now = System.currentTimeMillis();
    if (lastFetchAttemptMs != 0 && now - lastFetchAttemptMs < refreshIntervalMs) {
      return cachedStreams;
    }

    lastFetchAttemptMs = now;
    return fetchFromApi();
  }

  private List<LiveStream> fetchFromApi() {
    try {

      /*
       * ============================================================
       * STEP 1
       *
       * Search ALL pages of live VALORANT videos.
       *
       * YouTube search.list allows max 50 results per request.
       * We therefore keep requesting nextPageToken until there
       * are no more pages.
       * ============================================================
       */

      Set<String> uniqueVideoIds = new LinkedHashSet<>();

      String pageToken = null;

      int pageNumber = 0;

      int totalResults = 0;

      do {

        pageNumber++;

        final String currentPageToken = pageToken;

        logger.info(
            "YouTube search: requesting page {}{}",
            pageNumber,
            currentPageToken == null ? "" : " with pageToken");

        String searchResponse =
            client
                .get()
                .uri(
                    uriBuilder -> {
                      var query =
                          uriBuilder
                              .path("/search")
                              .queryParam("part", "snippet")
                              .queryParam("q", "VALORANT")
                              .queryParam("type", "video")
                              .queryParam("eventType", "live")
                              .queryParam("order", "viewCount")
                              .queryParam("maxResults", SEARCH_PAGE_SIZE)
                              .queryParam("regionCode", "IN")
                              .queryParam("key", apiKey);

                      if (currentPageToken != null && !currentPageToken.isBlank()) {

                        query.queryParam("pageToken", currentPageToken);
                      }

                      return query.build();
                    })
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (searchResponse == null || searchResponse.isBlank()) {

          throw new RuntimeException("YouTube search returned an empty response");
        }

        JsonNode searchResult = mapper.readTree(searchResponse);

        /*
         * ========================================================
         * Check for API error
         * ========================================================
         */

        if (searchResult.has("error")) {

          logger.error(
              "YouTube search API error:\n{}", searchResult.path("error").toPrettyString());

          throw new RuntimeException("YouTube search API returned an error");
        }

        /*
         * ========================================================
         * Read total result count
         * ========================================================
         */

        if (pageNumber == 1) {

          totalResults = searchResult.path("pageInfo").path("totalResults").asInt(0);

          logger.info("YouTube reports {} live search results", totalResults);
        }

        /*
         * ========================================================
         * Extract video IDs from this page
         * ========================================================
         */

        int pageItems = 0;

        for (JsonNode item : searchResult.path("items")) {

          String videoId = item.path("id").path("videoId").asText(null);

          if (videoId != null && !videoId.isBlank()) {

            uniqueVideoIds.add(videoId);

            pageItems++;
          }
        }

        logger.info(
            "YouTube page {} returned {} video IDs. " + "Unique IDs collected so far: {}",
            pageNumber,
            pageItems,
            uniqueVideoIds.size());

        /*
         * ========================================================
         * Get next page
         * ========================================================
         */

        pageToken = searchResult.path("nextPageToken").asText(null);

      } while (pageToken != null && !pageToken.isBlank());

      /*
       * ============================================================
       * STEP 2
       *
       * Convert Set -> List
       * ============================================================
       */

      List<String> videoIds = new ArrayList<>(uniqueVideoIds);

      logger.info(
          "YouTube pagination complete. "
              + "API reported totalResults={}, "
              + "unique video IDs collected={}",
          totalResults,
          videoIds.size());

      if (videoIds.isEmpty()) {

        logger.info("No live VALORANT videos found.");

        cachedStreams = List.of();
        return List.of();
      }

      /*
       * ============================================================
       * STEP 3
       *
       * Request /videos in batches of 50.
       *
       * Example:
       *
       * 607 search results
       *
       * Search pages:
       *   50
       *   50
       *   50
       *   ...
       *
       * /videos:
       *   50 IDs
       *   50 IDs
       *   50 IDs
       *   ...
       * ============================================================
       */

      List<LiveStream> streams = new ArrayList<>();

      int totalVideoBatches = (int) Math.ceil(videoIds.size() / (double) VIDEO_BATCH_SIZE);

      for (int start = 0; start < videoIds.size(); start += VIDEO_BATCH_SIZE) {

        int end = Math.min(start + VIDEO_BATCH_SIZE, videoIds.size());

        int batchNumber = (start / VIDEO_BATCH_SIZE) + 1;

        List<String> batch = videoIds.subList(start, end);

        String batchIds = String.join(",", batch);

        logger.info(
            "YouTube /videos batch {}/{}: {} IDs", batchNumber, totalVideoBatches, batch.size());

        String videosResponse =
            client
                .get()
                .uri(
                    uriBuilder ->
                        uriBuilder
                            .path("/videos")
                            .queryParam("part", "snippet," + "liveStreamingDetails," + "status")
                            .queryParam("id", batchIds)
                            .queryParam("key", apiKey)
                            .build())
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (videosResponse == null || videosResponse.isBlank()) {

          logger.warn("YouTube /videos returned empty " + "response for batch {}", batchNumber);

          continue;
        }

        JsonNode videosResult = mapper.readTree(videosResponse);

        /*
         * ========================================================
         * Check for API error
         * ========================================================
         */

        if (videosResult.has("error")) {

          logger.error(
              "YouTube /videos API error:\n{}", videosResult.path("error").toPrettyString());

          continue;
        }

        /*
         * ========================================================
         * STEP 4
         *
         * Extract current concurrent viewers.
         * ========================================================
         */

        for (JsonNode video : videosResult.path("items")) {

          JsonNode liveDetails = video.path("liveStreamingDetails");

          String concurrentViewers = liveDetails.path("concurrentViewers").asText(null);

          /*
           * No concurrent viewer count means we cannot use
           * this stream for the current-viewer calculation.
           */

          if (concurrentViewers == null || concurrentViewers.isBlank()) {

            continue;
          }

          long viewers;

          try {

            viewers = Long.parseLong(concurrentViewers);

          } catch (NumberFormatException e) {

            logger.warn(
                "Invalid YouTube viewer count '{}' " + "for video {}",
                concurrentViewers,
                video.path("id").asText());

            continue;
          }

          /*
           * ====================================================
           * STEP 5
           *
           * Extract stream information.
           * ====================================================
           */

          String videoId = video.path("id").asText("");

          JsonNode snippet = video.path("snippet");

          String channelId = snippet.path("channelId").asText("");

          String channelTitle = snippet.path("channelTitle").asText("");

          String title = snippet.path("title").asText("");

          String url = "https://www.youtube.com/watch?v=" + videoId;

          streams.add(
              new LiveStream("YouTube", videoId, channelId, channelTitle, title, viewers, url));
        }
      }

      /*
       * ============================================================
       * STEP 6
       *
       * Sort EVERY collected stream by CURRENT concurrent viewers.
       *
       * This is the important part:
       *
       * order=viewCount
       *
       * was only used to order the SEARCH results.
       *
       * The actual Top 10 is determined here using
       * liveStreamingDetails.concurrentViewers.
       * ============================================================
       */

      streams.sort(Comparator.comparingLong(LiveStream::viewers).reversed());

      /*
       * ============================================================
       * STEP 7
       * Statistics
       * ============================================================
       */

      long totalViewers = streams.stream().mapToLong(LiveStream::viewers).sum();

      logger.info("================================================");

      logger.info("YouTube collection complete");

      logger.info("Search totalResults: {}", totalResults);

      logger.info("Unique video IDs collected: {}", videoIds.size());

      logger.info("Streams with concurrent viewer count: {}", streams.size());

      logger.info("Total concurrent viewers: {}", totalViewers);

      logger.info("================================================");

      /*
       * ============================================================
       * STEP 8
       * Print Top 20
       * ============================================================
       */

      logger.info("YouTube Top 20:");

      streams.stream()
          .limit(20)
          .forEach(
              stream ->
                  logger.info(
                      "{} -> {} viewers | {}",
                      stream.channelTitle(),
                      stream.viewers(),
                      stream.title()));

      cachedStreams = List.copyOf(streams);
      return cachedStreams;

    } catch (Exception e) {

      logger.warn(
          "YouTube refresh failed; retrying after {} ms and reusing {} cached streams",
          refreshIntervalMs,
          cachedStreams.size(),
          e);
      return cachedStreams;
    }
  }
}
