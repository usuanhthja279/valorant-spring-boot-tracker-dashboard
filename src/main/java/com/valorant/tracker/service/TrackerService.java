package com.valorant.tracker.service;

import com.valorant.tracker.model.*;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
  volatile OffsetDateTime lastRun;
  private List<LiveStream> lastYouTubeStreams = List.of();
  private List<LiveStream> lastTwitchStreams = List.of();
  private List<LiveStream> lastKickStreams = List.of();

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
  }

  @Scheduled(fixedDelayString = "${tracker.interval-ms:60000}")
  public void scheduled() {
    collect();
  }

  public synchronized void collect() {
    OffsetDateTime timestamp =
        OffsetDateTime.now(ZoneId.of("Asia/Kolkata")).withSecond(0).withNano(0);
    List<LiveStream> streams = new ArrayList<>();
    lastYouTubeStreams = fetchProvider("YouTube", yt::fetch, lastYouTubeStreams);
    lastTwitchStreams = fetchProvider("Twitch", tw::fetch, lastTwitchStreams);
    lastKickStreams = fetchProvider("Kick", kick::fetch, lastKickStreams);
    streams.addAll(lastYouTubeStreams);
    streams.addAll(lastTwitchStreams);
    streams.addAll(lastKickStreams);

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
                  count(streams, "Kick")));
          streams.forEach(stream -> em.persist(new StreamSample(timestamp, stream)));
        });
    lastRun = timestamp;
    System.out.printf(
        "%s | YouTube %,d | Twitch %,d | Kick %,d | TOTAL %,d%n",
        timestamp,
        youtubeViewers,
        twitchViewers,
        kickViewers,
        youtubeViewers + twitchViewers + kickViewers);
  }

  private List<LiveStream> fetchProvider(
      String platform, Supplier<List<LiveStream>> fetch, List<LiveStream> lastSuccessfulStreams) {
    try {
      List<LiveStream> streams = fetch.get();
      if (streams == null) {
        throw new IllegalStateException(platform + " service returned no response");
      }
      return List.copyOf(streams);
    } catch (RuntimeException e) {
      logger.error(
          "{} fetch failed; continuing collection with {} cached streams",
          platform,
          lastSuccessfulStreams.size(),
          e);
      return lastSuccessfulStreams;
    }
  }

  long sum(List<LiveStream> streams, String platform) {
    return streams.stream()
        .filter(stream -> stream.platform().equals(platform))
        .mapToLong(LiveStream::viewers)
        .sum();
  }

  int count(List<LiveStream> streams, String platform) {
    return (int) streams.stream().filter(stream -> stream.platform().equals(platform)).count();
  }

  public OffsetDateTime getLastRun() {
    return lastRun;
  }
}
