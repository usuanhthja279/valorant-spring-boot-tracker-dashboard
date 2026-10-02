package com.valorant.tracker.controller;

import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.model.StreamSample;
import com.valorant.tracker.service.TrackerService;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class TrackerController {
  private static final int DEFAULT_HISTORY_LIMIT = 5000;
  private static final int MAX_HISTORY_LIMIT = 20000;

  private final EntityManager entityManager;
  private final TrackerService tracker;

  public TrackerController(EntityManager entityManager, TrackerService tracker) {
    this.entityManager = entityManager;
    this.tracker = tracker;
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("running", true);
    result.put("lastRun", tracker.getLastRun() == null ? "not-run" : tracker.getLastRun());
    result.put("providers", tracker.getProviderHealth());
    return result;
  }

  @PostMapping("/collect")
  public Map<String, Object> collect() {
    tracker.collect();
    return Map.of("status", "collected", "lastRun", tracker.getLastRun());
  }

  /** Recent snapshots, kept for compatibility with the existing dashboard. */
  @GetMapping("/snapshots")
  public List<Snapshot> snapshots() {
    return entityManager.createQuery(
            "select s from Snapshot s order by s.timestamp desc, s.id desc", Snapshot.class)
        .setMaxResults(500).getResultList();
  }

  /** Bounded, indexed history query for chart ranges. Timestamps must be ISO-8601 offsets. */
  @GetMapping("/snapshots/range")
  public List<Snapshot> snapshotsInRange(
      @RequestParam OffsetDateTime from,
      @RequestParam OffsetDateTime to,
      @RequestParam(defaultValue = "5000") int limit) {
    validateRange(from, to);
    int safeLimit = clampLimit(limit);
    return entityManager.createQuery(
            "select s from Snapshot s where s.timestamp >= :from and s.timestamp <= :to "
                + "order by s.timestamp asc, s.id asc", Snapshot.class)
        .setParameter("from", from).setParameter("to", to).setMaxResults(safeLimit).getResultList();
  }

  @GetMapping("/streams")
  public List<StreamSample> streams(@RequestParam(defaultValue = "Overall") String platform) {
    OffsetDateTime latestTimestamp = entityManager.createQuery(
            "select max(s.timestamp) from StreamSample s", OffsetDateTime.class).getSingleResult();
    if (latestTimestamp == null) return List.of();

    String query = platform.equalsIgnoreCase("Overall")
        ? "select s from StreamSample s where s.timestamp = :timestamp order by s.viewers desc, s.id desc"
        : "select s from StreamSample s where s.timestamp = :timestamp and lower(s.platform) = lower(:platform) order by s.viewers desc, s.id desc";
    var streams = entityManager.createQuery(query, StreamSample.class).setParameter("timestamp", latestTimestamp);
    if (!platform.equalsIgnoreCase("Overall")) streams.setParameter("platform", platform);
    List<StreamSample> results = streams.getResultList();
    Map<String, StreamSample> uniqueChannels = new LinkedHashMap<>();
    for (StreamSample stream : results) {
      String channelKey = stream.getPlatform().toLowerCase() + ":"
          + (stream.getChannelId() == null || stream.getChannelId().isBlank()
              ? (stream.getChannel() == null ? "" : stream.getChannel().trim().toLowerCase())
              : stream.getChannelId());
      uniqueChannels.putIfAbsent(channelKey, stream);
    }
    return List.copyOf(uniqueChannels.values());
  }

  /** Channel history supports database-side time filtering and a hard result cap. */
  @GetMapping("/channels/history")
  public List<StreamSample> channelHistory(
      @RequestParam String name,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(defaultValue = "5000") int limit) {
    if (name.isBlank()) return List.of();
    if ((from == null) != (to == null)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Both 'from' and 'to' must be supplied together");
    }
    if (from != null) validateRange(from, to);
    int safeLimit = clampLimit(limit);

    String query = "select s from StreamSample s where lower(trim(s.channel)) = lower(trim(:name)) ";
    if (from != null) query += "and s.timestamp >= :from and s.timestamp <= :to ";
    query += "order by s.timestamp desc, s.id desc";
    var typedQuery = entityManager.createQuery(query, StreamSample.class).setParameter("name", name.trim());
    if (from != null) typedQuery.setParameter("from", from).setParameter("to", to);
    List<StreamSample> history = typedQuery.setMaxResults(safeLimit).getResultList();
    java.util.Collections.reverse(history);
    return history;
  }

  @GetMapping("/top10")
  public List<StreamSample> top(@RequestParam(defaultValue = "Overall") String platform) {
    return streams(platform).stream().limit(10).toList();
  }

  private static int clampLimit(int limit) {
    return Math.max(1, Math.min(limit, MAX_HISTORY_LIMIT));
  }

  private static void validateRange(OffsetDateTime from, OffsetDateTime to) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST, "'from' must be earlier than 'to'");
    }
  }
}
