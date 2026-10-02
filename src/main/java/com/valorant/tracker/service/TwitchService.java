package com.valorant.tracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valorant.tracker.model.LiveStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
public class TwitchService {

 private static final Logger logger = LoggerFactory.getLogger(TwitchService.class);

 private static final int PAGE_SIZE = 100;
 private static final int MAX_PAGES = 5;
 private static final int TOP_DISPLAY_COUNT = 20;

 /*
  * Twitch allows a maximum of 100 streams per
  * Get Streams request.
  */
 private static final int TOP_STREAM_COUNT = 100;

 private final WebClient client;
 private final ObjectMapper mapper;

 @Value("${tracker.twitch.client-id:}")
 private String clientId;

 @Value("${tracker.twitch.client-secret:}")
 private String clientSecret;

 private String accessToken;
 private String gameId;

 public TwitchService(WebClient.Builder webClientBuilder) {
  this.client = webClientBuilder.build();
  this.mapper = new ObjectMapper();
 }

 /*
  * ================================================================
  * AUTHENTICATION
  * ================================================================
  */

 private synchronized void authenticate() throws Exception {

  if (accessToken != null &&
          !accessToken.isBlank() &&
          gameId != null &&
          !gameId.isBlank()) {

   return;
  }

  if (clientId == null ||
          clientId.isBlank() ||
          clientSecret == null ||
          clientSecret.isBlank()) {

   throw new IllegalStateException(
           "Twitch Client ID or Client Secret is not configured"
   );
  }

  logger.info("Authenticating with Twitch...");

  /*
   * ------------------------------------------------------------
   * Get application access token
   * ------------------------------------------------------------
   */

  String tokenResponse =
          client
                  .post()
                  .uri(uriBuilder ->
                          uriBuilder
                                  .scheme("https")
                                  .host("id.twitch.tv")
                                  .path("/oauth2/token")
                                  .queryParam(
                                          "client_id",
                                          clientId
                                  )
                                  .queryParam(
                                          "client_secret",
                                          clientSecret
                                  )
                                  .queryParam(
                                          "grant_type",
                                          "client_credentials"
                                  )
                                  .build()
                  )
                  .retrieve()
                  .bodyToMono(String.class)
                  .block();

  if (tokenResponse == null ||
          tokenResponse.isBlank()) {

   throw new RuntimeException(
           "Twitch token response was empty"
   );
  }

  JsonNode tokenJson =
          mapper.readTree(tokenResponse);

  if (tokenJson.has("error")) {

   logger.error(
           "Twitch authentication error:\n{}",
           tokenJson.toPrettyString()
   );

   throw new RuntimeException(
           "Twitch authentication failed"
   );
  }

  accessToken =
          tokenJson
                  .path("access_token")
                  .asText(null);

  if (accessToken == null ||
          accessToken.isBlank()) {

   throw new RuntimeException(
           "Twitch access token was not returned"
   );
  }

  logger.info(
          "Twitch authentication successful"
  );

  /*
   * ------------------------------------------------------------
   * Get VALORANT game ID
   * ------------------------------------------------------------
   */

  String gameResponse =
          client
                  .get()
                  .uri(uriBuilder ->
                          uriBuilder
                                  .scheme("https")
                                  .host("api.twitch.tv")
                                  .path("/helix/games")
                                  .queryParam(
                                          "name",
                                          "VALORANT"
                                  )
                                  .build()
                  )
                  .header(
                          "Client-ID",
                          clientId
                  )
                  .header(
                          "Authorization",
                          "Bearer " + accessToken
                  )
                  .retrieve()
                  .bodyToMono(String.class)
                  .block();

  if (gameResponse == null ||
          gameResponse.isBlank()) {

   throw new RuntimeException(
           "Twitch game response was empty"
   );
  }

  JsonNode gameJson =
          mapper.readTree(gameResponse);

  if (gameJson.has("error")) {

   logger.error(
           "Twitch game API error:\n{}",
           gameJson.toPrettyString()
   );

   throw new RuntimeException(
           "Failed to retrieve Twitch VALORANT game ID"
   );
  }

  gameId =
          gameJson
                  .path("data")
                  .path(0)
                  .path("id")
                  .asText(null);

  if (gameId == null ||
          gameId.isBlank()) {

   throw new RuntimeException(
           "Twitch VALORANT game ID was not found"
   );
  }

  logger.info(
          "Twitch VALORANT game ID = {}",
          gameId
  );
 }

 /*
  * ================================================================
  * FETCH TOP 100 LIVE VALORANT STREAMS
  * ================================================================
  */

