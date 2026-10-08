package com.valorant.tracker.controller;

import com.valorant.tracker.constant.GameConfig;
import com.valorant.tracker.model.Snapshot;
import com.valorant.tracker.model.StreamSample;
import com.valorant.tracker.model.EsportsMatchRecord;
import com.valorant.tracker.repository.EsportsMatchRecordRepository;
import com.valorant.tracker.service.misc.DynamicGameScheduler;
import com.valorant.tracker.service.misc.LiquipediaEsportsScheduleScraper;
import com.valorant.tracker.service.tracker.TrackerService;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.OffsetDateTime;
import java.util.*;

@RestController
@RequestMapping("/api")
public class TrackerController {
  private static final int MAX_HISTORY_LIMIT = 20000;

  private final EntityManager entityManager;
  private final TrackerService tracker;
  private final DynamicGameScheduler dynamicGameScheduler;
  private final EsportsMatchRecordRepository matchRepository;
  private final LiquipediaEsportsScheduleScraper liquipediaEsportsScheduleScraper;

  public TrackerController(EntityManager entityManager, TrackerService tracker, DynamicGameScheduler dynamicGameScheduler, EsportsMatchRecordRepository matchRepository, LiquipediaEsportsScheduleScraper liquipediaEsportsScheduleScraper) {
    this.entityManager = entityManager;
    this.tracker = tracker;
    this.dynamicGameScheduler = dynamicGameScheduler;
    this.matchRepository = matchRepository;
      this.liquipediaEsportsScheduleScraper = liquipediaEsportsScheduleScraper;
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
    tracker.collectAllGames();
    return Map.of("status", "collected", "lastRun", tracker.getLastRun());
  }

  /** Recent snapshots, kept for compatibility with the existing dashboard. */
  @GetMapping("/snapshots")
  public List<Snapshot> snapshots(@RequestParam(defaultValue = "VALORANT") String game) {
    GameConfig config = GameConfig.from(game);
    return entityManager.createQuery(
                    "select s from Snapshot s where (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT')) order by s.timestamp desc, s.id desc", Snapshot.class)
            .setParameter("game", config.getId())
            .setMaxResults(500).getResultList();
  }

  /** Bounded, indexed history query for chart ranges. The upper timestamp is exclusive. */
  @GetMapping("/snapshots/range")
  public List<Snapshot> snapshotsRange(
          @RequestParam OffsetDateTime from,
          @RequestParam OffsetDateTime to,
          @RequestParam(defaultValue = "VALORANT") String game,
          @RequestParam(defaultValue = "1000") int limit) {

    validateRange(from, to);

    int safeLimit = Math.clamp(limit, 1, 5000);
    GameConfig config = GameConfig.from(game);

    return entityManager.createQuery(
                    "select s from Snapshot s " +
                            "where s.timestamp >= :from " +
                            "and s.timestamp < :to " +
                            "and (lower(s.game) = lower(:game) " +
                            "or (s.game is null and :game = 'VALORANT')) " +
                            "order by s.timestamp asc, s.id asc",
                    Snapshot.class)
            .setParameter("from", from)
            .setParameter("to", to)
            .setParameter("game", config.getId())
            .setMaxResults(safeLimit)
            .getResultList();
  }

