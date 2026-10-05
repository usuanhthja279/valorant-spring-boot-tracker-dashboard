package com.valorant.tracker.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valorant.tracker.model.LiveStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.valorant.tracker.service.tracker.DataSourceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class KickService {

  private static final Logger logger = LoggerFactory.getLogger(KickService.class);

  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 5;
  private static final int TOP_DISPLAY_COUNT = 20;

  private final WebClient client;
  private final WebClient authClient;
  private final ObjectMapper mapper;
  private final String authUrl;
  private final String streamsUrl;

  @Value("${tracker.kick.client-id:}")
  private String clientId;

  @Value("${tracker.kick.client-secret:}")
  private String clientSecret;

  private String token;
  private Instant tokenExpiresAt = Instant.EPOCH;

  public KickService(WebClient.Builder builder, DataSourceCatalog sources) {

    this.client = builder.clone().build();

    this.authClient = builder.clone().build();

    this.authUrl = sources.kickAuthUrl();
    this.streamsUrl = sources.kickStreamsUrl();
    this.mapper = new ObjectMapper();
  }

  /*
   * ================================================================
   * KICK OAUTH
   * ================================================================
   */

  private synchronized String accessToken() throws Exception {

    if (token != null
        && !token.isBlank()
        && tokenExpiresAt.isAfter(Instant.now().plusSeconds(60))) {

      return token;
    }

    if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {

      throw new IllegalStateException("Kick Client ID or Client Secret is not configured");
    }

    logger.info("Authenticating with Kick...");

    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();

    form.add("client_id", clientId);

    form.add("client_secret", clientSecret);

    form.add("grant_type", "client_credentials");

    String response =
        authClient
            .post()
            .uri(authUrl)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData(form))
            .retrieve()
            .bodyToMono(String.class)
            .block();

    if (response == null || response.isBlank()) {

      throw new RuntimeException("Kick OAuth response was empty");
    }

    JsonNode tokenResponse = mapper.readTree(response);

    if (tokenResponse.has("error")) {

      logger.error("Kick OAuth error:\n{}", tokenResponse.toPrettyString());

      throw new RuntimeException("Kick OAuth authentication failed");
    }

    token = tokenResponse.path("access_token").asText("");

    if (token.isBlank()) {

      throw new IllegalStateException("Kick OAuth response did not contain an access token");
    }

    long expiresIn = tokenResponse.path("expires_in").asLong(3600);

    tokenExpiresAt = Instant.now().plusSeconds(expiresIn);

    logger.info("Kick authentication successful");

    return token;
  }

  /*
   * ================================================================
   * FETCH KICK LIVE STREAMS
   * ================================================================
   */

  public List<LiveStream> fetch() {

    if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {

      logger.warn(
          "Kick collection disabled because "
              + "Kick Client ID / Client Secret is not configured.");

      return List.of();
    }

    try {

      String accessToken = accessToken();

      List<LiveStream> streams = new ArrayList<>();

      String cursor = null;

      /*
       * ========================================================
       * FETCH UP TO 5 PAGES
       *
       * 5 × 100 = maximum 500 livestream records
       * ========================================================
       */

      for (int page = 1; page <= MAX_PAGES; page++) {

        final String currentCursor = cursor;

        logger.info(
            "Kick livestreams: requesting page {}/{}{}",
            page,
            MAX_PAGES,
            currentCursor == null ? "" : " with cursor");

        String response =
            client
                .get()
                .uri(
                    uriBuilder -> {
                      var query =
                          org.springframework.web.util.UriComponentsBuilder
                              .fromUriString(streamsUrl)
                              .queryParam("limit", PAGE_SIZE);

                      /*
                       * Kick v2 pagination cursor.
                       */

                      if (currentCursor != null && !currentCursor.isBlank()) {

                        query.queryParam("cursor", currentCursor);
                      }

                      return query.build().toUri();
                    })
                .header("Accept", "application/json")
                .headers(headers -> headers.setBearerAuth(accessToken))
                .retrieve()
                .bodyToMono(String.class)
                .block();

        if (response == null || response.isBlank()) {

          throw new RuntimeException("Kick livestream API returned an empty response");
        }

        logger.info(
            "Kick raw livestream API response page {}: {}",
            page,
            response.substring(0, Math.min(2000, response.length())));

        JsonNode root = mapper.readTree(response);

        /*
         * ====================================================
         * DEBUG / API ERROR
         * ====================================================
         */

        if (root.has("error")) {

          logger.error("Kick API error:\n{}", root.toPrettyString());

          throw new RuntimeException("Kick livestream API returned an error");
        }

        /*
         * ====================================================
         * DATA
         * ====================================================
         */

        JsonNode data = root.path("data");

        if (!data.isArray()) {

          logger.warn(
              "Kick API returned no data array. " + "Raw response:\n{}", root.toPrettyString());

          break;
        }

        int pageStreams = 0;
        int valorantStreams = 0;

        for (JsonNode stream : data) {

          pageStreams++;

          /*
           * ------------------------------------------------
           * CATEGORY
           * ------------------------------------------------
           */

          JsonNode category = stream.path("category");

          String categoryName = category.path("name").asText("");

          String categorySlug = category.path("slug").asText("");

          /*
           * Kick can represent the category in either
           * name or slug, so check both.
           */

          boolean isValorant =
              categoryName.equalsIgnoreCase("VALORANT")
                  || categorySlug.equalsIgnoreCase("valorant");

          if (!isValorant) {
            continue;
          }

          valorantStreams++;

          /*
           * ------------------------------------------------
           * CHANNEL / BROADCASTER
           * ------------------------------------------------
           */

          JsonNode channel = stream.path("channel");

          JsonNode broadcaster = stream.path("broadcaster_user");

          String channelName = channel.path("slug").asText("");

          if (channelName.isBlank()) {

            channelName = broadcaster.path("username").asText("");
          }

          /*
           * ------------------------------------------------
           * VIEWERS
           * ------------------------------------------------
           */

          JsonNode viewerNode = stream.path("viewer_count");

          if (viewerNode.isMissingNode() || viewerNode.isNull()) {

            continue;
          }

          long viewers = viewerNode.asLong(0);

          /*
           * ------------------------------------------------
           * STREAM ID
           * ------------------------------------------------
           */

          String streamId = stream.path("id").asText("");

          if (streamId.isBlank()) {

            streamId = channelName;
          }

          /*
           * ------------------------------------------------
           * URL
           * ------------------------------------------------
           */

          String url = "https://kick.com/" + channelName;

          /*
           * ------------------------------------------------
           * ADD STREAM
           * ------------------------------------------------
           */

          streams.add(
              new LiveStream(
                  "Kick",
                  streamId,
                  broadcaster.path("id").asText(""),
                  channelName,
                  stream.path("title").asText(""),
                  viewers,
                  url));
        }

        logger.info(
            "Kick page {}/{}: {} streams returned, "
                + "{} VALORANT streams. Total VALORANT collected: {}",
            page,
            MAX_PAGES,
            pageStreams,
            valorantStreams,
            streams.size());

        /*
         * ====================================================
         * PAGINATION
         * ====================================================
         *
         * Try both common locations for the pagination
         * cursor so that the service is tolerant of the
         * response structure.
         * ====================================================
         */

        cursor = root.path("pagination").path("cursor").asText(null);

        if (cursor == null || cursor.isBlank()) {

          cursor = root.path("meta").path("pagination").path("cursor").asText(null);
        }

        /*
         * No cursor = no more pages.
         */

        if (cursor == null || cursor.isBlank()) {

          logger.info("Kick pagination finished after page {}", page);

          break;
        }
      }

      /*
       * ========================================================
       * SORT BY CURRENT VIEWERS
       * ========================================================
       */

      streams.sort(Comparator.comparingLong(LiveStream::viewers).reversed());

      /*
       * ========================================================
       * TOTAL VIEWERS
       *
       * Total across all VALORANT streams collected
       * from the maximum 500 records.
       * ========================================================
       */

      long totalViewers = streams.stream().mapToLong(LiveStream::viewers).sum();

      /*
       * ========================================================
       * SUMMARY
       * ========================================================
       */

      logger.info("================================================");

      logger.info("Kick collection complete");

      logger.info("VALORANT streams collected: {}", streams.size());

      logger.info("Total VALORANT viewers: {}", totalViewers);

      logger.info("================================================");

      /*
       * ========================================================
       * TOP 20
       * ========================================================
       */

      logger.info("Kick Top 20:");

      for (int i = 0; i < Math.min(TOP_DISPLAY_COUNT, streams.size()); i++) {

        LiveStream stream = streams.get(i);

        logger.info(
            "{}. {} -> {} viewers | {}",
            i + 1,
            stream.channelTitle(),
            stream.viewers(),
            stream.title());
      }

      /*
       * Return all collected VALORANT streams.
       *
       * They are sorted descending by current viewers.
       */

      return streams;

    } catch (Exception e) {

      logger.error("Kick service failed", e);

      throw new IllegalStateException("Failed to fetch Kick live streams", e);
    }
  }
}
