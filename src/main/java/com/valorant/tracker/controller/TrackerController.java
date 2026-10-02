package com.valorant.tracker.controller;

import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.model.StreamSample;
import com.valorant.tracker.service.TrackerService;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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

  /** Bounded, indexed history query for chart ranges. The upper timestamp is exclusive. */
  @GetMapping("/snapshots/range")
  public List<Snapshot> snapshotsInRange(
      @RequestParam OffsetDateTime from,
      @RequestParam OffsetDateTime to,
      @RequestParam(defaultValue = "5000") int limit) {
    validateRange(from, to);
    int safeLimit = clampLimit(limit);
    List<Snapshot> results = entityManager.createQuery(
            "select s from Snapshot s where s.timestamp >= :from and s.timestamp < :to "
                + "order by s.timestamp desc, s.id desc", Snapshot.class)
        .setParameter("from", from).setParameter("to", to).setMaxResults(safeLimit).getResultList();
    results = new ArrayList<>(results);
    java.util.Collections.reverse(results);
    return results;
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
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both 'from' and 'to' must be supplied together");
    }
    if (from != null) validateRange(from, to);
    int safeLimit = clampLimit(limit);

    String query = "select s from StreamSample s where lower(trim(s.channel)) = lower(trim(:name)) ";
    if (from != null) query += "and s.timestamp >= :from and s.timestamp < :to ";
    query += "order by s.timestamp desc, s.id desc";
    var typedQuery = entityManager.createQuery(query, StreamSample.class).setParameter("name", name.trim());
    if (from != null) typedQuery.setParameter("from", from).setParameter("to", to);
    List<StreamSample> history = new ArrayList<>(typedQuery.setMaxResults(safeLimit).getResultList());
    java.util.Collections.reverse(history);
    return history;
  }

  /** Historical analytics for a bounded window [from, to). Rankings are withheld when capped. */
  @GetMapping("/analytics")
  public Map<String, Object> analytics(
      @RequestParam OffsetDateTime from,
      @RequestParam OffsetDateTime to,
      @RequestParam(defaultValue = "20000") int limit) {
    validateRange(from, to);
    int safeLimit = clampLimit(limit);
    List<StreamSample> samples = new ArrayList<>(entityManager.createQuery(
            "select s from StreamSample s where s.timestamp >= :from and s.timestamp < :to "
                + "order by s.timestamp desc, s.id desc", StreamSample.class)
        .setParameter("from", from).setParameter("to", to).setMaxResults(safeLimit + 1).getResultList());
    boolean truncated = samples.size() > safeLimit;
    if (truncated) samples = new ArrayList<>(samples.subList(0, safeLimit));
    java.util.Collections.reverse(samples);

    Map<String, Map<String, Object>> channels = new HashMap<>();
    Map<String, Map<String, Object>> platforms = new LinkedHashMap<>();
    for (String name : List.of("YouTube", "Twitch", "Kick")) {
      Map<String, Object> metric = new LinkedHashMap<>();
      metric.put("platform", name); metric.put("samples", 0L); metric.put("peakViewers", 0L);
      metric.put("viewerSamples", 0L); metric.put("viewerTotal", 0L);
      platforms.put(name, metric);
    }

    long overallPeak = 0L, overallViewerTotal = 0L, sampleCount = 0L;
    for (StreamSample sample : samples) {
      String platform = sample.getPlatform() == null ? "Unknown" : sample.getPlatform();
      Map<String, Object> platformMetric = platforms.computeIfAbsent(platform, name -> {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("platform", name); metric.put("samples", 0L); metric.put("peakViewers", 0L);
        metric.put("viewerSamples", 0L); metric.put("viewerTotal", 0L); return metric;
      });
      long viewers = Math.max(0L, sample.getViewers());
      platformMetric.put("samples", (Long) platformMetric.get("samples") + 1L);
      platformMetric.put("peakViewers", Math.max((Long) platformMetric.get("peakViewers"), viewers));
      platformMetric.put("viewerSamples", (Long) platformMetric.get("viewerSamples") + 1L);
      platformMetric.put("viewerTotal", (Long) platformMetric.get("viewerTotal") + viewers);
      overallPeak = Math.max(overallPeak, viewers); overallViewerTotal += viewers; sampleCount++;

      String identity = platform.toLowerCase(Locale.ROOT) + ":" +
          (sample.getChannelId() == null || sample.getChannelId().isBlank()
              ? String.valueOf(sample.getChannel()).trim().toLowerCase(Locale.ROOT)
              : sample.getChannelId());
      Map<String, Object> channel = channels.computeIfAbsent(identity, key -> {
        Map<String, Object> metric = new LinkedHashMap<>();
        metric.put("platform", platform); metric.put("channel", sample.getChannel());
        metric.put("url", sample.getUrl()); metric.put("firstViewers", viewers);
        metric.put("lastViewers", viewers); metric.put("peakViewers", viewers);
        metric.put("viewerTotal", 0L); metric.put("samples", 0L);
        metric.put("firstTimestamp", sample.getTimestamp()); metric.put("lastTimestamp", sample.getTimestamp());
        return metric;
      });
      channel.put("lastViewers", viewers); channel.put("lastTimestamp", sample.getTimestamp());
      channel.put("peakViewers", Math.max((Long) channel.get("peakViewers"), viewers));
      channel.put("viewerTotal", (Long) channel.get("viewerTotal") + viewers);
      channel.put("samples", (Long) channel.get("samples") + 1L);
    }

    List<Map<String, Object>> fastestGrowing = new ArrayList<>(channels.values());
    for (Map<String, Object> channel : fastestGrowing) {
      long first = (Long) channel.get("firstViewers"), last = (Long) channel.get("lastViewers");
      channel.put("growthViewers", last - first);
      channel.put("growthPercent", first > 0 ? ((last - first) * 100.0 / first) : null);
      channel.put("averageViewers", channel.get("samples") == null || (Long) channel.get("samples") == 0
          ? 0.0 : ((Long) channel.get("viewerTotal")).doubleValue() / (Long) channel.get("samples"));
      channel.remove("viewerTotal");
    }
    fastestGrowing.sort(Comparator.comparingLong((Map<String, Object> c) -> (Long)c.get("growthViewers")).reversed());
    // Rankings require the full selected window; never rank a capped subset as if complete.
    if (truncated) fastestGrowing = new ArrayList<>();
    else if (fastestGrowing.size() > 10) fastestGrowing = new ArrayList<>(fastestGrowing.subList(0, 10));

    for (Map<String, Object> metric : platforms.values()) {
      long count = (Long) metric.get("viewerSamples"), total = (Long) metric.get("viewerTotal");
      metric.put("averageViewers", count == 0 ? 0.0 : (double) total / count);
      metric.remove("viewerSamples"); metric.remove("viewerTotal");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("from", from); result.put("to", to); result.put("samplesReturned", samples.size());
    result.put("truncated", truncated); result.put("analyticsComplete", !truncated);
    result.put("growthRankingsComplete", !truncated);
    result.put("overallPeakViewers", overallPeak);
    result.put("overallAverageViewers", sampleCount == 0 ? 0.0 : (double) overallViewerTotal / sampleCount);
    result.put("platforms", platforms.values()); result.put("fastestGrowingChannels", fastestGrowing);
    return result;
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
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be earlier than 'to'");
    }
  }
}
