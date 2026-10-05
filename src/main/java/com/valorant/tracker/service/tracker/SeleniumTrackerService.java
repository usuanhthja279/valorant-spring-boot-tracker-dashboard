package com.valorant.tracker.service.tracker;

import com.valorant.tracker.model.LiveStream;
import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.model.StreamSample;
import com.valorant.tracker.service.selenium.KickSeleniumDiscoveryService;
import com.valorant.tracker.service.selenium.TwitchSeleniumDiscoveryService;
import com.valorant.tracker.service.selenium.YouTubeSeleniumWorldwideDiscoveryService;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Aggregate tracker backed exclusively by the Selenium discovery providers.
 *
 * This service is intentionally separate from TrackerService. The existing
 * API-based tracker remains unchanged while this Selenium tracker is developed
 * and validated.
 */
@Service
public class SeleniumTrackerService {
  private static final Logger log = LoggerFactory.getLogger(SeleniumTrackerService.class);

  private final YouTubeSeleniumWorldwideDiscoveryService youtube;
  private final TwitchSeleniumDiscoveryService twitch;
  private final KickSeleniumDiscoveryService kick;
  private final EntityManager entityManager;
  private final TransactionTemplate transactions;
  private final ExecutorService executor = Executors.newFixedThreadPool(3);

  private volatile OffsetDateTime lastRun;
  private volatile Map<String, ProviderHealth> providerHealth = initialHealth();

  public SeleniumTrackerService(
      YouTubeSeleniumWorldwideDiscoveryService youtube,
      TwitchSeleniumDiscoveryService twitch,
      KickSeleniumDiscoveryService kick,
      EntityManager entityManager,
      PlatformTransactionManager transactionManager) {
    this.youtube = youtube;
    this.twitch = twitch;
    this.kick = kick;
    this.entityManager = entityManager;
    this.transactions = new TransactionTemplate(transactionManager);
  }

  /**
   * Collects all three Selenium providers concurrently and persists one
   * snapshot plus one StreamSample for every discovered stream.
   */
  public synchronized Map<String, Object> collect() {
    OffsetDateTime timestamp = OffsetDateTime.now(ZoneId.of("Asia/Kolkata"));

    CompletableFuture<ProviderResult> youtubeFuture =
        CompletableFuture.supplyAsync(
            () -> fetch("YouTube", youtube::fetch), executor);
    CompletableFuture<ProviderResult> twitchFuture =
        CompletableFuture.supplyAsync(
            () -> fetch("Twitch", twitch::fetch), executor);
    CompletableFuture<ProviderResult> kickFuture =
        CompletableFuture.supplyAsync(
            () -> fetch("Kick", kick::fetch), executor);

    CompletableFuture.allOf(youtubeFuture, twitchFuture, kickFuture).join();

    ProviderResult yt = youtubeFuture.join();
    ProviderResult tw = twitchFuture.join();
    ProviderResult ki = kickFuture.join();

    List<LiveStream> streams = new ArrayList<>();
    streams.addAll(deduplicate("YouTube", yt.streams()));
    streams.addAll(deduplicate("Twitch", tw.streams()));
    streams.addAll(deduplicate("Kick", ki.streams()));

    long ytViewers = sum(yt.streams());
    long twViewers = sum(tw.streams());
    long kiViewers = sum(ki.streams());

    transactions.executeWithoutResult(
        status -> {
          entityManager.persist(
              new Snapshot(
                  timestamp,
                  ytViewers,
                  twViewers,
                  kiViewers,
                  yt.streams().size(),
                  tw.streams().size(),
                  ki.streams().size(),
                  yt.success(),
                  tw.success(),
                  ki.success()));

          for (LiveStream stream : streams) {
            entityManager.persist(new StreamSample(timestamp, stream));
          }
        });

    lastRun = timestamp;
    Map<String, ProviderHealth> updated = new LinkedHashMap<>();
    updated.put("YouTube", health(yt));
    updated.put("Twitch", health(tw));
    updated.put("Kick", health(ki));
    providerHealth = Map.copyOf(updated);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "collected");
    result.put("timestamp", timestamp);
    result.put("lastRun", lastRun);
    result.put("totalStreams", streams.size());
    result.put("totalViewers", ytViewers + twViewers + kiViewers);
    result.put("providers", providerHealth);