 public List<LiveStream> fetch() {

  if (clientId == null ||
          clientId.isBlank() ||
          clientSecret == null ||
          clientSecret.isBlank()) {

   throw new IllegalStateException(
           "Twitch collection is disabled because Client ID / Client Secret is not configured"
   );
  }

  try {

   authenticate();

   List<LiveStream> streams =
           new ArrayList<>();

   String cursor = null;

   /*
    * ============================================================
    * FETCH 5 PAGES
    *
    * 5 pages × 100 streams = up to 500 streams
    * ============================================================
    */

   for (int page = 1; page <= MAX_PAGES; page++) {

    final String currentCursor = cursor;

    logger.info(
            "Twitch streams: requesting page {}/{}{}",
            page,
            MAX_PAGES,
            currentCursor == null
                    ? ""
                    : " with cursor"
    );

    String response =
            client
                    .get()
                    .uri(uriBuilder -> {

                     var query =
                             uriBuilder
                                     .scheme("https")
                                     .host("api.twitch.tv")
                                     .path("/helix/streams")
                                     .queryParam(
                                             "game_id",
                                             gameId
                                     )
                                     .queryParam(
                                             "first",
                                             PAGE_SIZE
                                     );

                     if (currentCursor != null &&
                             !currentCursor.isBlank()) {

                      query.queryParam(
                              "after",
                              currentCursor
                      );
                     }

                     return query.build();
                    })
                    .header(
                            "Client-ID",
                            clientId
                    )
                    .header(
                            "Authorization",
                            "Bearer " + accessToken
                    )
                    .retrieve()
                    .bodyToMono(String.class)
                    .block();

    if (response == null ||
            response.isBlank()) {

     throw new RuntimeException(
             "Twitch streams API returned an empty response"
     );
    }

    JsonNode result =
            mapper.readTree(response);

    /*
     * --------------------------------------------------------
     * API ERROR
     * --------------------------------------------------------
     */

    if (result.has("error")) {

     logger.error(
             "Twitch streams API error:\n{}",
             result.toPrettyString()
     );

     throw new RuntimeException(
             "Twitch streams API returned an error"
     );
    }

    /*
     * --------------------------------------------------------
     * PROCESS STREAMS
     * --------------------------------------------------------
     */

    int pageStreams = 0;

    for (JsonNode stream :
            result.path("data")) {

     String streamId =
             stream
                     .path("id")
                     .asText("");

     String userId =
             stream
                     .path("user_id")
                     .asText("");

     String userName =
             stream
                     .path("user_name")
                     .asText("");

     String userLogin =
             stream
                     .path("user_login")
                     .asText("");

     String title =
             stream
                     .path("title")
                     .asText("");

     long viewers =
             stream
                     .path("viewer_count")
                     .asLong(0);

     if (streamId.isBlank() ||
             userId.isBlank()) {

      continue;
     }

     streams.add(
             new LiveStream(
                     "Twitch",
                     streamId,
                     userId,
                     userName,
                     title,
                     viewers,
                     "https://www.twitch.tv/"
                             + userLogin
             )
     );

     pageStreams++;
    }

    logger.info(
            "Twitch page {}/{} returned {} streams. " +
                    "Total collected: {}",
            page,
            MAX_PAGES,
            pageStreams,
            streams.size()
    );

    /*
     * --------------------------------------------------------
     * NEXT PAGE
     * --------------------------------------------------------
     */

    cursor =
            result
                    .path("pagination")
                    .path("cursor")
                    .asText(null);

    /*
     * If Twitch has no next page, stop early.
     */

    if (cursor == null ||
            cursor.isBlank()) {

     logger.info(
             "Twitch has no more pages. " +
                     "Stopped after page {}.",
             page
     );

     break;
    }
   }

   /*
    * ============================================================
    * SORT ALL COLLECTED STREAMS
    *
    * viewer_count = current concurrent viewers
    * ============================================================
    */

   streams.sort(
           Comparator.comparingLong(
                   LiveStream::viewers
           ).reversed()
   );

   /*
    * ============================================================
    * TOTAL VIEWERS
    *
    * This is the total across the streams we collected
    * (maximum 500).
    * ============================================================
    */

   long totalViewers =
           streams.stream()
                   .mapToLong(
                           LiveStream::viewers
                   )
                   .sum();

   /*
    * ============================================================
    * LOG SUMMARY
    * ============================================================
    */

   logger.info(
           "================================================"
   );

   logger.info(
           "Twitch collection complete"
   );

   logger.info(
           "Streams collected: {}",
           streams.size()
   );

   logger.info(
           "Total viewers across collected streams: {}",
           totalViewers
   );

   logger.info(
           "================================================"
   );

   /*
    * ============================================================
    * TOP 20
    * ============================================================
    */

   logger.info(
           "Twitch Top 20:"
   );

   streams.stream()
           .limit(TOP_DISPLAY_COUNT)
           .forEachOrdered(
                   stream ->
                           logger.info(
                                   "{}. {} -> {} viewers | {}",
                                   streams.indexOf(stream) + 1,
                                   stream.channelTitle(),
                                   stream.viewers(),
                                   stream.title()
                           )
           );

   /*
    * Return all collected streams.
    *
    * Dashboard can then calculate Top 20 itself if needed.
    */

   return streams;

  } catch (Exception e) {

   logger.error(
           "Twitch service failed",
           e
   );

   throw new IllegalStateException("Failed to fetch Twitch live streams", e);
  }
 }
}