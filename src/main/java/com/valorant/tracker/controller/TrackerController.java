package com.valorant.tracker.controller;

import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.model.StreamSample;
import com.valorant.tracker.service.TrackerService;
import jakarta.persistence.EntityManager;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class TrackerController {

  private final EntityManager entityManager;
  private final TrackerService tracker;

  public TrackerController(EntityManager entityManager, TrackerService tracker) {
    this.entityManager = entityManager;
    this.tracker = tracker;
  }

  @GetMapping("/status")
  public Map<String, Object> status() {
    return Map.of("running", true, "lastRun", tracker.getLastRun() == null
        ? "not-run"
        : tracker.getLastRun());
  }

  @PostMapping("/collect")
  public Map<String, Object> collect() {
    tracker.collect();
    return Map.of("status", "collected", "lastRun", tracker.getLastRun());
  }

  @GetMapping("/snapshots")
  public List<Snapshot> snapshots() {
    return entityManager
        .createQuery("select s from Snapshot s order by s.timestamp desc", Snapshot.class)
        .setMaxResults(500)
        .getResultList();
  }

  @GetMapping("/streams")
  public List<StreamSample> streams(
      @RequestParam(defaultValue = "Overall") String platform) {
    OffsetDateTime latestTimestamp =
        entityManager
            .createQuery("select max(s.timestamp) from StreamSample s", OffsetDateTime.class)
            .getSingleResult();
    if (latestTimestamp == null) {
      return List.of();
    }

    String query =
        platform.equalsIgnoreCase("Overall")
            ? "select s from StreamSample s where s.timestamp = :timestamp "
                + "order by s.viewers desc, s.id desc"
            : "select s from StreamSample s where s.timestamp = :timestamp "
                + "and lower(s.platform) = lower(:platform) "
                + "order by s.viewers desc, s.id desc";
    var streams =
        entityManager
            .createQuery(query, StreamSample.class)
            .setParameter("timestamp", latestTimestamp);
    if (!platform.equalsIgnoreCase("Overall")) {
      streams.setParameter("platform", platform);
    }
    List<StreamSample> results = streams.getResultList();
    Map<String, StreamSample> uniqueChannels = new LinkedHashMap<>();
    for (StreamSample stream : results) {
      String channelKey =
          stream.getPlatform().toLowerCase()
              + ":"
              + (stream.getChannelId().isBlank()
                  ? stream.getChannel().trim().toLowerCase()
                  : stream.getChannelId());
      uniqueChannels.putIfAbsent(channelKey, stream);
    }
    return List.copyOf(uniqueChannels.values());
  }

  @GetMapping("/channels/history")
  public List<StreamSample> channelHistory(@RequestParam String name) {
    if (name.isBlank()) {
      return List.of();
    }

    List<StreamSample> history =
        entityManager
            .createQuery(
                "select s from StreamSample s where lower(trim(s.channel)) = lower(trim(:name)) "
                    + "order by s.timestamp desc, s.id desc",
                StreamSample.class)
            .setParameter("name", name)
            .getResultList();
    Collections.reverse(history);
    return history;
  }

  @GetMapping("/top10")
  public List<StreamSample> top(
      @RequestParam(defaultValue = "Overall") String platform) {
    return streams(platform).stream().limit(10).toList();
  }
}