    log.info(
        "Selenium tracker | YouTube {} / {} | Twitch {} / {} | Kick {} / {} | TOTAL {}",
        yt.streams().size(), ytViewers,
        tw.streams().size(), twViewers,
        ki.streams().size(), kiViewers,
        ytViewers + twViewers + kiViewers);

    return result;
  }

  private ProviderResult fetch(String platform, Supplier<List<LiveStream>> supplier) {
    long started = System.currentTimeMillis();
    try {
      List<LiveStream> result = supplier.get();
      if (result == null) {
        throw new IllegalStateException(platform + " Selenium provider returned null");
      }
      List<LiveStream> unique = deduplicate(platform, result);
      log.info(
          "Selenium {} succeeded: {} streams / {} viewers in {} ms",
          platform, unique.size(), sum(unique), System.currentTimeMillis() - started);
      return new ProviderResult(unique, true, null, System.currentTimeMillis() - started);
    } catch (Exception e) {
      String error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
      log.warn(
          "Selenium {} failed after {} ms: {}",
          platform, System.currentTimeMillis() - started, error);
      return new ProviderResult(List.of(), false, error, System.currentTimeMillis() - started);
    }
  }

  private List<LiveStream> deduplicate(String platform, List<LiveStream> streams) {
    Map<String, LiveStream> unique = new LinkedHashMap<>();
    for (LiveStream stream : streams) {
      if (stream == null || !platform.equalsIgnoreCase(stream.platform())) {
        continue;
      }
      String channelId = safe(stream.channelId());
      String id = safe(stream.id());
      String channel = safe(stream.channelTitle());
      String identity =
          !channelId.isBlank()
              ? "channel:" + channelId
              : !id.isBlank()
                  ? "id:" + id
                  : "name:" + channel.toLowerCase(java.util.Locale.ROOT);

      if (identity.equals("name:")) {
        continue;
      }

      LiveStream existing = unique.get(identity);
      if (existing == null || stream.viewers() > existing.viewers()) {
        unique.put(identity, stream);
      }
    }
    return List.copyOf(unique.values());
  }

  private ProviderHealth health(ProviderResult result) {
    return new ProviderHealth(
        result.success() ? "UP" : "DOWN",
        result.success() ? lastRun : null,
        result.error(),
        result.streams().size(),
        sum(result.streams()),
        result.elapsedMs());
  }

  private long sum(List<LiveStream> streams) {
    return streams.stream().mapToLong(LiveStream::viewers).sum();
  }

  private String safe(String value) {
    return value == null ? "" : value.trim();
  }

  public OffsetDateTime getLastRun() {
    return lastRun;
  }

  public Map<String, ProviderHealth> getProviderHealth() {
    return providerHealth;
  }

  @PreDestroy
  void shutdown() {
    executor.shutdown();
  }

  private static Map<String, ProviderHealth> initialHealth() {
    Map<String, ProviderHealth> result = new LinkedHashMap<>();
    result.put("YouTube", ProviderHealth.initial());
    result.put("Twitch", ProviderHealth.initial());
    result.put("Kick", ProviderHealth.initial());
    return Map.copyOf(result);
  }

  public record ProviderHealth(
      String status,
      OffsetDateTime completedAt,
      String lastError,
      int streamCount,
      long viewerCount,
      long elapsedMs) {
    static ProviderHealth initial() {
      return new ProviderHealth("UNKNOWN", null, null, 0, 0, 0);
    }
  }

  private record ProviderResult(
      List<LiveStream> streams, boolean success, String error, long elapsedMs) {}
}
