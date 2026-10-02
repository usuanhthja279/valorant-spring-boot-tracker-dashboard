package com.valorant.tracker.service;

import com.valorant.tracker.model.LiveStream;
import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.model.StreamSample;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class TrackerService {
  private static final Logger logger = LoggerFactory.getLogger(TrackerService.class);

  final YouTubeScraperService yt;
  final TwitchService tw;
  final KickScraperService kick;
  final EntityManager em;
  final TransactionTemplate transactions;

  private volatile OffsetDateTime lastRun;
  private final Map<String, ProviderHealth> providerHealth = new LinkedHashMap<>();

  public TrackerService(
      YouTubeScraperService y,
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

  @Scheduled(fixedDelayString = "${tracker.interval-ms:60000}")
  public void scheduled() {
    collect();
  }

  public synchronized void collect() {
    OffsetDateTime timestamp = OffsetDateTime.now(ZoneId.of("Asia/Kolkata"));

    ProviderResult youtube = fetchProvider("YouTube", yt::fetch, timestamp);
    ProviderResult twitch = fetchProvider("Twitch", tw::fetch, timestamp);
    ProviderResult kickResult = fetchProvider("Kick", kick::fetch, timestamp);

    List<LiveStream> streams = new ArrayList<>();
    streams.addAll(youtube.streams());
    streams.addAll(twitch.streams());
    streams.addAll(kickResult.streams());

    long youtubeViewers = sum(streams, "YouTube");
    long twitchViewers = sum(streams, "Twitch");
    long kickViewers = sum(streams, "Kick");

    transactions.executeWithoutResult(
        status -> {
          em.persist(
              new Snapshot(
                  timestamp,
                  youtubeViewers,
                  twitchViewers,
                  kickViewers,
                  count(streams, "YouTube"),
                  count(streams, "Twitch"),
                  count(streams, "Kick"),
                  youtube.success(),
                  twitch.success(),
                  kickResult.success()));
          streams.forEach(stream -> em.persist(new StreamSample(timestamp, stream)));
        });

    lastRun = timestamp;
    logger.info(
        "{} | YouTube {} ({}) | Twitch {} ({}) | Kick {} ({}) | TOTAL {}",
        timestamp,
        youtubeViewers,
        healthLabel(youtube.success()),
        twitchViewers,
        healthLabel(twitch.success()),
        kickViewers,
        healthLabel(kickResult.success()),
        youtubeViewers + twitchViewers + kickViewers);
  }

  private ProviderResult fetchProvider(
      String platform, Supplier<List<LiveStream>> fetch, OffsetDateTime timestamp) {
    try {
      List<LiveStream> result = fetch.get();
      if (result == null) {
        throw new IllegalStateException(platform + " service returned a null response");
      }

      List<LiveStream> unique = deduplicate(platform, result);
      long viewers = unique.stream().mapToLong(LiveStream::viewers).sum();
      providerHealth.put(
          platform,
          new ProviderHealth("UP", timestamp, null, unique.size(), viewers));
      logger.info("{} collection succeeded: {} unique streams, {} viewers",
          platform, unique.size(), viewers);
      return new ProviderResult(unique, true);
    } catch (Exception e) {
      ProviderHealth previous = providerHealth.getOrDefault(platform, ProviderHealth.initial());
      providerHealth.put(
          platform,
          new ProviderHealth(
              "DOWN",
              previous.lastSuccessAt(),
              conciseError(e),
              0,
              0));
      logger.error(
          "{} collection failed; excluding stale cached streams from this snapshot. Last success: {}",
          platform,
          previous.lastSuccessAt(),
          e);
      // Do not carry old streams into a new snapshot: that would make stale counts look current.
      return new ProviderResult(List.of(), false);
    }
  }

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