  @GetMapping("/games")
  public List<Map<String, Object>> games() {

    List<Map<String, Object>> result = new ArrayList<>();

    for (GameConfig game : GameConfig.values()) {
      String gameId = game.getId();
      boolean scheduledActive = isSchedulerActive(game);
      Map<String, Object> item = new LinkedHashMap<>();

      item.put("id", gameId);
      item.put("name", game.getDisplayName());

      // True = Liquipedia says an S/A-tier esports match
      // is currently active.
      item.put("active", scheduledActive);
      List<LiquipediaEsportsScheduleScraper.EsportsMatch> activeMatches =
              dynamicGameScheduler.getActiveMatches(game.getDisplayName());
      item.put("activeMatches", activeMatches);
      if (!activeMatches.isEmpty()) {
        // Keep the singular field for existing clients while exposing the complete list.
        item.put("activeMatch", activeMatches.get(0));
      }

      // Never expose stale database data as CURRENT
      // data when the esports game is not scheduled.
      if (!scheduledActive) {

        item.put("liveStreams", 0L);
        item.put("totalViewers", 0L);
        item.put("youtubeStreams", 0L);
        item.put("twitchStreams", 0L);
        item.put("kickStreams", 0L);
        item.put("youtubeViewers", 0L);
        item.put("twitchViewers", 0L);
        item.put("kickViewers", 0L);

      } else {

        OffsetDateTime liveFrom = activeMatches.stream()
                .map(LiquipediaEsportsScheduleScraper.EsportsMatch::startTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(OffsetDateTime.now().minusMinutes(1));

        List<StreamSample> samples = entityManager.createQuery(
                        "select s from StreamSample s " +
                                "where lower(s.game) = lower(:game) " +
                                "and s.timestamp >= :liveFrom " +
                                "and s.timestamp = (" +
                                "select max(x.timestamp) from StreamSample x " +
                                "where lower(x.game) = lower(:game) " +
                                "and x.timestamp >= :liveFrom" +
                                ")",
                        StreamSample.class)
                .setParameter("game", gameId)
                .setParameter("liveFrom", liveFrom)
                .getResultList();

        item.put("liveStreams", (long) samples.size());

        item.put(
                "totalViewers",
                samples.stream()
                        .mapToLong(StreamSample::getViewers)
                        .sum()
        );

        item.put(
                "youtubeStreams",
                samples.stream()
                        .filter(s ->
                                "YouTube".equalsIgnoreCase(
                                        s.getPlatform()))
                        .count()
        );

        item.put(
                "twitchStreams",
                samples.stream()
                        .filter(s ->
                                "Twitch".equalsIgnoreCase(
                                        s.getPlatform()))
                        .count()
        );

        item.put(
                "kickStreams",
                samples.stream()
                        .filter(s ->
                                "Kick".equalsIgnoreCase(
                                        s.getPlatform()))
                        .count()
        );

        item.put("youtubeViewers", samples.stream()
                .filter(s -> "YouTube".equalsIgnoreCase(s.getPlatform()))
                .mapToLong(StreamSample::getViewers).sum());
        item.put("twitchViewers", samples.stream()
                .filter(s -> "Twitch".equalsIgnoreCase(s.getPlatform()))
                .mapToLong(StreamSample::getViewers).sum());
        item.put("kickViewers", samples.stream()
                .filter(s -> "Kick".equalsIgnoreCase(s.getPlatform()))
                .mapToLong(StreamSample::getViewers).sum());

        samples.stream()
                .max(Comparator.comparingLong(
                        StreamSample::getViewers))
                .ifPresent(top -> {
                  item.put("topChannel", top.getChannel());
                  item.put("topViewers", top.getViewers());
                  item.put("topPlatform", top.getPlatform());
                  item.put("topMatchId", top.getMatchId());
                });
      }

      result.add(item);
    }

    result.sort(
            Comparator.comparingLong(
                    (Map<String, Object> x) ->
                            ((Number) x.get("totalViewers")).longValue()
            ).reversed()
    );

    return result;
  }

  @GetMapping("/streams")
  public List<Map<String, Object>> streams(
          @RequestParam(defaultValue = "VALORANT") String game,
          @RequestParam(defaultValue = "Overall") String platform) {

    GameConfig config = GameConfig.from(game);

    // Live-stream endpoints must never expose the last historical sample when
    // the esports scheduler says the game is currently offline. Historical
    // data remains available through /api/snapshots, /api/snapshots/range,
    // /api/channels/history, and the analytics endpoints.
    if (!isSchedulerActive(config)) {
      return List.of();
    }

    List<LiquipediaEsportsScheduleScraper.EsportsMatch> activeMatches =
            dynamicGameScheduler.getActiveMatches(config.getDisplayName());

    OffsetDateTime liveFrom = activeMatches.stream()
            .map(LiquipediaEsportsScheduleScraper.EsportsMatch::startTime)
            .filter(Objects::nonNull)
            .min(Comparator.naturalOrder())
            .orElse(OffsetDateTime.now().minusMinutes(1));

    OffsetDateTime latestTimestamp = entityManager.createQuery(
                    "select max(s.timestamp) from StreamSample s " +
                            "where (lower(s.game) = lower(:game) " +
                            "or (s.game is null and :game = 'VALORANT')) " +
                            "and s.timestamp >= :liveFrom",
                    OffsetDateTime.class)
            .setParameter("game", config.getId())
            .setParameter("liveFrom", liveFrom)
            .getSingleResult();

    if (latestTimestamp == null) {
      return List.of();
    }

    String query;

    if (platform.equalsIgnoreCase("Overall")) {
      query = "select s from StreamSample s " +
              "where s.timestamp = :timestamp " +
              "and (lower(s.game) = lower(:game) " +
              "or (s.game is null and :game = 'VALORANT')) " +
              "order by s.viewers desc, s.id desc";
    } else {
      query = "select s from StreamSample s " +
              "where s.timestamp = :timestamp " +
              "and (lower(s.game) = lower(:game) " +
              "or (s.game is null and :game = 'VALORANT')) " +
              "and lower(s.platform) = lower(:platform) " +
              "order by s.viewers desc, s.id desc";
    }

    var streams = entityManager.createQuery(query, StreamSample.class)
            .setParameter("timestamp", latestTimestamp)
            .setParameter("game", config.getId());

    if (!platform.equalsIgnoreCase("Overall")) {
      streams.setParameter("platform", platform);
    }

    List<StreamSample> results = streams.getResultList();

    /*
     * A channel may have multiple simultaneous live videos.
     *
     * Keep one row per actual stream/video identity, NOT one row per channel.
     *
     * Example:
     *
     * ESL Counter-Strike
     *   ├── Video A → FURIA vs Aurora
     *   └── Video B → Legacy vs M80
     *
     * Their stream/video IDs are different and must remain
     * independent rows.
     */
    Map<String, StreamSample> uniqueStreams = new LinkedHashMap<>();

    for (StreamSample stream : results) {

      // IMPORTANT:
      // "platform" is already the @RequestParam above.
      // Use a different variable name for the StreamSample platform.
      String streamPlatform = stream.getPlatform() == null
              ? ""
              : stream.getPlatform().trim().toLowerCase(Locale.ROOT);

      String streamId = stream.getStreamId() == null
              ? ""
              : stream.getStreamId().trim();

      String channelId = stream.getChannelId() == null
              ? ""
              : stream.getChannelId().trim();

      String channel = stream.getChannel() == null
              ? ""
              : stream.getChannel().trim();

      /*
       * Prefer the actual stream/video ID.
       *
       * This is critical for YouTube because the same channel can
       * broadcast multiple live videos simultaneously.
       */
      String identity;

      if (!streamId.isBlank()) {
        identity = "stream:"
                + streamPlatform
                + ":"
                + streamId;
      } else if (!channelId.isBlank()) {
        identity = "channel:"
                + streamPlatform
                + ":"
                + channelId;
      } else {
        identity = "name:"
                + streamPlatform
                + ":"
                + channel.toLowerCase(Locale.ROOT);
      }

      StreamSample existing = uniqueStreams.get(identity);

      if (existing == null) {
        uniqueStreams.put(identity, stream);
      } else {

        /*
         * If the same stream/video has been collected more than once
         * at the same timestamp, prefer the row that contains a title.
         */
        String existingTitle = existing.getTitle() == null
                ? ""
                : existing.getTitle().trim();

        String currentTitle = stream.getTitle() == null
                ? ""
                : stream.getTitle().trim();

        if (existingTitle.isBlank() && !currentTitle.isBlank()) {
          uniqueStreams.put(identity, stream);
        }
      }
    }

    /*
     * Hydrate missing titles using the SAME stream/video ID first.
     *
     * Never fall back from one video on a channel to another video's title.
     */
    Map<String, String> titleFallbacks = new HashMap<>();

    for (StreamSample stream : uniqueStreams.values()) {

      String title = stream.getTitle() == null
              ? ""
              : stream.getTitle().trim();

      if (!title.isBlank()) {
        continue;
      }

      String fallback = null;

      if (stream.getStreamId() != null
              && !stream.getStreamId().isBlank()) {

        List<String> titles = entityManager.createQuery(
                        "select s.title from StreamSample s " +
                                "where lower(s.platform) = lower(:platform) " +
                                "and s.streamId = :streamId " +
                                "and s.title is not null " +
                                "and trim(s.title) <> '' " +
                                "order by s.timestamp desc, s.id desc",
                        String.class)
                .setParameter("platform", stream.getPlatform())
                .setParameter("streamId", stream.getStreamId())
                .setMaxResults(1)
                .getResultList();

        if (!titles.isEmpty()) {
          fallback = titles.get(0);
        }
      }

      if (fallback != null && !fallback.isBlank()) {
        titleFallbacks.put(
                String.valueOf(stream.getId()),
                fallback
        );
      }
    }

    /*
     * Explicit response mapping.
     *
     * Guarantees that "title" is present in /api/streams.
     */
    List<Map<String, Object>> response = new ArrayList<>();

    for (StreamSample stream : uniqueStreams.values()) {

      Map<String, Object> item = new LinkedHashMap<>();

      item.put("id", stream.getId());
      item.put("streamId", stream.getStreamId());
      item.put("matchId", stream.getMatchId());

      item.put("platform", stream.getPlatform());

      item.put("channelId", stream.getChannelId());
      item.put("channel", stream.getChannel());
      item.put("channelTitle", stream.getChannel());

      String responseTitle = stream.getTitle() == null
              ? ""
              : stream.getTitle().trim();

      if (responseTitle.isBlank()) {
        responseTitle = titleFallbacks.getOrDefault(
                String.valueOf(stream.getId()),
                ""
        );
      }

      item.put("title", responseTitle);

      item.put("viewers", stream.getViewers());
      item.put("url", stream.getUrl());
      item.put("timestamp", stream.getTimestamp());

      response.add(item);
    }

    return response;
  }

  /** Returns all currently active Liquipedia matches for a game. */
  @GetMapping("/esports/active-matches")
  public Map<String, Object> activeMatches(
          @RequestParam(defaultValue = "VALORANT") String game) {
    GameConfig config = GameConfig.from(game);
    List<LiquipediaEsportsScheduleScraper.EsportsMatch> matches =
            dynamicGameScheduler.getActiveMatches(config.getDisplayName());
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("active", !matches.isEmpty());
    result.put("matches", matches);
    return result;
  }

  /**
   * Search live and historical esports matches by token.
   *
   * Examples:
   *   q=9z vs BB  -> 9z vs BB
   *   q=9z        -> every match involving 9z
   *   q=BB        -> every match involving BB
   *   q=ESL Pro League -> matches from that tournament
   *
   * Upcoming-only matches are intentionally excluded; the search is for
   * currently live or completed matches.
   */
  @GetMapping("/esports/matches/search")
  public List<Map<String, Object>> searchEsportsMatches(
          @RequestParam String q,
          @RequestParam(defaultValue = "VALORANT") String game,
          @RequestParam(defaultValue = "30") int limit) {

    String query = q == null ? "" : q.trim();
    if (query.isBlank()) return List.of();

    GameConfig config = GameConfig.from(game);
    OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    List<String> tokens = Arrays.stream(normalizeSearchText(query).split("\\s+"))
            .filter(token -> !token.isBlank() && !token.equals("vs") && !token.equals("v"))
            .distinct()
            .toList();

    if (tokens.isEmpty()) return List.of();

    List<EsportsMatchRecord> records =
            matchRepository.findByGameIgnoreCaseOrderByStartTimeDesc(config.getDisplayName());

    Set<String> activeIds = dynamicGameScheduler.getActiveMatches(config.getDisplayName()).stream()
            .map(LiquipediaEsportsScheduleScraper.EsportsMatch::matchId)
            .collect(java.util.stream.Collectors.toSet());

    List<Map<String, Object>> scored = new ArrayList<>();

    for (EsportsMatchRecord match : records) {
      if (match.getStartTime() == null) {
        continue;
      }

      String team1 = normalizeSearchText(match.getTeam1());
      String team2 = normalizeSearchText(match.getTeam2());
      String tournament = normalizeSearchText(match.getTournament());
      String combined = String.join(" ",
              team1, team2, tournament,
              normalizeSearchText(match.getMatchId()),
              normalizeSearchText(match.getGame()));

      boolean allTokensFound = tokens.stream().allMatch(combined::contains);
      if (!allTokensFound) continue;

      int score = 0;
      for (String token : tokens) {
        boolean team1Exact = team1.equals(token);
        boolean team2Exact = team2.equals(token);
        boolean teamContains = team1.contains(token) || team2.contains(token);
        boolean tournamentContains = tournament.contains(token);

        if (team1Exact || team2Exact) score += 100;
        else if (teamContains) score += 70;
        else if (tournamentContains) score += 35;
        else score += 10;
      }

      String queryNormalized = normalizeSearchText(query);
      String teamsForward = (team1 + " vs " + team2).trim();
      String teamsReverse = (team2 + " vs " + team1).trim();
      if (queryNormalized.equals(teamsForward) || queryNormalized.equals(teamsReverse)) {
        score += 150;
      }

      String effectiveStatus = effectiveMatchStatus(match, now, activeIds.contains(match.getMatchId()));
      boolean live = "LIVE".equals(effectiveStatus);
      if (live) score += 500;

      Map<String, Object> item = new LinkedHashMap<>();
      item.put("matchId", match.getMatchId());
      item.put("game", match.getGame());
      item.put("tournament", match.getTournament());
      item.put("tier", match.getTier());
      item.put("team1", match.getTeam1());
      item.put("team2", match.getTeam2());
      item.put("team1Score", match.getTeam1Score());
      item.put("team2Score", match.getTeam2Score());
      item.put("bestOf", match.getBestOf());
      item.put("winner", match.getWinner());
      item.put("finished", match.isFinished());
      item.put("startTime", match.getStartTime());
      item.put("endTime", match.getEndTime());
      item.put("status", effectiveStatus);
      item.put("active", live);
      item.put("matchUrl", match.getMatchUrl());
      item.put("tournamentUrl", match.getTournamentUrl());
      item.put("score", score);
      scored.add(item);
    }

    scored.sort(Comparator
            .comparing((Map<String, Object> item) -> Boolean.TRUE.equals(item.get("active")))
            .reversed()
            .thenComparing(item -> ((Number) item.get("score")).intValue(), Comparator.reverseOrder())
            .thenComparing(item -> String.valueOf(item.get("startTime")), Comparator.reverseOrder()));

    int safeLimit = Math.clamp(limit, 1, 50);
    return scored.stream().limit(safeLimit).toList();
  }

  /** Match catalog endpoint used by search/history clients. */
  @GetMapping("/esports/matches")
  public List<Map<String, Object>> esportsMatches(
          @RequestParam(defaultValue = "VALORANT") String game,
          @RequestParam(defaultValue = "all") String status,
          @RequestParam(defaultValue = "50") int limit) {

    GameConfig config = GameConfig.from(game);
    OffsetDateTime now = OffsetDateTime.now(java.time.ZoneOffset.UTC);
    Set<String> activeIds = dynamicGameScheduler.getActiveMatches(config.getDisplayName()).stream()
            .map(LiquipediaEsportsScheduleScraper.EsportsMatch::matchId)
            .collect(java.util.stream.Collectors.toSet());

    int safeLimit = Math.clamp(limit, 1, 200);
    List<EsportsMatchRecord> records =
            matchRepository.findByGameIgnoreCaseOrderByStartTimeDesc(config.getDisplayName());

    // The game dashboard is a TODAY schedule, not the complete historical catalog.
    // Keep historical matches available through the search/history endpoints, but
    // /api/esports/matches should only expose today's live/upcoming/completed matches.
    java.time.LocalDate today = now.toLocalDate();

    List<Map<String, Object>> response = new ArrayList<>();
    for (EsportsMatchRecord match : records) {
      if (match.getStartTime() == null) continue;

      String effectiveStatus = effectiveMatchStatus(match, now, activeIds.contains(match.getMatchId()));
      boolean live = "LIVE".equals(effectiveStatus);
      boolean upcoming = "UPCOMING".equals(effectiveStatus);
      boolean completed = "COMPLETED".equals(effectiveStatus);

      // Ignore old UNKNOWN rows and all matches from previous calendar days.
      // A live match is retained even if its kickoff was yesterday because it
      // is still current; otherwise only today's scheduled/completed matches
      // belong in this dashboard list.
      boolean todayMatch = live
              || match.getStartTime().toLocalDate().equals(today)
              || (completed && match.getEndTime() != null
              && match.getEndTime().toLocalDate().equals(today));

      if (!todayMatch) continue;
      if ("live".equalsIgnoreCase(status) && !live) continue;
      if ("upcoming".equalsIgnoreCase(status) && !upcoming) continue;
      if ("completed".equalsIgnoreCase(status) && !completed) continue;
      if ("history".equalsIgnoreCase(status) && (live || upcoming)) continue;

      Map<String, Object> item = new LinkedHashMap<>();
      item.put("matchId", match.getMatchId());
      item.put("game", match.getGame());
      item.put("tournament", match.getTournament());
      item.put("tier", match.getTier());
      item.put("team1", match.getTeam1());
      item.put("team2", match.getTeam2());
      item.put("team1Score", match.getTeam1Score());
      item.put("team2Score", match.getTeam2Score());
      item.put("bestOf", match.getBestOf());
      item.put("winner", match.getWinner());
      item.put("finished", match.isFinished());
      item.put("startTime", match.getStartTime());
      item.put("endTime", match.getEndTime());
      item.put("status", effectiveStatus);
      item.put("active", live);
      item.put("matchUrl", match.getMatchUrl());
      item.put("tournamentUrl", match.getTournamentUrl());
      response.add(item);
      if (response.size() >= safeLimit) break;
    }
    return response;
  }

  /**
   * Derive the externally visible match phase from the freshest scheduler state
   * plus persisted match metadata. This prevents old database rows from staying
   * UPCOMING forever when Liquipedia has already moved them into the past.
   *
   * Rules:
   * - scheduler says active -> LIVE
   * - persisted finished flag or explicit COMPLETED status -> COMPLETED
   * - future kickoff -> UPCOMING
   * - explicitly persisted LIVE state -> LIVE
   * - stale past records are not treated as current LIVE
   */
  private String effectiveMatchStatus(
          EsportsMatchRecord match,
          OffsetDateTime now,
          boolean schedulerActive) {

    // Fresh scheduler state has highest priority.
    if (schedulerActive) {
      return "LIVE";
    }

    // Explicit completion evidence is authoritative.
    if (match.isFinished()
            || "COMPLETED".equalsIgnoreCase(match.getStatus())) {
      return "COMPLETED";
    }

    OffsetDateTime start = match.getStartTime();
    if (start == null) {
      return "UNKNOWN";
    }

    if (start.isAfter(now)) {
      return "UPCOMING";
    }

    // Preserve an explicitly persisted LIVE state. There is deliberately
    // no four-hour cutoff: a genuinely long-running match can cross midnight
    // or run for more than four hours.
    if ("LIVE".equalsIgnoreCase(match.getStatus())) {
      return "LIVE";
    }

    // An old UPCOMING value is stale once kickoff has passed. Do not leave
    // historical rows labelled UPCOMING forever.
    if ("UPCOMING".equalsIgnoreCase(match.getStatus())) {
      return "COMPLETED";
    }

    return "UNKNOWN";
  }

  private String normalizeSearchText(String value) {
    if (value == null) return "";
    return java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKD)
            .replaceAll("\\p{M}", "")
            .toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", " ")
            .trim()
            .replaceAll("\\s+", " ");
  }

