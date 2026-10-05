package com.valorant.tracker.service.tracker;

import com.valorant.tracker.constant.URLData;
import com.valorant.tracker.constant.GameConfig;
import com.valorant.tracker.model.LiveStream;
import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.model.StreamSample;
import com.valorant.tracker.service.api.TwitchService;
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
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TrackerService {
  private static final Logger logger = LoggerFactory.getLogger(TrackerService.class);

  final YouTubeSeleniumWorldwideDiscoveryService yt;
  final TwitchService tw;
  final KickScraperService kick;
  final EntityManager em;
  final TransactionTemplate transactions;

  private volatile OffsetDateTime lastRun;
  @Value("${tracker.interval-ms:120000}")
  private long intervalMs;
  private final Map<String, ProviderHealth> providerHealth = new ConcurrentHashMap<>();
  private final Map<String, CachedProviderData> lastSuccessfulData = new ConcurrentHashMap<>();
  private final Map<String, OffsetDateTime> retryAfter = new ConcurrentHashMap<>();
  private final Map<String, OffsetDateTime> outageStarted = new ConcurrentHashMap<>();
  private static final Duration STALE_DATA_GRACE = Duration.ofMinutes(3);
  private static final Duration RATE_LIMIT_COOLDOWN = Duration.ofMinutes(3);
  private final ExecutorService providerExecutor = Executors.newFixedThreadPool(3);

  public TrackerService(
      YouTubeSeleniumWorldwideDiscoveryService y,
      TwitchService t,
      KickScraperService k,
      EntityManager e,
      PlatformTransactionManager transactionManager) {
    yt = y;
    tw = t;
    kick = k;
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
    for (GameConfig game : GameConfig.values()) {
      collectGameData(game);
    }
  }

  private void collectGameData(GameConfig game) {
    OffsetDateTime timestamp = OffsetDateTime.now(ZoneId.of("Asia/Kolkata"));

    CompletableFuture<ProviderResult> youtubeFetch = CompletableFuture.supplyAsync(
        () -> fetchProvider("YouTube", () -> yt.fetch(100, "worldwide", game.getYoutubeUrl()), timestamp), providerExecutor);
    CompletableFuture<ProviderResult> twitchFetch = CompletableFuture.supplyAsync(
        () -> fetchProvider("Twitch", () -> tw.fetch(game.getId()), timestamp), providerExecutor);
    CompletableFuture<ProviderResult> kickFetch = CompletableFuture.supplyAsync(
        () -> fetchProvider("Kick", () -> kick.fetch(game.getKickUrl(), true), timestamp), providerExecutor);

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

    transactions.executeWithoutResult(status -> {
      em.persist(new Snapshot(timestamp, game.getId(), youtubeViewers, twitchViewers, kickViewers,
          count(streams, "YouTube"), count(streams, "Twitch"), count(streams, "Kick"),
          youtube.success(), twitch.success(), kickResult.success()));
      streams.forEach(stream -> em.persist(new StreamSample(timestamp, game.getId(), stream)));
    });

    lastRun = timestamp;
    logger.info("{} | {} | YouTube {} ({}) | Twitch {} ({}) | Kick {} ({}) | TOTAL {}",
        timestamp, game.getId(), youtubeViewers, healthLabel(youtube.success()), twitchViewers,
        healthLabel(twitch.success()), kickViewers, healthLabel(kickResult.success()),
        youtubeViewers + twitchViewers + kickViewers);
  }

  @PreDestroy
  void shutdownProviderExecutor() {
    providerExecutor.shutdown();
  }

  private ProviderResult fetchProvider(
      String platform, Supplier<List<LiveStream>> fetch, OffsetDateTime timestamp) {
    OffsetDateTime blockedUntil = retryAfter.get(platform);
    if (blockedUntil != null && timestamp.isBefore(blockedUntil)) {
      logger.warn("{} fetch is rate-limited; skipping request until {} and preserving last successful data",
          platform, blockedUntil);
      return staleOrEmpty(platform, timestamp, "429 cooldown until " + blockedUntil);
    }
    if (blockedUntil != null) {
      retryAfter.remove(platform, blockedUntil);
    }

    try {
      List<LiveStream> result = fetch.get();
      if (result == null) {
        throw new IllegalStateException(platform + " service returned a null response");
      }

      List<LiveStream> unique = deduplicate(platform, result);

      // A sudden empty result after a previously non-empty successful fetch can be
      // a transient provider/API/search failure rather than a real drop to zero.
      // Treat it as an outage signal and retain the last good data for the same
      // three-minute grace period used for exceptions and HTTP 429 cooldowns.
      CachedProviderData previousData = lastSuccessfulData.get(platform);
      if (unique.isEmpty()
              && previousData != null
              && !previousData.streams().isEmpty()) {
        throw new IllegalStateException(
                platform + " returned zero streams after previously returning live streams; "
                        + "treating empty result as transient and preserving last successful data");
      }

      long viewers = unique.stream().mapToLong(LiveStream::viewers).sum();
      lastSuccessfulData.put(platform, new CachedProviderData(timestamp, unique));
      retryAfter.remove(platform);
      outageStarted.remove(platform);
      providerHealth.put(
          platform,
          new ProviderHealth("UP", timestamp, null, unique.size(), viewers));
      logger.info("{} collection succeeded: {} unique streams, {} viewers",
          platform, unique.size(), viewers);
      return new ProviderResult(unique, true);
    } catch (Exception e) {
      if (isRateLimited(e)) {
        OffsetDateTime until = timestamp.plus(RATE_LIMIT_COOLDOWN);
        retryAfter.put(platform, until);
        logger.warn("{} returned HTTP 429; pausing requests for 3 minutes until {}",
            platform, until);
      }
      return staleOrEmpty(platform, timestamp, conciseError(e));
    }
  }

  private ProviderResult staleOrEmpty(String platform, OffsetDateTime timestamp, String error) {
    CachedProviderData cached = lastSuccessfulData.get(platform);
    ProviderHealth previous = providerHealth.getOrDefault(platform, ProviderHealth.initial());
    List<LiveStream> retained = List.of();
    OffsetDateTime outageAt = outageStarted.computeIfAbsent(platform, ignored -> timestamp);
    boolean withinGrace = Duration.between(outageAt, timestamp).compareTo(STALE_DATA_GRACE) <= 0;
    boolean inRateLimitCooldown = retryAfter.get(platform) != null && timestamp.isBefore(retryAfter.get(platform));
    if (cached != null && (withinGrace || inRateLimitCooldown)) {
      retained = cached.streams();
      logger.warn("{} fetch unavailable; retaining {} streams / {} viewers from {} for consistency",
          platform, retained.size(), retained.stream().mapToLong(LiveStream::viewers).sum(), cached.retrievedAt());
    } else {
      logger.warn("{} fetch unavailable and cached data is older than 3 minutes; using zero streams", platform);
    }
    long viewers = retained.stream().mapToLong(LiveStream::viewers).sum();
    providerHealth.put(platform, new ProviderHealth(
        "DOWN", previous.lastSuccessAt(), error, retained.size(), viewers));
    return new ProviderResult(retained, false);
  }

  private boolean isRateLimited(Exception exception) {
    Throwable current = exception;
    while (current != null) {
      if (current instanceof WebClientResponseException response
          && response.getStatusCode().value() == 429) {
        return true;
      }
      String message = current.getMessage();
      if (message != null && (message.contains("429") || message.toLowerCase(java.util.Locale.ROOT).contains("too many requests"))) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

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
