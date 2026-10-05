package com.valorant.tracker.service.scraper;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valorant.tracker.model.LiveStream;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.valorant.tracker.service.tracker.DataSourceCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
public class YouTubeScraperService {

  private static final Logger logger = LoggerFactory.getLogger(YouTubeScraperService.class);
  private static final String INITIAL_DATA_MARKER = "ytInitialData";
  private static final String PLAYER_RESPONSE_MARKER = "ytInitialPlayerResponse";
  private static final int MAX_PAGE_SIZE_BYTES = 2 * 1024 * 1024;
  private static final Pattern VIEWER_COUNT =
      Pattern.compile("([\\d,.]+)\\s*([KMB]?)\\s+watching", Pattern.CASE_INSENSITIVE);
  private static final Pattern WATCH_PAGE_RENDERER =
      Pattern.compile("\"videoViewCountRenderer\"\\s*:\\s*\\{");
  private static final Pattern WATCH_PAGE_LIVE_STATUS =
      Pattern.compile("\"isLive\"\\s*:\\s*(true|false)");
  private static final Pattern WATCH_PAGE_VIEWER_COUNT =
      Pattern.compile("\"originalViewCount\"\\s*:\\s*\"?(\\d+)\"?");
  private static final int WATCH_PAGE_CONCURRENCY = 2;
  private static final Duration WATCH_PAGE_REFRESH_INTERVAL = Duration.ofMinutes(5);
  private static final Duration FETCH_CACHE_TTL = Duration.ofSeconds(30);
  private static final Duration RATE_LIMIT_BACKOFF = Duration.ofMinutes(1);

  private final WebClient client;
  private final ObjectMapper mapper;
  private final DataSourceCatalog sources;
  private final List<String> searchUrls;
  private final Map<String, Instant> lastWatchPageAttempt = new ConcurrentHashMap<>();
  private final Map<String, CachedViewerCount> cachedWatchPageStatus = new ConcurrentHashMap<>();
  private final AtomicLong watchPageBlockedUntilMillis = new AtomicLong();
  private volatile Instant lastFetchTime;
  private volatile List<LiveStream> cachedFetchResults = List.of();