  /** Backward-compatible endpoint returning the first active match, if one exists. */
  @GetMapping("/esports/active-match")
  public Map<String, Object> activeMatch(
          @RequestParam(defaultValue = "VALORANT") String game) {
    GameConfig config = GameConfig.from(game);
    return dynamicGameScheduler.getActiveMatch(config.getDisplayName())
            .map(match -> {
              Map<String, Object> result = new LinkedHashMap<>();
              result.put("active", true);
              result.put("match", match);
              return result;
            })
            .orElseGet(() -> Map.of("active", false, "matches", List.of()));
  }

  /** Returns persisted stream samples associated with one esports match. */
  @GetMapping("/match-streams")
  public List<Map<String, Object>> matchStreams(
          @RequestParam String matchId,
          @RequestParam(defaultValue = "VALORANT") String game) {
    GameConfig config = GameConfig.from(game);
    List<StreamSample> samples = entityManager.createQuery(
                    "select s from StreamSample s " +
                            "where s.matchId = :matchId " +
                            "and (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT')) " +
                            "order by s.timestamp desc, s.viewers desc, s.id desc",
                    StreamSample.class)
            .setParameter("matchId", matchId)
            .setParameter("game", config.getId())
            .setMaxResults(1000)
            .getResultList();
    List<Map<String, Object>> response = new ArrayList<>();
    for (StreamSample sample : samples) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("id", sample.getId());
      item.put("matchId", sample.getMatchId());
      item.put("platform", sample.getPlatform());
      item.put("streamId", sample.getStreamId());
      item.put("channelId", sample.getChannelId());
      item.put("channel", sample.getChannel());
      item.put("title", sample.getTitle());
      item.put("viewers", sample.getViewers());
      item.put("url", sample.getUrl());
      item.put("timestamp", sample.getTimestamp());
      response.add(item);
    }
    return response;
  }

  /** Detailed match dashboard data, including persisted metadata and match-specific stream analytics. */
  @GetMapping("/esports/matches/{matchId}")
  public Map<String, Object> esportsMatchDetails(
          @PathVariable String matchId,
          @RequestParam(defaultValue = "VALORANT") String game) {
    GameConfig config = GameConfig.from(game);
    EsportsMatchRecord match = matchRepository.findById(matchId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Match not found: " + matchId));

    // Read both the normalized ID and the legacy Liquipedia-URL ID so
    // historical StreamSamples collected by older builds are not lost.
    String legacyMatchId = match.getMatchUrl();
    String sampleQuery = "select s from StreamSample s where " +
            "(s.matchId = :matchId " +
            (legacyMatchId != null && !legacyMatchId.isBlank() ? "or s.matchId = :legacyMatchId " : "") +
            ") " +
            "and (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT')) " +
            "order by s.timestamp asc, s.id asc";

    var sampleTypedQuery = entityManager.createQuery(sampleQuery, StreamSample.class)
            .setParameter("matchId", matchId)
            .setParameter("game", config.getId());
    if (legacyMatchId != null && !legacyMatchId.isBlank()) {
      sampleTypedQuery.setParameter("legacyMatchId", legacyMatchId);
    }
    List<StreamSample> samples = sampleTypedQuery
            .setMaxResults(20000)
            .getResultList();

    Map<String, List<StreamSample>> byTimestamp = new LinkedHashMap<>();
    for (StreamSample sample : samples) {
      byTimestamp.computeIfAbsent(sample.getTimestamp().toString(), k -> new ArrayList<>()).add(sample);
    }

    List<Map<String, Object>> timeline = new ArrayList<>();
    long peakCombined = 0;
    long combinedSum = 0;
    long combinedPoints = 0;
    Map<String, Long> platformPeaks = new LinkedHashMap<>();
    Map<String, Set<String>> platformChannels = new LinkedHashMap<>();
    Map<String, Long> latestPlatform = new LinkedHashMap<>();
    OffsetDateTime latestSampleTimestamp = null;

    for (var entry : byTimestamp.entrySet()) {
      Map<String, Long> platformValues = new LinkedHashMap<>();
      for (StreamSample sample : entry.getValue()) {
        String platform = sample.getPlatform() == null ? "Unknown" : sample.getPlatform();
        platformValues.merge(platform, sample.getViewers(), Long::sum);
        platformChannels.computeIfAbsent(platform, k -> new LinkedHashSet<>())
                .add(sample.getChannel() == null ? "" : sample.getChannel());
      }
      long combined = platformValues.values().stream().mapToLong(Long::longValue).sum();
      peakCombined = Math.max(peakCombined, combined);
      combinedSum += combined;
      combinedPoints++;
      for (var p : platformValues.entrySet()) {
        platformPeaks.merge(p.getKey(), p.getValue(), Math::max);
      }

      // Keep the latest observation independently for each platform. Do not
      // overwrite the whole latest map at every timestamp because YouTube,
      // Twitch and Kick are not necessarily sampled at exactly the same time.
      OffsetDateTime pointTimestamp = OffsetDateTime.parse(entry.getKey());
      if (latestSampleTimestamp == null || pointTimestamp.isAfter(latestSampleTimestamp)) {
        latestSampleTimestamp = pointTimestamp;
      }
      for (var p : platformValues.entrySet()) {
        latestPlatform.put(p.getKey(), p.getValue());
      }
      Map<String, Object> point = new LinkedHashMap<>();
      point.put("timestamp", entry.getKey());
      point.put("youtube", platformValues.getOrDefault("YouTube", 0L));
      point.put("twitch", platformValues.getOrDefault("Twitch", 0L));
      point.put("kick", platformValues.getOrDefault("Kick", 0L));
      point.put("combined", combined);
      timeline.add(point);
    }

    Map<String, Object> latest = new LinkedHashMap<>();
    latest.put("youtube", latestPlatform.getOrDefault("YouTube", 0L));
    latest.put("twitch", latestPlatform.getOrDefault("Twitch", 0L));
    latest.put("kick", latestPlatform.getOrDefault("Kick", 0L));
    latest.put("combined", latestPlatform.entrySet().stream()
            .filter(e -> !e.getKey().equalsIgnoreCase("Unknown"))
            .mapToLong(Map.Entry::getValue).sum());

    Map<String, Object> peak = new LinkedHashMap<>();
    peak.put("youtube", platformPeaks.getOrDefault("YouTube", 0L));
    peak.put("twitch", platformPeaks.getOrDefault("Twitch", 0L));
    peak.put("kick", platformPeaks.getOrDefault("Kick", 0L));
    peak.put("combined", peakCombined);

    Map<String, Object> summary = new LinkedHashMap<>();
    summary.put("peakCombined", peakCombined);
    summary.put("averageCombined", combinedPoints == 0 ? 0 : Math.round((double) combinedSum / combinedPoints));
    summary.put("samples", samples.size());
    summary.put("timelinePoints", timeline.size());
    summary.put("latest", latest);
    summary.put("peak", peak);
    summary.put("peakByPlatform", platformPeaks);
    summary.put("channelsByPlatform", platformChannels.entrySet().stream()
            .collect(LinkedHashMap::new, (m, e) -> m.put(e.getKey(), e.getValue().size()), Map::putAll));

    List<Map<String, Object>> streamRows = new ArrayList<>();
    Map<String, StreamSample> latestByStream = new LinkedHashMap<>();
    for (StreamSample sample : samples) {
      String key = (sample.getPlatform() == null ? "" : sample.getPlatform()) + "|" +
              (sample.getStreamId() == null ? sample.getChannel() : sample.getStreamId());
      latestByStream.put(key, sample);
    }
    latestByStream.values().stream()
            .sorted(Comparator.comparingLong(StreamSample::getViewers).reversed())
            .limit(100)
            .forEach(sample -> {
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("platform", sample.getPlatform());
              row.put("channel", sample.getChannel());
              row.put("title", sample.getTitle());
              row.put("viewers", sample.getViewers());
              row.put("url", sample.getUrl());
              row.put("timestamp", sample.getTimestamp());
              streamRows.add(row);
            });

    Map<String, Object> response = new LinkedHashMap<>();
    response.put("match", match);
    response.put("active", dynamicGameScheduler.getActiveMatches(match.getGame()).stream()
            .anyMatch(m -> matchId.equals(m.matchId())));
    response.put("summary", summary);
    response.put("timeline", timeline);
    response.put("streams", streamRows);
    return response;
  }

  /** Searchable catalog of every channel ever sampled, with live status from the latest collection. */
  @GetMapping("/channels/catalog")
  public List<Map<String, Object>> channelCatalog(@RequestParam(defaultValue = "VALORANT") String game) {
    GameConfig config = GameConfig.from(game);
    OffsetDateTime latestTimestamp = entityManager.createQuery(
                    "select max(s.timestamp) from StreamSample s " +
                            "where (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT'))",
                    OffsetDateTime.class)
            .setParameter("game", config.getId())
            .getSingleResult();
    if (latestTimestamp == null) return List.of();
    List<Object[]> grouped = entityManager.createQuery(
                    "select s.platform, s.channel, s.channelId, s.url, max(s.timestamp) from StreamSample s " +
                            "where (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT')) " +
                            "group by s.platform, s.channel, s.channelId, s.url", Object[].class)
            .setParameter("game", config.getId()).getResultList();
    List<StreamSample> current = entityManager.createQuery(
                    "select s from StreamSample s where s.timestamp = :timestamp and (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT'))", StreamSample.class)
            .setParameter("timestamp", latestTimestamp).setParameter("game", config.getId()).getResultList();
    Map<String, StreamSample> liveByIdentity = new HashMap<>();
    for (StreamSample sample : current) liveByIdentity.putIfAbsent(channelIdentity(sample.getPlatform(), sample.getChannelId(), sample.getChannel()), sample);
    List<Map<String, Object>> catalog = new ArrayList<>();
    for (Object[] row : grouped) {
      String platform=(String)row[0], channel=(String)row[1], channelId=(String)row[2], url=(String)row[3];
      OffsetDateTime lastSeen=(OffsetDateTime)row[4];
      StreamSample liveSample=lastSeen.equals(latestTimestamp)?liveByIdentity.get(channelIdentity(platform,channelId,channel)):null;
      Map<String,Object> item=new LinkedHashMap<>(); item.put("platform",platform); item.put("channel",channel); item.put("channelId",channelId);
      item.put("identity",channelIdentity(platform,channelId,channel)); item.put("url",url); item.put("live",liveSample!=null);
      item.put("viewers",liveSample==null?null:liveSample.getViewers()); item.put("lastSeen",lastSeen); catalog.add(item);
    }
    catalog.sort(Comparator.comparing((Map<String,Object> x)->String.valueOf(x.get("channel")),String.CASE_INSENSITIVE_ORDER).thenComparing(x->String.valueOf(x.get("platform")),String.CASE_INSENSITIVE_ORDER));
    return catalog;
  }

  /** Bounded, indexed channel search for historical comparisons, including offline channels. */
  @GetMapping("/channels/catalog/search")
  public List<Map<String, Object>> searchChannelCatalog(@RequestParam String query, @RequestParam(defaultValue = "VALORANT") String game) {
    String term=query==null?"":query.trim().toLowerCase(Locale.ROOT); if(term.length()<2)return List.of();
    GameConfig config=GameConfig.from(game);
    OffsetDateTime latestTimestamp=entityManager.createQuery(
                    "select max(s.timestamp) from StreamSample s " +
                            "where (lower(s.game)=lower(:game) or (s.game is null and :game='VALORANT'))",
                    OffsetDateTime.class)
            .setParameter("game",config.getId()).getSingleResult(); if(latestTimestamp==null)return List.of();
    List<Object[]> grouped=entityManager.createQuery(
                    "select s.platform,s.channel,s.channelId,s.url,max(s.timestamp) from StreamSample s " +
                            "where (lower(s.game)=lower(:game) or (s.game is null and :game='VALORANT')) and " +
                            "(lower(s.channel) like :term or lower(s.platform) like :term or lower(coalesce(s.channelId,'')) like :term) " +
                            "group by s.platform,s.channel,s.channelId,s.url order by lower(s.channel),lower(s.platform)",Object[].class)
            .setParameter("term","%"+term+"%").setParameter("game",config.getId()).setMaxResults(100).getResultList();
    if(grouped.isEmpty())return List.of();
    List<StreamSample> current=entityManager.createQuery(
                    "select s from StreamSample s where s.timestamp=:timestamp and (lower(s.game)=lower(:game) or (s.game is null and :game='VALORANT'))",StreamSample.class)
            .setParameter("timestamp",latestTimestamp).setParameter("game",config.getId()).getResultList();
    Map<String,StreamSample> liveByIdentity=new HashMap<>(); for(StreamSample sample:current) liveByIdentity.putIfAbsent(channelIdentity(sample.getPlatform(),sample.getChannelId(),sample.getChannel()),sample);
    List<Map<String,Object>> matches=new ArrayList<>();
    for(Object[] row:grouped){String platform=(String)row[0],channel=(String)row[1],channelId=(String)row[2];OffsetDateTime lastSeen=(OffsetDateTime)row[4];StreamSample live=lastSeen.equals(latestTimestamp)?liveByIdentity.get(channelIdentity(platform,channelId,channel)):null;Map<String,Object> item=new LinkedHashMap<>();item.put("platform",platform);item.put("channel",channel);item.put("channelId",channelId);item.put("identity",channelIdentity(platform,channelId,channel));item.put("url",row[3]);item.put("live",live!=null);item.put("viewers",live==null?null:live.getViewers());item.put("lastSeen",lastSeen);matches.add(item);}
    return matches;
  }

  @GetMapping("/channels/status")
  public List<Map<String, Object>> channelStatus(
          @RequestParam String name,
          @RequestParam(required = false) String platform,
          @RequestParam(defaultValue = "VALORANT") String game) {
    if (name.isBlank()) return List.of();
    GameConfig config = GameConfig.from(game);

    OffsetDateTime latestTimestamp = entityManager.createQuery(
                    "select max(s.timestamp) from StreamSample s " +
                            "where (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT'))",
                    OffsetDateTime.class)
            .setParameter("game", config.getId())
            .getSingleResult();
    if (latestTimestamp == null) return List.of();

    List<String> platforms = platform == null || platform.isBlank()
            ? List.of("YouTube", "Twitch", "Kick")
            : List.of(platform.trim());

    List<Map<String, Object>> status = new ArrayList<>();
    for (String requestedPlatform : platforms) {
      List<StreamSample> latest = entityManager.createQuery(
                      "select s from StreamSample s where lower(s.channel) = lower(:name) " +
                              "and lower(s.platform) = lower(:platform) " +
                              "and (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT')) " +
                              "order by s.timestamp desc, s.id desc", StreamSample.class)
              .setParameter("name", name.trim())
              .setParameter("platform", requestedPlatform)
              .setParameter("game", config.getId())
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

  /**
   * Map the application's GameConfig value to the exact game name used by
   * DynamicGameScheduler/Liquipedia.
   *
   * GameConfig may use short/internal IDs (for example CS2/LOL/DOTA2),
   * while the scheduler stores display-style esports names.
   */
  private boolean isSchedulerActive(GameConfig game) {
    String id = game.getId();
    String displayName = game.getDisplayName();

    // Prefer the display name because it matches the scheduler's managed names.
    if (dynamicGameScheduler.isGameActive(displayName)) {
      return true;
    }

    // Explicit aliases for internal GameConfig IDs.
    return switch (id.trim().toUpperCase(Locale.ROOT)) {
      case "CS2", "COUNTER-STRIKE", "COUNTER STRIKE" ->
              dynamicGameScheduler.isGameActive("COUNTER-STRIKE 2");
      case "LOL", "LEAGUE-OF-LEGENDS", "LEAGUE OF LEGENDS" ->
              dynamicGameScheduler.isGameActive("LEAGUE OF LEGENDS");
      case "DOTA2", "DOTA-2", "DOTA 2" ->
              dynamicGameScheduler.isGameActive("DOTA 2");
      case "R6", "RAINBOW-SIX", "RAINBOW SIX SIEGE" ->
              dynamicGameScheduler.isGameActive("RAINBOW SIX SIEGE");
      case "RL", "ROCKET-LEAGUE", "ROCKET LEAGUE" ->
              dynamicGameScheduler.isGameActive("ROCKET LEAGUE");
      case "APEX", "APEX-LEGENDS", "APEX LEGENDS" ->
              dynamicGameScheduler.isGameActive("APEX LEGENDS");
      case "OW2", "OVERWATCH-2", "OVERWATCH 2" ->
              dynamicGameScheduler.isGameActive("OVERWATCH 2");
      case "EAFC", "EA-SPORTS-FC", "EA SPORTS FC" ->
              dynamicGameScheduler.isGameActive("EA SPORTS FC");
      case "COD", "CALL-OF-DUTY", "CALL OF DUTY" ->
              dynamicGameScheduler.isGameActive("CALL OF DUTY");
      case "MLBB", "MOBILE-LEGENDS", "MOBILE LEGENDS" ->
              dynamicGameScheduler.isGameActive("MOBILE LEGENDS");
      case "FREE-FIRE", "FREE FIRE" ->
              dynamicGameScheduler.isGameActive("FREE FIRE");
      default -> false;
    };
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
          @RequestParam(defaultValue = "VALORANT") String game,
          @RequestParam(defaultValue = "5000") int limit) {
    if (name.isBlank()) return List.of();
    if ((from == null) != (to == null)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Both 'from' and 'to' must be supplied together");
    }
    if (from != null) validateRange(from, to);
    int safeLimit = clampLimit(limit);
    GameConfig config = GameConfig.from(game);

    String query = "select s from StreamSample s where lower(s.channel) = lower(:name) "
            + "and (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT')) ";
    if (platform != null && !platform.isBlank()) query += "and lower(s.platform) = lower(:platform) ";
    if (from != null) query += "and s.timestamp >= :from and s.timestamp < :to ";
    query += "order by s.timestamp desc, s.id desc";
    var typedQuery = entityManager.createQuery(query, StreamSample.class).setParameter("name", name).setParameter("game", config.getId());
    if (platform != null && !platform.isBlank()) typedQuery.setParameter("platform", platform.trim());
    if (from != null) typedQuery.setParameter("from", from).setParameter("to", to);
    List<StreamSample> history = new ArrayList<>(typedQuery.setMaxResults(safeLimit).getResultList());
    Collections.reverse(history);
    return history;
  }

  /** Compares a channel's selected window with the same window one calendar period earlier. */
  @GetMapping("/channels/comparison")
  public Map<String, Object> channelComparison(
          @RequestParam String name,
          @RequestParam(required = false) String platform,
          @RequestParam OffsetDateTime from,
          @RequestParam OffsetDateTime to,
          @RequestParam String period,
          @RequestParam(defaultValue = "VALORANT") String game) {
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
    result.put("current", summarizeChannelWindow(name, platform, game, from, to));
    result.put("previous", summarizeChannelWindow(name, platform, game, previousFrom, previousTo));
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
          String name, String platform, String game, OffsetDateTime from, OffsetDateTime to) {
    Map<Long, Long> minuteTotals = new HashMap<>();
    long lastId = 0L;
    final int pageSize = 5000;
    while (true) {
      String query = "select s from StreamSample s where lower(s.channel) = lower(:name) "
              + "and (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT')) "
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
              .setParameter("game", GameConfig.from(game).getId())
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
          @RequestParam OffsetDateTime to,
          @RequestParam(defaultValue = "VALORANT") String game) {
    validateRange(from, to);
    GameConfig config = GameConfig.from(game);
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
                              + "and (lower(s.game) = lower(:game) or (s.game is null and :game = 'VALORANT')) "
                              + (cursorTimestamp != null
                              ? "and (s.timestamp > :cursorTimestamp or (s.timestamp = :cursorTimestamp and s.id > :cursorId)) "
                              : "")
                              + "order by s.timestamp asc, s.id asc", Object[].class)
              .setParameter("from", from).setParameter("to", to).setParameter("game", config.getId());
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
  public List<Map<String, Object>> top(
          @RequestParam(defaultValue = "VALORANT") String game,
          @RequestParam(defaultValue = "Overall") String platform) {

    return streams(game, platform).stream()
            .limit(10)
            .toList();
  }

  /**
   * Returns actual Liquipedia team logo URLs for the selected game.
   *
   * Example:
   * GET /api/esports/team-logos?game=VALORANT
   */
  @GetMapping("/esports/team-logos")
  public Map<String, String> esportsTeamLogos(
          @RequestParam(defaultValue = "VALORANT") String game) {
    return liquipediaEsportsScheduleScraper.getTeamLogoUrls(game);
  }

  private static int clampLimit(int limit) {
    return Math.clamp(limit, 1, MAX_HISTORY_LIMIT);
  }

  private static void validateRange(OffsetDateTime from, OffsetDateTime to) {
    if (from == null || to == null || !from.isBefore(to)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "'from' must be earlier than 'to'");
    }
  }
}