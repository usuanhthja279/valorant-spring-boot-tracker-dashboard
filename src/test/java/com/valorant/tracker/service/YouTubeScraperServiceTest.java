package com.valorant.tracker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.valorant.tracker.service.scraper.YouTubeScraperService;
import com.valorant.tracker.service.tracker.DataSourceCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class YouTubeScraperServiceTest {
  @Test
  void treatsWatchUrlAsOneSpecificVideo() {
    String videoPage =
        """
        <html>ytInitialPlayerResponse = {
          "videoDetails":{
            "videoId":"fixed-live-id",
            "title":"VALORANT Grand Final",
            "author":"VALORANT Esports",
            "channelId":"UC-valorant",
            "viewCount":"4567",
            "isLiveContent":true
          },
          "microformat":{"playerMicroformatRenderer":{"liveBroadcastDetails":{"isLiveNow":true}}}
        };</html>
        """;
    List<String> requestedUrls = new CopyOnWriteArrayList<>();
    WebClient.Builder client =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  String url = request.url().toString();
                  requestedUrls.add(url);
                  String body =
                      url.contains("watch?v=fixed-live-id")
                          ? videoPage
                          : "<html>{\"ytInitialData\":{\"contents\":{}}}</html>";
                  return Mono.just(ClientResponse.create(HttpStatus.OK).body(body).build());
                });
    ObjectMapper mapper = new ObjectMapper();
    DataSourceCatalog sources = mock(DataSourceCatalog.class);
    when(sources.youtubeScraperSearchUrls())
        .thenReturn(List.of("https://www.youtube.com/watch?v=fixed-live-id"));
    when(sources.youtubeWatchPageUrl("fixed-live-id"))
        .thenReturn("https://www.youtube.com/watch?v=fixed-live-id");
    YouTubeScraperService scraper =
        new YouTubeScraperService(client, mapper, sources);

    var streams = scraper.fetch();

    assertEquals(1, streams.size());
    assertEquals("fixed-live-id", streams.get(0).id());
    assertEquals("VALORANT Esports", streams.get(0).channelTitle());
    assertEquals(4567L, streams.get(0).viewers());
    assertTrue(
        requestedUrls.stream().anyMatch(url -> url.contains("watch?v=fixed-live-id")));
  }

  @Test
  void includesLiveStreamsFromValorantEsportsChannel() {
    String channelPage =
        """
        <html>{"ytInitialData":{"contents":{"videoRenderer":{
          "videoId":"valorant-live",
          "title":{"simpleText":"VALORANT Champions"},
          "ownerText":{"runs":[{"text":"VALORANT Esports","navigationEndpoint":{"browseEndpoint":{"browseId":"UC-valorant"}}}]},
          "viewCountText":{"simpleText":"1,234 watching"}
        }}}}</html>
        """;
    List<String> requestedUrls = new CopyOnWriteArrayList<>();
    WebClient.Builder client =
        WebClient.builder()
            .exchangeFunction(
                request -> {
                  String url = request.url().toString();
                  requestedUrls.add(url);
                  String body =
                      url.contains("/@ValorantEsports/streams")
                          ? channelPage
                          : url.contains("/watch?")
                              ? "<html>watch page</html>"
                              : "<html>{\"ytInitialData\":{\"contents\":{}}}</html>";
                  return Mono.just(ClientResponse.create(HttpStatus.OK).body(body).build());
                });

    ObjectMapper mapper = new ObjectMapper();
    DataSourceCatalog sources = mock(DataSourceCatalog.class);
    when(sources.youtubeScraperSearchUrls())
        .thenReturn(List.of("https://www.youtube.com/@ValorantEsports/streams"));
    when(sources.youtubeWatchPageUrl("valorant-live"))
        .thenReturn("https://www.youtube.com/watch?v=valorant-live");
    YouTubeScraperService scraper =
        new YouTubeScraperService(client, mapper, sources);

    var streams = scraper.fetch();

    assertEquals(1, streams.size());
    assertEquals("valorant-live", streams.get(0).id());
    assertEquals("VALORANT Esports", streams.get(0).channelTitle());
    assertEquals(1234L, streams.get(0).viewers());
    assertTrue(requestedUrls.stream().anyMatch(url -> url.contains("/@ValorantEsports/streams")));
  }
}