  public YouTubeScraperService(
      WebClient.Builder webClientBuilder, ObjectMapper mapper, DataSourceCatalog sources) {
    this.client =
        webClientBuilder
            .clone()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(MAX_PAGE_SIZE_BYTES))
            .build();
    this.mapper = mapper;
    this.sources = sources;
    this.searchUrls = List.copyOf(sources.youtubeScraperSearchUrls());
  }

  public synchronized List<LiveStream> fetch() {
    Instant now = Instant.now();
    if (lastFetchTime != null && now.isBefore(lastFetchTime.plus(FETCH_CACHE_TTL))) {
      logger.debug("Returning cached YouTube scrape results");
      return cachedFetchResults;
    }

    try {
      Map<String, JsonNode> videoRenderers = new LinkedHashMap<>();
      List<LiveStream> individualVideos = new ArrayList<>();
      RuntimeException lastSearchError = null;
      int successfulSearches = 0;
      for (String searchUrl : searchUrls) {
        try {
          logger.info("YouTube streams: requesting live results from {}", searchUrl);
          String html =
              client
                  .get()
                  .uri(URI.create(searchUrl))
                  .header("User-Agent", "Mozilla/5.0")
                  .header("Accept-Language", "en-US,en;q=0.9")
                  .retrieve()
                  .bodyToMono(String.class)
                  .block();
          if (html == null || html.isBlank()) {
            throw new IllegalStateException("YouTube search page returned an empty response");
          }

          String videoId = videoIdFromWatchUrl(searchUrl);
          if (!videoId.isBlank()) {
            LiveStream stream = parseIndividualVideo(html, videoId, searchUrl);
            if (stream != null) {
              individualVideos.add(stream);
            }
            successfulSearches++;
            logger.info(
                "YouTube individual video {} {}",
                videoId,
                stream == null ? "is not currently live" : "is live");
            continue;
          }

          JsonNode initialData = mapper.readTree(extractInitialData(html));
          List<JsonNode> pageRenderers = new ArrayList<>();
          collectVideoRenderers(initialData, pageRenderers);
          pageRenderers.stream()
              .filter(video -> !video.path("videoId").asText("").isBlank())
              .forEach(video -> videoRenderers.putIfAbsent(video.path("videoId").asText(), video));
          successfulSearches++;
          logger.info(
              "YouTube search page returned {} characters and {} unique video results",
              html.length(),
              videoRenderers.size());
        } catch (RuntimeException error) {
          lastSearchError = error;
          logger.warn("YouTube search source failed at {}: {}", searchUrl, error.getMessage());
        }
      }
      if (successfulSearches == 0) {
        throw lastSearchError == null
            ? new IllegalStateException("All YouTube search sources failed")
            : lastSearchError;
      }
      logger.info("YouTube search pages contained {} unique video results", videoRenderers.size());

      List<LiveStream> streams = new ArrayList<>(individualVideos);
      for (JsonNode video : videoRenderers.values()) {
        String videoId = video.path("videoId").asText("");
        String title = text(video.path("title"));
        String channelTitle = text(video.path("ownerText"));
        if (channelTitle.isBlank()) {
          channelTitle = text(video.path("longBylineText"));
        }

        String countText = text(video.path("viewCountText"));
        if (countText.isBlank()) {
          countText = text(video.path("shortViewCountText"));
        }

        long viewers = parseLiveViewerCount(countText);
        if (videoId.isBlank() || viewers < 0) {
          continue;
        }

        JsonNode channelEndpoint =
            video.path("ownerText").path("runs").path(0).path("navigationEndpoint");
        if (channelEndpoint.isMissingNode()) {
          channelEndpoint =
              video.path("longBylineText").path("runs").path(0).path("navigationEndpoint");
        }
        String channelId = channelEndpoint.path("browseEndpoint").path("browseId").asText("");

        streams.add(
            new LiveStream(
                "YouTube",
                videoId,
                channelId,
                channelTitle,
                title,
                viewers,
                sources.youtubeWatchPageUrl(videoId)));
      }

      streams = new ArrayList<>(refreshViewerCounts(streams));
      streams.sort(Comparator.comparingLong(LiveStream::viewers).reversed());
      long totalViewers = streams.stream().mapToLong(LiveStream::viewers).sum();
      logger.info("YouTube collection complete");
      logger.info("Live streams collected: {}", streams.size());
      logger.info("Total viewers across collected streams: {}", totalViewers);
      logger.info("YouTube Top 20:");
      for (int index = 0; index < Math.min(20, streams.size()); index++) {
        LiveStream stream = streams.get(index);
        logger.info(
            "{}. {} -> {} viewers | {}",
            index + 1,
            stream.channelTitle(),
            stream.viewers(),
            stream.title());
      }
      cachedFetchResults = List.copyOf(streams);
      lastFetchTime = Instant.now();
      return cachedFetchResults;
    } catch (Exception e) {
      logger.error("Failed to collect YouTube search results", e);
      throw new IllegalStateException("Failed to collect YouTube search results", e);
    }
  }

  private List<LiveStream> refreshViewerCounts(List<LiveStream> streams) {
    long nowMillis = System.currentTimeMillis();
    if (nowMillis < watchPageBlockedUntilMillis.get()) {
      logger.info("Skipping YouTube watch-page refresh during rate-limit backoff");
      return applyCachedViewerCounts(streams);
    }

    Instant now = Instant.ofEpochMilli(nowMillis);
    List<LiveStream> candidates =
        streams.stream()
            .filter(
                stream -> {
                  Instant lastAttempt = lastWatchPageAttempt.get(stream.id());
                  return lastAttempt == null
                      || !now.isBefore(lastAttempt.plus(WATCH_PAGE_REFRESH_INTERVAL));
                })
            .toList();
    candidates.forEach(stream -> lastWatchPageAttempt.put(stream.id(), now));

    AtomicInteger refreshedCount = new AtomicInteger();
    List<LiveStream> refreshedStreams =
        Flux.fromIterable(candidates)
            .flatMap(
                stream ->
                    client
                        .get()
                        .uri(URI.create(stream.url()))
                        .header("User-Agent", "Mozilla/5.0")
                        .header("Accept-Language", "en-US,en;q=0.9")
                        .retrieve()
                        .bodyToMono(String.class)
                        .map(this::parseWatchPageData)
                        .map(
                            watchPageData -> {
                              if (watchPageData == null) {
                                logger.warn(
                                    "YouTube watch page did not confirm live status for {}; using search result",
                                    stream.id());
                                return stream;
                              }
                              Instant checkedAt = Instant.now();
                              if (!watchPageData.live()) {
                                cachedWatchPageStatus.put(
                                    stream.id(),
                                    new CachedViewerCount(stream.viewers(), checkedAt, false));
                                logger.info(
                                    "Excluding YouTube video {} because its watch page is not live",
                                    stream.id());
                                return stream;
                              }
                              if (watchPageData.viewers() < 0) {
                                cachedWatchPageStatus.put(
                                    stream.id(),
                                    new CachedViewerCount(stream.viewers(), checkedAt, true));
                                logger.warn(
                                    "YouTube watch page confirmed {} is live but had no viewer count; using search result count",
                                    stream.id());
                                return stream;
                              }
                              refreshedCount.incrementAndGet();
                              cachedWatchPageStatus.put(
                                  stream.id(),
                                  new CachedViewerCount(watchPageData.viewers(), checkedAt, true));
                              return withViewerCount(stream, watchPageData.viewers());
                            })
                        .onErrorResume(
                            error -> {
                              if (error instanceof WebClientResponseException responseException
                                  && responseException.getStatusCode().value() == 429) {
                                long backoffMillis =
                                    responseException
                                        .getHeaders()
                                        .getFirst("Retry-After") == null
                                        ? RATE_LIMIT_BACKOFF.toMillis()
                                        : parseRetryAfterMillis(
                                            responseException
                                                .getHeaders()
                                                .getFirst("Retry-After"));
                                watchPageBlockedUntilMillis.set(
                                    System.currentTimeMillis() + backoffMillis);
                                logger.warn(
                                    "YouTube rate limited watch-page refresh for {}; pausing refreshes for {} seconds",
                                    stream.id(),
                                    Duration.ofMillis(backoffMillis).toSeconds());
                              } else {
                                logger.warn(
                                    "Could not refresh YouTube viewer count for {}; using cached/search result count: {}",
                                    stream.id(),
                                    error.getMessage());
                              }
                              return Mono.just(stream);
                            }),
                WATCH_PAGE_CONCURRENCY)
            .collectList()
            .block();

    if (refreshedStreams == null && !candidates.isEmpty()) {
      throw new IllegalStateException("YouTube watch page refresh returned no result");
    }
    logger.info(
        "YouTube watch pages refreshed viewer counts for {}/{} streams",
        refreshedCount.get(),
        candidates.size());

    if (refreshedStreams != null) {
      Map<String, LiveStream> refreshedById =
          refreshedStreams.stream()
              .collect(
                  java.util.stream.Collectors.toMap(
                      LiveStream::id, stream -> stream, (first, second) -> second));
      streams =
          streams.stream()
              .map(stream -> refreshedById.getOrDefault(stream.id(), stream))
              .toList();
    }

    return applyCachedViewerCounts(streams);
  }

  private List<LiveStream> applyCachedViewerCounts(List<LiveStream> streams) {
    Instant now = Instant.now();
    return streams.stream()
        .filter(
            stream -> {
              CachedViewerCount cached = cachedWatchPageStatus.get(stream.id());
              return cached == null
                  || now.isAfter(cached.updatedAt().plus(WATCH_PAGE_REFRESH_INTERVAL))
                  || cached.live();
            })
        .map(
            stream -> {
              CachedViewerCount cached = cachedWatchPageStatus.get(stream.id());
              if (cached == null
                  || now.isAfter(cached.updatedAt().plus(WATCH_PAGE_REFRESH_INTERVAL))
                  || !cached.live()) {
                return stream;
              }
              return withViewerCount(stream, cached.viewers());
            })
        .toList();
  }

  private LiveStream withViewerCount(LiveStream stream, long viewers) {
    return new LiveStream(
        stream.platform(),
        stream.id(),
        stream.channelId(),
        stream.channelTitle(),
        stream.title(),
        viewers,
        stream.url());
  }

  private long parseRetryAfterMillis(String retryAfter) {
    try {
      return Math.max(1, Long.parseLong(retryAfter)) * 1000;
    } catch (NumberFormatException ignored) {
      try {
        return Math.max(
            1,
            Duration.between(
                    Instant.now(),
                    ZonedDateTime.parse(retryAfter, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant())
                .toMillis());
      } catch (DateTimeParseException invalidDate) {
        return RATE_LIMIT_BACKOFF.toMillis();
      }
    }
  }

  private WatchPageData parseWatchPageData(String html) {
    Matcher rendererMatcher = WATCH_PAGE_RENDERER.matcher(html);
    if (!rendererMatcher.find()) {
      return null;
    }

    int start = rendererMatcher.end();
    int end = Math.min(html.length(), start + 10_000);
    int nextRenderer = html.indexOf("\"videoActions\"", start);
    if (nextRenderer >= 0 && nextRenderer < end) {
      end = nextRenderer;
    }
    String renderer = html.substring(start, end);

    Matcher liveMatcher = WATCH_PAGE_LIVE_STATUS.matcher(renderer);
    if (!liveMatcher.find()) {
      return null;
    }

    Matcher viewersMatcher = WATCH_PAGE_VIEWER_COUNT.matcher(renderer);
    long viewers = viewersMatcher.find() ? Long.parseLong(viewersMatcher.group(1)) : -1;
    return new WatchPageData(Boolean.parseBoolean(liveMatcher.group(1)), viewers);
  }

  private LiveStream parseIndividualVideo(String html, String expectedVideoId, String url) {
    try {
      JsonNode playerResponse = mapper.readTree(extractJsonObject(html, PLAYER_RESPONSE_MARKER));
      JsonNode details = playerResponse.path("videoDetails");
      JsonNode liveDetails =
          playerResponse
              .path("microformat")
              .path("playerMicroformatRenderer")
              .path("liveBroadcastDetails");
      String videoId = details.path("videoId").asText(expectedVideoId);
      boolean liveNow = liveDetails.path("isLiveNow").asBoolean(false);
      long viewers = details.path("viewCount").asLong(-1);
      if (!videoId.equals(expectedVideoId) || !liveNow || viewers < 0) {
        return null;
      }
      return new LiveStream(
          "YouTube",
          videoId,
          details.path("channelId").asText(""),
          details.path("author").asText(""),
          details.path("title").asText(""),
          viewers,
          url);
    } catch (Exception error) {
      throw new IllegalStateException(
          "Could not parse individual YouTube video " + expectedVideoId, error);
    }
  }

  private String videoIdFromWatchUrl(String url) {
    URI uri = URI.create(url);
    if (uri.getHost() == null
        || (!uri.getHost().equalsIgnoreCase("www.youtube.com")
            && !uri.getHost().equalsIgnoreCase("youtube.com"))) {
      return "";
    }
    if (!"/watch".equals(uri.getPath()) || uri.getRawQuery() == null) {
      return "";
    }
    for (String parameter : uri.getRawQuery().split("&")) {
      String[] pair = parameter.split("=", 2);
      if (pair.length == 2 && pair[0].equals("v")) {
        return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
      }
    }
    return "";
  }

  private record WatchPageData(boolean live, long viewers) {}

  private record CachedViewerCount(long viewers, Instant updatedAt, boolean live) {}

  private String extractInitialData(String html) {
    return extractJsonObject(html, INITIAL_DATA_MARKER);
  }

  private String extractJsonObject(String html, String marker) {
    int markerIndex = html.indexOf(marker);
    if (markerIndex < 0) {
      throw new IllegalStateException("YouTube page did not contain " + marker);
    }

    int start = html.indexOf('{', markerIndex + marker.length());
    if (start < 0) {
      throw new IllegalStateException("YouTube " + marker + " was malformed");
    }

    boolean inString = false;
    boolean escaped = false;
    int depth = 0;
    for (int index = start; index < html.length(); index++) {
      char current = html.charAt(index);

      if (inString) {
        if (escaped) {
          escaped = false;
        } else if (current == '\\') {
          escaped = true;
        } else if (current == '"') {
          inString = false;
        }
        continue;
      }

      if (current == '"') {
        inString = true;
      } else if (current == '{') {
        depth++;
      } else if (current == '}' && --depth == 0) {
        return html.substring(start, index + 1);
      }
    }

    throw new IllegalStateException("YouTube " + marker + " was incomplete");
  }

  private void collectVideoRenderers(JsonNode node, List<JsonNode> results) {
    if (node.isObject()) {
      JsonNode renderer = node.get("videoRenderer");
      if (renderer != null && renderer.isObject()) {
        results.add(renderer);
      }
      node.elements().forEachRemaining(child -> collectVideoRenderers(child, results));
    } else if (node.isArray()) {
      node.elements().forEachRemaining(child -> collectVideoRenderers(child, results));
    }
  }

  private String text(JsonNode textNode) {
    JsonNode runs = textNode.path("runs");
    if (runs.isArray() && !runs.isEmpty()) {
      StringBuilder value = new StringBuilder();
      runs.forEach(run -> value.append(run.path("text").asText("")));
      return value.toString();
    }
    return textNode.path("simpleText").asText("");
  }

  private long parseLiveViewerCount(String text) {
    Matcher matcher = VIEWER_COUNT.matcher(text);
    if (!matcher.find()) {
      return -1;
    }

    double count = Double.parseDouble(matcher.group(1).replace(",", ""));
    double multiplier =
        switch (matcher.group(2).toUpperCase()) {
          case "K" -> 1_000;
          case "M" -> 1_000_000;
          case "B" -> 1_000_000_000;
          default -> 1;
        };
    return Math.round(count * multiplier);
  }
}
