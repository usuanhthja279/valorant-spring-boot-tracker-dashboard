package com.valorant.tracker.service.tracker;

import com.valorant.tracker.constant.GameConfig;
import com.valorant.tracker.model.LiveStream;
import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.model.StreamSample;
import com.valorant.tracker.service.api.TwitchService;
import com.valorant.tracker.service.misc.DynamicGameScheduler;
import com.valorant.tracker.service.misc.MatchStreamMatcher;
import com.valorant.tracker.service.misc.LiquipediaEsportsScheduleScraper;
import com.valorant.tracker.service.scraper.YouTubeScraperService;
import com.valorant.tracker.service.selenium.YouTubeSeleniumWorldwideDiscoveryService;
import com.valorant.tracker.service.scraper.KickScraperService;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TrackerService {
  private static final Logger logger = LoggerFactory.getLogger(TrackerService.class);

  final YouTubeSeleniumWorldwideDiscoveryService yt;
  final YouTubeScraperService yt1;
  final TwitchService tw;
  final KickScraperService kick;
  final DynamicGameScheduler dynamicGameScheduler;
  final MatchStreamMatcher matchStreamMatcher;
  final EntityManager em;
  final TransactionTemplate transactions;

  private volatile OffsetDateTime lastRun;
  @Value("${tracker.interval-ms:120000}")
  private long intervalMs;
  /** Backward-compatible aggregate health keyed by platform. */
  private final Map<String, ProviderHealth> providerHealth = new ConcurrentHashMap<>();
  /** Exact provider health keyed by game + platform. */
  private final Map<String, ProviderHealth> gameProviderHealth = new ConcurrentHashMap<>();
  /** Last successful provider data, isolated by game + platform. */
  private final Map<String, CachedProviderData> lastSuccessfulData = new ConcurrentHashMap<>();
  /** Consecutive failed/no-data collection runs, isolated by game + platform. */
  private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
  /** Number of failed runs for which the previous successful result may be reused. */
  private static final int MAX_CACHED_FAILURE_RUNS = 2;
  private final ExecutorService providerExecutor = Executors.newFixedThreadPool(6);
  private final ExecutorService gameExecutor = Executors.newFixedThreadPool(2);

  /*
   * Selenium launches a full ChromeDriver instance and is much heavier than
   * the API/scraper providers. Keep Selenium concurrency bounded separately
   * so adding games does not create too many Chrome instances at once.
   */
  private final ExecutorService seleniumExecutor = Executors.newFixedThreadPool(2);

  public TrackerService(
          YouTubeSeleniumWorldwideDiscoveryService y, YouTubeScraperService yt12,
          TwitchService t,
          KickScraperService k, DynamicGameScheduler dynamicGameScheduler,
          MatchStreamMatcher matchStreamMatcher, EntityManager e,
          PlatformTransactionManager transactionManager) {
    yt = y;
    yt1 = yt12;
    tw = t;
    kick = k;
      this.dynamicGameScheduler = dynamicGameScheduler;
      this.matchStreamMatcher = matchStreamMatcher;
      em = e;
    transactions = new TransactionTemplate(transactionManager);
    providerHealth.put("YouTube", ProviderHealth.initial());
    providerHealth.put("Twitch", ProviderHealth.initial());
    providerHealth.put("Kick", ProviderHealth.initial());
  }

  /**
   * Run immediately when scheduling starts, then every 2 minutes from the
   * scheduled start time: 0-1 min collect, 1-2 min idle, 2-3 min collect, etc.
   *
   * fixedRate is intentional here. fixedDelay would wait two full minutes
   * after a collection finishes and would produce a different cadence.
   */
  @Scheduled(
          initialDelayString = "${tracker.initial-delay-ms:0}",
          fixedRateString = "${tracker.interval-ms:120000}")
  public void scheduled() {
    collectAllGames();
  }

  public synchronized void collectValorantData() {
    collectGameData(GameConfig.VALORANT);
  }

  public synchronized void collectAllGames() {

    List<CompletableFuture<Void>> gameFutures = new ArrayList<>();

    for (GameConfig game : GameConfig.values()) {
      String gameId = game.getId();
      if (dynamicGameScheduler.isManagedGame(gameId) && !dynamicGameScheduler.isGameActive(gameId)) {
        logger.info("{} | esports schedule inactive - skipping collection", gameId);
        continue;
      }

      gameFutures.add(CompletableFuture.runAsync(() -> collectGameData(game), gameExecutor));
    }

    if (gameFutures.isEmpty()) {
      logger.info("No games scheduled for collection");
      return;
    }

    CompletableFuture.allOf(gameFutures.toArray(new CompletableFuture[0])).join();
  }

  private void collectGameData(GameConfig game) {
    OffsetDateTime timestamp = OffsetDateTime.now(ZoneId.of("Asia/Kolkata"));

    CompletableFuture<ProviderResult> youtubeFetch = CompletableFuture.supplyAsync(
            () -> fetchProvider(game.getId(), "YouTube", () -> {
              logger.debug("{} YouTube Selenium slot acquired", game.getId());
              try {
                return  yt.fetch(50, "worldwide", game.getDisplayName(), game.getYoutubeTopicUrl());
              } finally {
                logger.debug("{} YouTube Selenium slot released", game.getId());
              }
            }, timestamp), seleniumExecutor);
    CompletableFuture<ProviderResult> twitchFetch = CompletableFuture.supplyAsync(
            () -> fetchProvider(game.getId(), "Twitch", () -> tw.fetch(game.getDisplayName(), game.getId()), timestamp), providerExecutor);
    CompletableFuture<ProviderResult> kickFetch = CompletableFuture.supplyAsync(
            () -> fetchProvider(game.getId(), "Kick", () -> kick.fetch(game.getDisplayName(), game.getKickUrl(), true), timestamp), providerExecutor);

    CompletableFuture.allOf(youtubeFetch, twitchFetch, kickFetch).join();
    ProviderResult youtube = youtubeFetch.join();
    ProviderResult twitch = twitchFetch.join();
    ProviderResult kickResult = kickFetch.join();

    List<LiveStream> streams = new ArrayList<>();
    streams.addAll(youtube.streams());
    streams.addAll(twitch.streams());
    streams.addAll(kickResult.streams());

    long youtubeViewers = sum(streams, "YouTube");
    long twitchViewers = sum(streams, "Twitch");
    long kickViewers = sum(streams, "Kick");

    List<LiquipediaEsportsScheduleScraper.EsportsMatch> activeMatches =
            dynamicGameScheduler.getActiveMatches(game.getDisplayName());

    transactions.executeWithoutResult(status -> {
      em.persist(new Snapshot(timestamp, game.getId(), youtubeViewers, twitchViewers, kickViewers,
              count(streams, "YouTube"), count(streams, "Twitch"), count(streams, "Kick"),
              youtube.success(), twitch.success(), kickResult.success()));

      streams.forEach(stream -> {
        String matchId = null;
        MatchStreamMatcher.MatchMatchResult bestMatch = MatchStreamMatcher.MatchMatchResult.noMatch();

        // A game can have several simultaneous matches. Associate the stream
        // with the highest-confidence active match rather than the first match.
        for (LiquipediaEsportsScheduleScraper.EsportsMatch activeMatch : activeMatches) {
          MatchStreamMatcher.MatchMatchResult candidate =
                  matchStreamMatcher.match(stream, activeMatch, game.getDisplayName());
          if (candidate.matched() && candidate.score() > bestMatch.score()) {
            bestMatch = candidate;
          }
        }

        if (bestMatch.matched()) {
          matchId = bestMatch.matchId();
          logger.debug("{} | matched stream {} to match {} score={} reasons={}",
                  game.getId(), stream.channelTitle(), matchId,
                  bestMatch.score(), bestMatch.reasons());
        }

        em.persist(new StreamSample(timestamp, game.getId(), stream, matchId));
      });
    });

    lastRun = timestamp;
    logger.info("{} | {} | YouTube {} ({}) | Twitch {} ({}) | Kick {} ({}) | TOTAL {}",
            timestamp, game.getId(), youtubeViewers, healthLabel(youtube.success()), twitchViewers,
            healthLabel(twitch.success()), kickViewers, healthLabel(kickResult.success()),
            youtubeViewers + twitchViewers + kickViewers);
  }

  @PreDestroy
  void shutdownExecutors() {
    providerExecutor.shutdown();
    gameExecutor.shutdown();
    seleniumExecutor.shutdown();
  }

  /**
   * Fetch one provider for one game. Cache/failure state is deliberately keyed by
   * BOTH game and platform so a failed provider for game B can never reuse the
   * successful result from game A.
   *
   * Fallback policy is run-based, not time-based:
   *   run 1 success -> cache result
   *   run 2 failure/no data -> reuse run 1
   *   run 3 failure/no data -> reuse run 1
   *   run 4 failure/no data -> return no data + failure reason
   *
   * A later successful run resets the failure counter and becomes the new cache.
   */
  private ProviderResult fetchProvider(
          String game, String platform, Supplier<List<LiveStream>> fetch, OffsetDateTime timestamp) {
    final String stateKey = providerStateKey(game, platform);

    try {
      List<LiveStream> result = fetch.get();
      if (result == null) {
        throw new IllegalStateException(platform + " service returned a null response for game " + game);
      }

      List<LiveStream> unique = deduplicate(platform, result);

      // For an actively collected game, an empty provider response is treated as
      // no-data/failure. It must follow the same run-based fallback sequence as
      // exceptions. This is scoped to this exact game + platform.
      if (unique.isEmpty()) {
        throw new IllegalStateException(
                platform + " returned no data for game " + game);
      }

      long viewers = unique.stream().mapToLong(LiveStream::viewers).sum();
      lastSuccessfulData.put(stateKey, new CachedProviderData(timestamp, unique));
      consecutiveFailures.remove(stateKey);
      ProviderHealth healthy = new ProviderHealth("UP", timestamp, null, unique.size(), viewers);
      gameProviderHealth.put(stateKey, healthy);
      providerHealth.put(platform, healthy);
      logger.info("{} | {} collection succeeded: {} unique streams, {} viewers",
              game, platform, unique.size(), viewers);
      return new ProviderResult(unique, true);
    } catch (Exception e) {
      String error = conciseError(e);
      return staleOrEmpty(game, platform, timestamp, error);
    }
  }

  private ProviderResult staleOrEmpty(
          String game, String platform, OffsetDateTime timestamp, String error) {
    final String stateKey = providerStateKey(game, platform);
    CachedProviderData cached = lastSuccessfulData.get(stateKey);
    ProviderHealth previous = gameProviderHealth.getOrDefault(stateKey, ProviderHealth.initial());

    int failures = consecutiveFailures.merge(stateKey, 1, Integer::sum);
    List<LiveStream> retained = List.of();

    if (cached != null && failures <= MAX_CACHED_FAILURE_RUNS) {
      retained = cached.streams();
      logger.warn(
              "{} | {} fetch failed on run {} of {} (reason: {}). "
                      + "Using last successful data from {}: {} streams / {} viewers",
              game, platform, failures, MAX_CACHED_FAILURE_RUNS + 1, error,
              cached.retrievedAt(), retained.size(),
              retained.stream().mapToLong(LiveStream::viewers).sum());
    } else {
      logger.error(
              "{} | {} fetch failed on run {} (reason: {}). "
                      + "No cached data will be used; returning NO DATA",
              game, platform, failures, error);
    }

    long viewers = retained.stream().mapToLong(LiveStream::viewers).sum();
    ProviderHealth down = new ProviderHealth(
            "DOWN", previous.lastSuccessAt(), error, retained.size(), viewers);
    gameProviderHealth.put(stateKey, down);
    providerHealth.put(platform, down);
    return new ProviderResult(retained, false);
  }

  private String providerStateKey(String game, String platform) {
    return game.trim().toUpperCase(java.util.Locale.ROOT)
            + "::"
            + platform.trim().toLowerCase(java.util.Locale.ROOT);
  }

  /** Cached only as the last successful result for one game + platform. */
  private record CachedProviderData(OffsetDateTime retrievedAt, List<LiveStream> streams) {}

  private List<LiveStream> deduplicate(String platform, List<LiveStream> streams) {
    Map<String, LiveStream> unique = new LinkedHashMap<>();
    for (LiveStream stream : streams) {
      if (stream == null || stream.platform() == null
              || !platform.equalsIgnoreCase(stream.platform())) {
        continue;
      }
      String id = stream.id() == null ? "" : stream.id().trim();
      String channelId = stream.channelId() == null ? "" : stream.channelId().trim();
      String channelName = stream.channelTitle() == null ? "" : stream.channelTitle().trim();
      String identity = !id.isBlank() ? "id:" + id
              : !channelId.isBlank() ? "channel:" + channelId
              : "name:" + channelName.toLowerCase(java.util.Locale.ROOT);
      if (identity.equals("name:")) {
        logger.warn("Skipping {} stream with no usable identity", platform);
        continue;
      }
      LiveStream existing = unique.get(identity);
      if (existing == null || stream.viewers() > existing.viewers()) {
        unique.put(identity, stream);
      }
    }
    return List.copyOf(unique.values());
  }

  private String conciseError(Exception e) {
    String message = e.getMessage();
    if (message == null || message.isBlank()) {
      return e.getClass().getSimpleName();
    }
    return message.length() > 240 ? message.substring(0, 240) : message;
  }

  private String healthLabel(boolean success) {
    return success ? "UP" : "DOWN";
  }

  long sum(List<LiveStream> streams, String platform) {
    return streams.stream()
            .filter(stream -> platform.equalsIgnoreCase(stream.platform()))
            .mapToLong(LiveStream::viewers)
            .sum();
  }

  int count(List<LiveStream> streams, String platform) {
    return (int) streams.stream()
            .filter(stream -> platform.equalsIgnoreCase(stream.platform()))
            .count();
  }

  public OffsetDateTime getLastRun() {
    return lastRun;
  }

  public long getIntervalMs() {
    return intervalMs;
  }

  public synchronized Map<String, ProviderHealth> getProviderHealth() {
    return Map.copyOf(providerHealth);
  }

  /**
   * Exact provider health for one game. This avoids mixing a failed provider
   * response for one game with another game's successful response.
   */
  public synchronized Map<String, ProviderHealth> getProviderHealth(String game) {
    String prefix = game.trim().toUpperCase(java.util.Locale.ROOT) + "::";
    Map<String, ProviderHealth> result = new LinkedHashMap<>();
    gameProviderHealth.forEach((key, value) -> {
      if (key.startsWith(prefix)) {
        String platform = key.substring(prefix.length());
        result.put(platform, value);
      }
    });
    return Map.copyOf(result);
  }

  public record ProviderHealth(
          String status,
          OffsetDateTime lastSuccessAt,
          String lastError,
          int streamCount,
          long viewerCount) {
    static ProviderHealth initial() {
      return new ProviderHealth("UNKNOWN", null, null, 0, 0);
    }
  }

  private record ProviderResult(List<LiveStream> streams, boolean success) {}
}
