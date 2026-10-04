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

  @GetMapping("/health")
  public Map<String, Object> health() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("status", "UP");
    result.put("database", "UP");
    try {
      entityManager.createQuery("select count(s) from Snapshot s", Long.class).getSingleResult();
    } catch (RuntimeException exception) {
      result.put("status", "DOWN");
      result.put("database", "DOWN");
      throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
          "Database health check failed", exception);
    }
    result.put("lastRun", tracker.getLastRun() == null ? "not-run" : tracker.getLastRun());
    result.put("providers", tracker.getProviderHealth());
    return result;
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("running", true);
    result.put("lastRun", tracker.getLastRun() == null ? "not-run" : tracker.getLastRun());
    result.put("intervalMs", tracker.getIntervalMs());
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

  /** Searchable catalog of every channel ever sampled, with live status from the latest collection. */
  @GetMapping("/channels/catalog")
  public List<Map<String, Object>> channelCatalog() {
    OffsetDateTime latestTimestamp = entityManager.createQuery(
        "select max(s.timestamp) from StreamSample s", OffsetDateTime.class).getSingleResult();
    if (latestTimestamp == null) return List.of();

    List<Object[]> grouped = entityManager.createQuery(
        "select s.platform, s.channel, s.channelId, s.url, max(s.timestamp) "
            + "from StreamSample s group by s.platform, s.channel, s.channelId, s.url",
        Object[].class).getResultList();
    List<StreamSample> current = entityManager.createQuery(
        "select s from StreamSample s where s.timestamp = :timestamp", StreamSample.class)
        .setParameter("timestamp", latestTimestamp).getResultList();
    Map<String, StreamSample> liveByIdentity = new HashMap<>();
    for (StreamSample sample : current) {
      String identity = channelIdentity(sample.getPlatform(), sample.getChannelId(), sample.getChannel());
      liveByIdentity.putIfAbsent(identity, sample);
    }

    List<Map<String, Object>> catalog = new ArrayList<>();
    for (Object[] row : grouped) {
      String platform = (String) row[0];
      String channel = (String) row[1];
      String channelId = (String) row[2];
      String url = (String) row[3];
      OffsetDateTime lastSeen = (OffsetDateTime) row[4];
      String identity = channelIdentity(platform, channelId, channel);
      StreamSample liveSample = lastSeen.equals(latestTimestamp) ? liveByIdentity.get(identity) : null;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("platform", platform);
      item.put("channel", channel);
      item.put("channelId", channelId);
      item.put("identity", identity);
      item.put("url", url);
      item.put("live", liveSample != null);
      item.put("viewers", liveSample == null ? null : liveSample.getViewers());
      item.put("lastSeen", lastSeen);
      catalog.add(item);
    }
    catalog.sort(Comparator.comparing((Map<String, Object> item) -> String.valueOf(item.get("channel")), String.CASE_INSENSITIVE_ORDER)
        .thenComparing(item -> String.valueOf(item.get("platform")), String.CASE_INSENSITIVE_ORDER));
    return catalog;
  }

  /** Bounded, indexed channel search for historical comparisons, including offline channels. */
  @GetMapping("/channels/catalog/search")
  public List<Map<String, Object>> searchChannelCatalog(@RequestParam String query) {
    String term = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
    if (term.length() < 2) return List.of();

    OffsetDateTime latestTimestamp = entityManager.createQuery(
        "select max(s.timestamp) from StreamSample s", OffsetDateTime.class).getSingleResult();
    if (latestTimestamp == null) return List.of();

    List<Object[]> grouped = entityManager.createQuery(
            "select s.platform, s.channel, s.channelId, s.url, max(s.timestamp) "
                + "from StreamSample s where lower(s.channel) like :term "
                + "or lower(s.platform) like :term "
                + "or lower(coalesce(s.channelId, '')) like :term "
                + "group by s.platform, s.channel, s.channelId, s.url "
                + "order by lower(s.channel), lower(s.platform)", Object[].class)
        .setParameter("term", "%" + term + "%")
        .setMaxResults(100)
        .getResultList();
    if (grouped.isEmpty()) return List.of();

    List<StreamSample> current = entityManager.createQuery(
            "select s from StreamSample s where s.timestamp = :timestamp", StreamSample.class)
        .setParameter("timestamp", latestTimestamp).getResultList();
    Map<String, StreamSample> liveByIdentity = new HashMap<>();
    for (StreamSample sample : current) {
      liveByIdentity.putIfAbsent(
          channelIdentity(sample.getPlatform(), sample.getChannelId(), sample.getChannel()), sample);
    }

    List<Map<String, Object>> matches = new ArrayList<>();
    for (Object[] row : grouped) {
      String platform = (String) row[0];
      String channel = (String) row[1];
      String channelId = (String) row[2];
      OffsetDateTime lastSeen = (OffsetDateTime) row[4];
      StreamSample liveSample = lastSeen.equals(latestTimestamp)
          ? liveByIdentity.get(channelIdentity(platform, channelId, channel)) : null;
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("platform", platform);
      item.put("channel", channel);
      item.put("channelId", channelId);
      item.put("identity", channelIdentity(platform, channelId, channel));
      item.put("url", row[3]);
      item.put("live", liveSample != null);
      item.put("viewers", liveSample == null ? null : liveSample.getViewers());
      item.put("lastSeen", lastSeen);
      matches.add(item);
    }
    return matches;
  }

  /** Fast live-status lookup for a single channel page; avoids grouping the full sample history. */
  @GetMapping("/channels/status")
  public List<Map<String, Object>> channelStatus(
      @RequestParam String name,
      @RequestParam(required = false) String platform) {
    if (name.isBlank()) return List.of();
    OffsetDateTime latestTimestamp = entityManager.createQuery(
        "select max(s.timestamp) from StreamSample s", OffsetDateTime.class).getSingleResult();
    if (latestTimestamp == null) return List.of();

    List<String> platforms = platform == null || platform.isBlank()
        ? List.of("YouTube", "Twitch", "Kick")
        : List.of(platform.trim());
    List<Map<String, Object>> status = new ArrayList<>();
    for (String requestedPlatform : platforms) {
      List<StreamSample> latest = entityManager.createQuery(
              "select s from StreamSample s where s.channel = :name "
                  + "and lower(s.platform) = lower(:platform) "
                  + "order by s.timestamp desc, s.id desc", StreamSample.class)
          .setParameter("name", name.trim())
          .setParameter("platform", requestedPlatform)
          .setMaxResults(1)
          .getResultList();
      if (latest.isEmpty()) continue;
      StreamSample sample = latest.get(0);
      boolean live = latestTimestamp.equals(sample.getTimestamp());
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("platform", sample.getPlatform());
      item.put("channel", sample.getChannel());
      item.put("channelId", sample.getChannelId());
      item.put("identity", channelIdentity(sample.getPlatform(), sample.getChannelId(), sample.getChannel()));
      item.put("url", sample.getUrl());
      item.put("live", live);
      item.put("viewers", live ? sample.getViewers() : null);
      item.put("lastSeen", sample.getTimestamp());
      status.add(item);
    }
    return status;
  }

  private String channelIdentity(String platform, String channelId, String channel) {
    String identity = channelId == null || channelId.isBlank()
        ? String.valueOf(channel).trim().toLowerCase(Locale.ROOT) : channelId;
    return String.valueOf(platform).toLowerCase(Locale.ROOT) + ":" + identity;
  }

  /** Channel history supports database-side time filtering and a hard result cap. */
  @GetMapping("/channels/history")
  public List<StreamSample> channelHistory(
      @RequestParam String name,
      @RequestParam(required = false) String platform,
      @RequestParam(required = false) OffsetDateTime from,
      @RequestParam(required = false) OffsetDateTime to,
      @RequestParam(defaultValue = "5000") int limit) {
    if (name.isBlank()) return List.of();
    if ((from == null) != (to == null)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both 'from' and 'to' must be supplied together");
    }
    if (from != null) validateRange(from, to);
    int safeLimit = clampLimit(limit);

    String query = "select s from StreamSample s where s.channel = :name ";
    if (platform != null && !platform.isBlank()) query += "and lower(s.platform) = lower(:platform) ";
    if (from != null) query += "and s.timestamp >= :from and s.timestamp < :to ";
    query += "order by s.timestamp desc, s.id desc";
    var typedQuery = entityManager.createQuery(query, StreamSample.class).setParameter("name", name);
    if (platform != null && !platform.isBlank()) typedQuery.setParameter("platform", platform.trim());
    if (from != null) typedQuery.setParameter("from", from).setParameter("to", to);
    List<StreamSample> history = new ArrayList<>(typedQuery.setMaxResults(safeLimit).getResultList());
    java.util.Collections.reverse(history);
    return history;
  }

  /** Compares a channel's selected window with the same window one calendar period earlier. */
  @GetMapping("/channels/comparison")
  public Map<String, Object> channelComparison(
      @RequestParam String name,
      @RequestParam(required = false) String platform,
      @RequestParam OffsetDateTime from,
      @RequestParam OffsetDateTime to,
      @RequestParam String period) {
    if (name.isBlank()) return Map.of();
    validateRange(from, to);
    OffsetDateTime previousFrom = shiftComparisonDate(from, period);
    OffsetDateTime previousTo = shiftComparisonDate(to, period);

    Map<String, Object> result = new LinkedHashMap<>();
    result.put("period", period);
    result.put("currentFrom", from);
    result.put("currentTo", to);
    result.put("previousFrom", previousFrom);
    result.put("previousTo", previousTo);
    result.put("current", summarizeChannelWindow(name, platform, from, to));
    result.put("previous", summarizeChannelWindow(name, platform, previousFrom, previousTo));
    return result;
  }

  private OffsetDateTime shiftComparisonDate(OffsetDateTime value, String period) {
    return switch (period.toLowerCase(Locale.ROOT)) {
      case "day" -> value.minusDays(1);
      case "week" -> value.minusWeeks(1);
      case "month" -> value.minusMonths(1);
      case "quarter" -> value.minusMonths(3);
      case "year" -> value.minusYears(1);
      default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "period must be day, week, month, quarter, or year");
    };
  }

  private Map<String, Object> summarizeChannelWindow(
      String name, String platform, OffsetDateTime from, OffsetDateTime to) {
    Map<Long, Long> minuteTotals = new HashMap<>();
    long lastId = 0L;
    final int pageSize = 5000;
    while (true) {
      String query = "select s from StreamSample s where s.channel = :name "
          + "and s.timestamp >= :from and s.timestamp < :to and s.id > :lastId ";
      if (platform != null && !platform.isBlank()) {
        query += "and lower(s.platform) = lower(:platform) ";
      }
      query += "order by s.id asc";
      var typedQuery = entityManager.createQuery(query, StreamSample.class)
          .setParameter("name", name)
          .setParameter("from", from)
          .setParameter("to", to)
          .setParameter("lastId", lastId)
          .setMaxResults(pageSize);
      if (platform != null && !platform.isBlank()) {
        typedQuery.setParameter("platform", platform.trim());
      }
      List<StreamSample> page = typedQuery.getResultList();
      if (page.isEmpty()) break;
      for (StreamSample sample : page) {
        long minute = sample.getTimestamp().toInstant().toEpochMilli() / 60000L;
        minuteTotals.merge(minute, sample.getViewers(), Long::sum);
        lastId = sample.getId();
      }
      if (page.size() < pageSize) break;
    }

    long peak = 0L;
    long total = 0L;
    long latestMinute = Long.MIN_VALUE;
    for (Map.Entry<Long, Long> entry : minuteTotals.entrySet()) {
      peak = Math.max(peak, entry.getValue());
      total += entry.getValue();
      latestMinute = Math.max(latestMinute, entry.getKey());
    }
    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("peakViewers", peak);
    summary.put("averageViewers", minuteTotals.isEmpty() ? 0L : Math.round((double) total / minuteTotals.size()));
    summary.put("latestViewers", minuteTotals.getOrDefault(latestMinute, 0L));
    summary.put("minutesWithData", minuteTotals.size());
    return summary;
  }

  /** Historical analytics for [from, to), processed in bounded keyset pages. */
  @GetMapping("/analytics")
  public Map<String, Object> analytics(
      @RequestParam OffsetDateTime from,
      @RequestParam OffsetDateTime to) {
    validateRange(from, to);
    // Keep each database read bounded, but process every sample in the selected range.
    // This avoids misleading growth rankings caused by applying one global row cap.
    final int pageSize = 5000;
    Map<String, Map<String, Object>> channels = new HashMap<>();
    Map<String, Map<String, Object>> platforms = new LinkedHashMap<>();
    for (String name : List.of("YouTube", "Twitch", "Kick")) {
      Map<String, Object> metric = new LinkedHashMap<>();
      metric.put("platform", name); metric.put("samples", 0L); metric.put("peakViewers", 0L);
      metric.put("viewerSamples", 0L); metric.put("viewerTotal", 0L);
      platforms.put(name, metric);
    }

    long overallPeak = 0L, overallViewerTotal = 0L, sampleCount = 0L;
    OffsetDateTime cursorTimestamp = null;
    Long cursorId = null;
    while (true) {
      // Analytics only needs these five fields. Avoid hydrating StreamSample entities,
      // especially their large title and URL columns, while scanning long ranges.
      var pageQuery = entityManager.createQuery(
          "select s.timestamp, s.id, s.platform, s.channelId, s.channel, s.viewers "
              + "from StreamSample s where s.timestamp >= :from and s.timestamp < :to "
              + (cursorTimestamp != null
                  ? "and (s.timestamp > :cursorTimestamp or (s.timestamp = :cursorTimestamp and s.id > :cursorId)) "
                  : "")
              + "order by s.timestamp asc, s.id asc", Object[].class)
          .setParameter("from", from).setParameter("to", to);
      if (cursorTimestamp != null) {
        pageQuery.setParameter("cursorTimestamp", cursorTimestamp).setParameter("cursorId", cursorId);
      }
      List<Object[]> page = pageQuery.setMaxResults(pageSize).getResultList();
      if (page.isEmpty()) break;

      for (Object[] row : page) {
        OffsetDateTime timestamp = (OffsetDateTime) row[0];
        String platform = row[2] == null ? "Unknown" : (String) row[2];
        String channelId = (String) row[3];
        String channelName = (String) row[4];
        long viewers = Math.max(0L, ((Number) row[5]).longValue());
        Map<String, Object> platformMetric = platforms.computeIfAbsent(platform, name -> {
          Map<String, Object> metric = new LinkedHashMap<>();
          metric.put("platform", name); metric.put("samples", 0L); metric.put("peakViewers", 0L);
          metric.put("viewerSamples", 0L); metric.put("viewerTotal", 0L); return metric;
        });
        platformMetric.put("samples", (Long) platformMetric.get("samples") + 1L);
        platformMetric.put("peakViewers", Math.max((Long) platformMetric.get("peakViewers"), viewers));
        platformMetric.put("viewerSamples", (Long) platformMetric.get("viewerSamples") + 1L);
        platformMetric.put("viewerTotal", (Long) platformMetric.get("viewerTotal") + viewers);
        overallPeak = Math.max(overallPeak, viewers); overallViewerTotal += viewers; sampleCount++;

        String identity = platform.toLowerCase(Locale.ROOT) + ":" +
            (channelId == null || channelId.isBlank()
                ? String.valueOf(channelName).trim().toLowerCase(Locale.ROOT)
                : channelId);
        Map<String, Object> channel = channels.computeIfAbsent(identity, key -> {
          Map<String, Object> metric = new LinkedHashMap<>();
          metric.put("platform", platform); metric.put("channel", channelName);
          metric.put("firstViewers", viewers);
          metric.put("lastViewers", viewers); metric.put("peakViewers", viewers);
          metric.put("viewerTotal", 0L); metric.put("samples", 0L);
          metric.put("firstTimestamp", timestamp); metric.put("lastTimestamp", timestamp);
          metric.put("peakTimestamp", timestamp);
          return metric;
        });
        channel.put("lastViewers", viewers); channel.put("lastTimestamp", timestamp);
        if (viewers > (Long) channel.get("peakViewers")) {
          channel.put("peakViewers", viewers);
          channel.put("peakTimestamp", timestamp);
        }
        channel.put("viewerTotal", (Long) channel.get("viewerTotal") + viewers);
        channel.put("samples", (Long) channel.get("samples") + 1L);
      }
      Object[] last = page.get(page.size() - 1);
      cursorTimestamp = (OffsetDateTime) last[0];
      cursorId = (Long) last[1];
      if (page.size() < pageSize) break;
    }

    List<Map<String, Object>> fastestGrowing = new ArrayList<>(channels.values());
    for (Map<String, Object> channel : fastestGrowing) {
      long first = (Long) channel.get("firstViewers"), last = (Long) channel.get("lastViewers");
      channel.put("growthViewers", last - first);
      channel.put("growthPercent", first > 0 ? ((last - first) * 100.0 / first) : null);
      channel.put("averageViewers", (Long) channel.get("samples") == 0
          ? 0.0 : ((Long) channel.get("viewerTotal")).doubleValue() / (Long) channel.get("samples"));
      channel.remove("viewerTotal");
    }
    fastestGrowing.sort(Comparator.comparingLong((Map<String, Object> c) -> (Long)c.get("growthViewers")).reversed());
    if (fastestGrowing.size() > 10) fastestGrowing = new ArrayList<>(fastestGrowing.subList(0, 10));

    List<Map<String, Object>> topChannelsByPeak = new ArrayList<>(channels.values());
    topChannelsByPeak.sort(Comparator.comparingLong((Map<String, Object> c) -> (Long)c.get("peakViewers")).reversed());
    if (topChannelsByPeak.size() > 10) topChannelsByPeak = new ArrayList<>(topChannelsByPeak.subList(0, 10));

    Map<String, Map<String, Object>> topIndividualByPlatform = new LinkedHashMap<>();
    for (Map<String, Object> channel : channels.values()) {
      String platform = String.valueOf(channel.get("platform"));
      Map<String, Object> current = topIndividualByPlatform.get(platform);
      if (current == null || (Long) channel.get("peakViewers") > (Long) current.get("peakViewers")) {
        topIndividualByPlatform.put(platform, channel);
      }
    }

    for (Map<String, Object> metric : platforms.values()) {
      long count = (Long) metric.get("viewerSamples"), total = (Long) metric.get("viewerTotal");
      metric.put("averageViewers", count == 0 ? 0.0 : (double) total / count);
      metric.remove("viewerSamples"); metric.remove("viewerTotal");
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("from", from); result.put("to", to); result.put("samplesReturned", sampleCount);
    result.put("truncated", false); result.put("analyticsComplete", true);
    result.put("growthRankingsComplete", true);
    result.put("overallPeakViewers", overallPeak);
    result.put("overallAverageViewers", sampleCount == 0 ? 0.0 : (double) overallViewerTotal / sampleCount);
    result.put("platforms", platforms.values()); result.put("fastestGrowingChannels", fastestGrowing);
    result.put("topChannelsByPeak", topChannelsByPeak);
    result.put("topIndividualStreamsByPlatform", topIndividualByPlatform);
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
