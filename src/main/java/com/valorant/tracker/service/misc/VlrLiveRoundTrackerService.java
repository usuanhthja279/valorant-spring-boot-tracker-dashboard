package com.valorant.tracker.service.misc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.valorant.tracker.model.EsportsMatchRecord;
import com.valorant.tracker.model.VlrLiveMapTrack;
import com.valorant.tracker.repository.EsportsMatchRecordRepository;
import com.valorant.tracker.repository.VlrLiveMapTrackRepository;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Opt-in live VLR round poller. One persisted tracker per Stremng match + VLR map.
 * VLR polling is isolated from Spring's scheduler thread and backs off on errors.
 */
@Service
public class VlrLiveRoundTrackerService {
    private static final Logger log = LoggerFactory.getLogger(VlrLiveRoundTrackerService.class);
    private static final Pattern VLR_MATCH_PATH = Pattern.compile("^/\\d+/[^?#]+/?$");
    private static final Pattern GAME_ID = Pattern.compile("\\d{1,12}");
    private static final Pattern TIME = Pattern.compile("^(\\d{1,2}):(\\d{2})$");
    private static final long POLL_SECONDS = 5;
    private static final int MAX_TRACKS_PER_MATCH = 5;

    private final VlrLiveMapTrackRepository tracks;
    private final EsportsMatchRecordRepository matches;
    private final VlrRoundLogService roundLogService;
    private final ObjectMapper mapper;
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(20), r -> {
        Thread t = new Thread(r, "vlr-live-poller");
        t.setDaemon(true);
        return t;
    },
            new ThreadPoolExecutor.DiscardPolicy());
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public VlrLiveRoundTrackerService(VlrLiveMapTrackRepository tracks,
                                      EsportsMatchRecordRepository matches,
                                      VlrRoundLogService roundLogService, ObjectMapper mapper) {
        this.tracks = tracks;
        this.matches = matches;
        this.roundLogService = roundLogService;
        this.mapper = mapper;
    }

    /**
     * Explicit opt-in: a VLR map URL and gameId must be known before polling starts.
     */
    public Map<String, Object> register(String stremngMatchId, String game, String matchUrl, String gameId) {
        String id = required(stremngMatchId, "matchId", 300);
        String cleanGame = required(game == null ? "VALORANT" : game, "game", 100);
        String cleanGameId = required(gameId, "gameId", 12);
        if (!GAME_ID.matcher(cleanGameId).matches()) throw new IllegalArgumentException("gameId must be numeric");
        String cleanUrl = validateVlrUrl(matchUrl);
        EsportsMatchRecord match = matches.findById(id).orElseThrow(() -> new IllegalArgumentException("Stremng matchId was not found"));
        if (!"VALORANT".equalsIgnoreCase(cleanGame) || !"VALORANT".equalsIgnoreCase(match.getGame())) {
            throw new IllegalArgumentException("VLR round tracking is currently supported for VALORANT matches only");
        }
        // Completed matches are allowed: their existing VLR round logs can be loaded
        // into the persisted JSON, then the tracker stops only after map completion is confirmed.
        List<VlrLiveMapTrack> existing = tracks.findByStremngMatchIdOrderByCreatedAtDesc(id);
        if (existing.size() >= MAX_TRACKS_PER_MATCH && existing.stream().noneMatch(t -> t.getGameId().equals(cleanGameId))) {
            throw new IllegalArgumentException("Maximum of " + MAX_TRACKS_PER_MATCH + " maps per match reached");
        }
        String trackId = id + "::" + cleanGameId;
        VlrLiveMapTrack track = tracks.findById(trackId).orElseGet(() -> new VlrLiveMapTrack(
                trackId, id, cleanGame, cleanUrl, cleanGameId, match.getTeam1(), match.getTeam2(), Instant.now()));
        if (!track.isEnabled())
            throw new IllegalArgumentException("This map tracker has been stopped; use a new tracking ID to restart it");
        tracks.save(track);
        return publicState(track);
    }

    public boolean isMapCompleted(String stremngMatchId, String gameId) {
        return tracks.findById(required(stremngMatchId, "matchId", 300) + "::" + required(gameId, "gameId", 12))
                .map(t -> "MAP_COMPLETED".equals(t.getTrackingStatus())).orElse(false);
    }

    public String getMapWinner(String stremngMatchId, String gameId) {
        VlrLiveMapTrack track = tracks.findById(required(stremngMatchId, "matchId", 300) + "::" + required(gameId, "gameId", 12)).orElse(null);
        if (track == null || !"MAP_COMPLETED".equals(track.getTrackingStatus())) return null;
        try {
            Map<String, Object> response = mapper.readValue(track.getLatestResponseJson() == null ? "{}" : track.getLatestResponseJson(), new TypeReference<>() {});
            return Objects.toString(response.get("mapWinner"), null);
        } catch (Exception ex) { return null; }
    }

    public void stopMatchTrackers(String stremngMatchId, String reason) {
        for (VlrLiveMapTrack track : tracks.findByStremngMatchIdOrderByCreatedAtDesc(required(stremngMatchId, "matchId", 300))) {
            if (track.isEnabled()) { track.stop(reason); tracks.save(track); }
        }
    }

    private boolean isEarliestUnfinishedMap(VlrLiveMapTrack candidate) {
        long candidateId;
        try { candidateId = Long.parseLong(candidate.getGameId()); } catch (NumberFormatException ex) { return false; }
        for (VlrLiveMapTrack other : tracks.findByStremngMatchIdOrderByCreatedAtDesc(candidate.getStremngMatchId())) {
            long otherId;
            try { otherId = Long.parseLong(other.getGameId()); } catch (NumberFormatException ex) { continue; }
            if (otherId < candidateId && !"MAP_COMPLETED".equals(other.getTrackingStatus())) return false;
        }
        return true;
    }

    public Map<String, Object> getState(String stremngMatchId) {
        String id = required(stremngMatchId, "matchId", 300);
        List<Map<String, Object>> mapStates = tracks.findByStremngMatchIdOrderByCreatedAtDesc(id).stream().map(this::publicState).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("matchId", id);
        result.put("source", "VLR");
        result.put("pollIntervalSeconds", POLL_SECONDS);
        result.put("trackingStatus", mapStates.isEmpty() ? "NOT_CONFIGURED" : mapStates.stream().anyMatch(x -> Boolean.TRUE.equals(x.get("enabled"))) ? "TRACKING" : "STOPPED");
        result.put("maps", mapStates);
        result.put("updatedAt", Instant.now().toString());
        return result;
    }

    public Map<String, Object> stop(String stremngMatchId, String gameId) {
        String trackId = required(stremngMatchId, "matchId", 300) + "::" + required(gameId, "gameId", 12);
        VlrLiveMapTrack track = tracks.findById(trackId).orElseThrow(() -> new IllegalArgumentException("No tracker registered for this match/map"));
        track.stop("STOPPED");
        tracks.save(track);
        return publicState(track);
    }

    @Scheduled(fixedDelay = 1000)
    public void dispatchDuePolls() {
        Instant now = Instant.now();
//        log.info("VLR live poller dispatch cycle: {} in-flight, {} enabled trackers", inFlight.size(), tracks.findByEnabledTrue().size());
        for (VlrLiveMapTrack track : tracks.findByEnabledTrue()) {
            if (!isEarliestUnfinishedMap(track)) continue;
            if (track.getNextPollAt() != null && track.getNextPollAt().isAfter(now)) continue;
            if (!inFlight.add(track.getId())) continue;
            try {
                workers.execute(() -> {
                    try {
                        pollOne(track.getId());
                    } finally {
                        inFlight.remove(track.getId());
                    }
                });
            } catch (RejectedExecutionException ex) {
                inFlight.remove(track.getId());
                log.warn("VLR poll queue full; skipping map {} this cycle", track.getGameId());
            }
        }
//        log.info("VLR live poller dispatch cycle complete: {} in-flight, {} enabled trackers", inFlight.size(), tracks.findByEnabledTrue().size());
    }

    private void pollOne(String trackId) {
        VlrLiveMapTrack track = tracks.findById(trackId).orElse(null);
        if (track == null || !track.isEnabled()) return;
        // Do not stop solely from the Stremng match status. A completed match may
        // still need a final VLR fetch to fill missing rounds into roundsJson.
        Instant now = Instant.now();
        track.markPollStarted(now);
        tracks.save(track);
        try {
            boolean seeded = "[]".equals(track.getRoundsJson()) || track.getRoundsJson() == null || track.getRoundsJson().isBlank();
            // Fetch the complete map log on every poll. This supports late-arriving
            // round data and lets live and historic matches use the same persisted JSON.
            Map<String, Object> response = roundLogService.fetchRoundLogs(track.getMatchUrl(), track.getGameId(), null);
            String status = Objects.toString(response.get("status"), "");
            if ("FETCH_FAILED".equals(status))
                throw new IllegalStateException(Objects.toString(response.get("error"), "VLR fetch failed"));

            List<Map<String, Object>> oldRounds = parseRounds(track.getRoundsJson());
            List<Map<String, Object>> newRounds = asRoundDetails(response.get("roundDetails"));
            if (newRounds.isEmpty()) {
                track.markNotReady(Instant.now());
                track.markSuccess(Instant.now(), mapper.writeValueAsString(response), mapper.writeValueAsString(oldRounds), track.getNextRound());
                // markSuccess sets the normal 5s interval; retain waiting status after it.
                track.markNotReady(Instant.now());
                // No round details means final round data has not been confirmed yet.
                tracks.save(track);
                return;
            }

            Instant observedAt = Instant.now();
            Map<String, Map<String, Object>> byRound = new TreeMap<>(Comparator.comparingInt(VlrLiveRoundTrackerService::roundNumber));
            for (Map<String, Object> r : oldRounds) byRound.put(Objects.toString(r.get("round"), "0"), r);
            for (Map<String, Object> incomingRound : newRounds) {
                String roundKey = Objects.toString(incomingRound.get("round"), "0");
                Map<String, Object> previousRound = byRound.get(roundKey);
                // Merge each incoming round into the persisted JSON rather than replacing it.
                // This keeps earlier events when a live/source response is partial.
                Map<String, Object> round = mergeRoundData(previousRound, incomingRound);
                annotateFetchMetadata(round, previousRound, observedAt);
                if (seeded) {
                    // Bootstrap rounds are known to exist, but their historic absolute times are not observable now.
                    addDurationOnly(round);
                } else if (previousRound == null) {
                    track.markRoundSeen(observedAt);
                    enrichTiming(round, track.getLastSeenWithoutRoundAt(), observedAt);
                } else {
                    // Refresh event-derived duration without moving the original timing estimate on every poll.
                    updateDuration(round);
                }
                byRound.put(roundKey, round);
            }
            List<Map<String, Object>> merged = new ArrayList<>(byRound.values());
            int maxRound = merged.stream().mapToInt(r -> roundNumber(Objects.toString(r.get("round"), "0"))).max().orElse(0);
            int nextRound = maxRound + 1;
            track.markSuccess(observedAt, mapper.writeValueAsString(response), mapper.writeValueAsString(merged), nextRound);
            if (!seeded) track.clearPendingRoundWindow();
            if (Boolean.TRUE.equals(response.get("mapCompleted"))) {
                track.stop("MAP_COMPLETED");
                log.info("VLR map completed: match={} map={} score={} winner={}", track.getStremngMatchId(), track.getGameId(), response.get("mapFinalScore"), response.get("mapWinner"));
            }
            tracks.save(track);
        } catch (Exception ex) {
            log.warn("VLR live round poll failed for track {}: {}", trackId, ex.toString());
            track.markFailure(Instant.now(), safeMessage(ex));
            tracks.save(track);
        }
    }

    private List<Map<String, Object>> asRoundDetails(Object value) {
        if (!(value instanceof List<?> list)) return List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list)
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> cast = new LinkedHashMap<>();
                map.forEach((k, v) -> cast.put(String.valueOf(k), v));
                out.add(cast);
            }
        return out;
    }

    private List<Map<String, Object>> parseRounds(String json) {
        try {
            return mapper.readValue(json == null || json.isBlank() ? "[]" : json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("Unable to parse persisted VLR round JSON; starting an empty timeline: {}", e.toString());
            return new ArrayList<>();
        }
    }

    private Map<String, Object> mergeRoundData(Map<String, Object> previous, Map<String, Object> incoming) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (previous != null) merged.putAll(previous);
        incoming.forEach((key, value) -> {
            if (value == null && merged.get(key) != null) return;
            if ("events".equals(key) && value instanceof List<?> newEvents) {
                merged.put(key, mergeJsonList(merged.get(key), newEvents, true));
            } else if ("objectiveTimelines".equals(key) && value instanceof List<?> newTimelines) {
                merged.put(key, mergeJsonList(merged.get(key), newTimelines, false));
            } else if ("players".equals(key) && value instanceof List<?> newPlayers) {
                merged.put(key, mergePlayers(merged.get(key), newPlayers));
            } else {
                merged.put(key, value);
            }
        });
        if (merged.get("events") instanceof List<?> events) merged.put("eventCount", events.size());
        if (merged.get("players") instanceof List<?> players) merged.put("playerCount", players.size());
        if (merged.get("objectiveTimelines") instanceof List<?> timelines) merged.put("objectiveTimelineCount", timelines.size());
        return merged;
    }

    private List<Object> mergeJsonList(Object oldValue, List<?> newValues, boolean eventObjects) {
        List<Object> result = new ArrayList<>();
        if (oldValue instanceof List<?> oldList) result.addAll(oldList);
        Set<String> seen = new LinkedHashSet<>();
        for (Object value : result) seen.add(jsonMergeKey(value, eventObjects));
        for (Object value : newValues) {
            String key = jsonMergeKey(value, eventObjects);
            if (seen.add(key)) result.add(value);
            else if (eventObjects && value instanceof Map<?, ?> newMap) {
                // If an event's identity is stable but its text/metadata improved, update that entry in place.
                for (int i = 0; i < result.size(); i++) {
                    if (jsonMergeKey(result.get(i), true).equals(key)) {
                        Map<String, Object> mergedEvent = new LinkedHashMap<>();
                        if (result.get(i) instanceof Map<?, ?> oldMap)
                            oldMap.forEach((k, v) -> mergedEvent.put(String.valueOf(k), v));
                        newMap.forEach((k, v) -> mergedEvent.put(String.valueOf(k), v));
                        result.set(i, mergedEvent);
                        break;
                    }
                }
            }
        }
        return result;
    }

    private String jsonMergeKey(Object value, boolean eventObjects) {
        if (eventObjects && value instanceof Map<?, ?> map) {
            return Objects.toString(map.get("timestamp"), "") + "|" +
                    Objects.toString(map.get("type"), "") + "|" + Objects.toString(map.get("text"), "");
        }
        try { return mapper.writeValueAsString(value); }
        catch (Exception ex) { return Objects.toString(value, ""); }
    }

    private List<Object> mergePlayers(Object oldValue, List<?> newPlayers) {
        Map<String, Map<String, Object>> byPlayer = new LinkedHashMap<>();
        if (oldValue instanceof List<?> oldList) {
            for (Object value : oldList) if (value instanceof Map<?, ?> map) {
                Map<String, Object> player = new LinkedHashMap<>();
                map.forEach((k, v) -> player.put(String.valueOf(k), v));
                String name = Objects.toString(player.get("player"), "");
                if (!name.isBlank()) byPlayer.put(name, player);
            }
        }
        for (Object value : newPlayers) if (value instanceof Map<?, ?> map) {
            Map<String, Object> player = new LinkedHashMap<>();
            map.forEach((k, v) -> player.put(String.valueOf(k), v));
            String name = Objects.toString(player.get("player"), "");
            if (name.isBlank()) continue;
            byPlayer.compute(name, (key, old) -> {
                if (old == null) return player;
                old.putAll(player);
                return old;
            });
        }
        return new ArrayList<>(byPlayer.values());
    }

    /**
     * Tracks when this round was returned by successful VLR fetches.
     * Game event timestamps (for example "0:31") remain untouched.
     */
    private void annotateFetchMetadata(Map<String, Object> round, Map<String, Object> previous, Instant fetchedAt) {
        String now = fetchedAt.toString();
        round.put("firstFetchedAt", previous == null
                ? now : Objects.toString(previous.get("firstFetchedAt"), now));
        round.put("lastFetchedAt", now);
        Object previousCount = previous == null ? null : previous.get("fetchCount");
        int count = 0;
        if (previousCount instanceof Number n) count = n.intValue();
        else if (previousCount != null) {
            try { count = Integer.parseInt(previousCount.toString()); } catch (NumberFormatException ignored) { }
        }
        round.put("fetchCount", Math.max(0, count) + 1);

        boolean changed = previous == null || !roundSourceData(round).equals(roundSourceData(previous));
        Object oldUpdatedAt = previous == null ? null : previous.get("lastUpdatedAt");
        round.put("lastUpdatedAt", changed || oldUpdatedAt == null ? now : oldUpdatedAt.toString());
    }

    private String roundSourceData(Map<String, Object> round) {
        Map<String, Object> source = new TreeMap<>();
        Set<String> generated = Set.of(
                "firstFetchedAt", "lastFetchedAt", "fetchCount", "lastUpdatedAt",
                "firstSeenAt", "lastSeenWithoutRoundAt", "timestampUncertaintySeconds",
                "estimatedStartAt", "estimatedEndAt", "estimatedDurationSeconds",
                "lastEventTimestamp", "timestampMethod", "timestampConfidence",
                "timestampSource", "estimatedStartAtEpochMs", "estimatedEndAtEpochMs"
        );
        round.forEach((key, value) -> {
            if (!generated.contains(key)) source.put(key, value);
        });
        try {
            return mapper.writeValueAsString(source);
        } catch (Exception ex) {
            // Map equality fallback representation is deterministic enough for ordinary parsed JSON values.
            return source.toString();
        }
    }

    private static void addDurationOnly(Map<String, Object> round) {
        List<?> events = round.get("events") instanceof List<?> list ? list : List.of();
        int lastEventSeconds = 0;
        String lastEventTimestamp = null;
        for (Object obj : events)
            if (obj instanceof Map<?, ?> event) {
                String ts = Objects.toString(event.get("timestamp"), "");
                Matcher m = TIME.matcher(ts);
                if (m.matches()) {
                    int sec = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                    if (sec >= lastEventSeconds) {
                        lastEventSeconds = sec;
                        lastEventTimestamp = ts;
                    }
                }
            }
        round.put("lastEventTimestamp", lastEventTimestamp);
        round.put("estimatedDurationSeconds", lastEventSeconds + 6);
        round.put("timestampMethod", "BOOTSTRAP_UNANCHORED");
        round.put("timestampConfidence", "LOW");
        round.put("estimatedRoundStartAt", null);
        round.put("estimatedRoundEndAt", null);
        round.put("timestampUncertaintyMs", null);
    }

    private static void updateDuration(Map<String, Object> round) {
        List<?> events = round.get("events") instanceof List<?> list ? list : List.of();
        int lastEventSeconds = 0;
        String lastEventTimestamp = null;
        for (Object obj : events) {
            if (obj instanceof Map<?, ?> event) {
                String ts = Objects.toString(event.get("timestamp"), "");
                Matcher m = TIME.matcher(ts);
                if (m.matches()) {
                    int sec = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                    if (sec >= lastEventSeconds) {
                        lastEventSeconds = sec;
                        lastEventTimestamp = ts;
                    }
                }
            }
        }
        round.put("lastEventTimestamp", lastEventTimestamp);
        round.put("estimatedDurationSeconds", lastEventSeconds + 6);
    }

    private static void enrichTiming(Map<String, Object> round, Instant missingSince, Instant firstSeen) {
        List<?> events = round.get("events") instanceof List<?> list ? list : List.of();
        int lastEventSeconds = 0;
        String lastEventTimestamp = null;
        for (Object obj : events)
            if (obj instanceof Map<?, ?> event) {
                String ts = Objects.toString(event.get("timestamp"), "");
                Matcher m = TIME.matcher(ts);
                if (m.matches()) {
                    int sec = Integer.parseInt(m.group(1)) * 60 + Integer.parseInt(m.group(2));
                    if (sec >= lastEventSeconds) {
                        lastEventSeconds = sec;
                        lastEventTimestamp = ts;
                    }
                }
            }
        int duration = lastEventSeconds + 6;
        round.put("lastEventTimestamp", lastEventTimestamp);
        round.put("estimatedDurationSeconds", duration);
        round.put("firstSeenAt", firstSeen.toString());
        round.put("lastSeenWithoutRoundAt", missingSince == null ? null : missingSince.toString());
        Instant endEstimate = firstSeen;
        if (missingSince != null && missingSince.isBefore(firstSeen)) {
            long midpointMillis = Duration.between(missingSince, firstSeen).toMillis() / 2;
            endEstimate = missingSince.plusMillis(midpointMillis);
            round.put("timestampUncertaintyMs", Duration.between(missingSince, firstSeen).toMillis() / 2);
        } else {
            round.put("timestampUncertaintyMs", 2500);
        }
        round.put("estimatedRoundEndAt", endEstimate.toString());
        round.put("estimatedRoundStartAt", endEstimate.minusSeconds(duration).toString());
        round.put("timestampMethod", "LIVE_POLL_WINDOW_ESTIMATE");
        round.put("timestampConfidence", "ESTIMATED");
    }

    private Map<String, Object> publicState(VlrLiveMapTrack track) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("trackingId", track.getId());
        out.put("matchId", track.getStremngMatchId());
        out.put("game", track.getGame());
        out.put("team1", track.getTeam1());
        out.put("team2", track.getTeam2());
        out.put("matchUrl", track.getMatchUrl());
        out.put("gameId", track.getGameId());
        out.put("trackingStatus", track.getTrackingStatus());
        out.put("enabled", track.isEnabled());
        out.put("pollIntervalSeconds", POLL_SECONDS);
        out.put("nextRound", track.getNextRound());
        out.put("createdAt", str(track.getCreatedAt()));
        out.put("lastPollAt", str(track.getLastPollAt()));
        out.put("lastSuccessfulPollAt", str(track.getLastSuccessAt()));
        out.put("nextPollAt", str(track.getNextPollAt()));
        out.put("lastError", track.getLastError());
        out.put("consecutiveFailures", track.getConsecutiveFailures());
        out.put("rounds", parseRounds(track.getRoundsJson()));
        try {
            out.put("latestResponse", mapper.readValue(track.getLatestResponseJson() == null ? "{}" : track.getLatestResponseJson(), new TypeReference<Map<String, Object>>() {
            }));
        } catch (Exception ignored) {
            out.put("latestResponse", Map.of());
        }
        out.put("timestampMethod", "LIVE_POLL_WINDOW_ESTIMATE");
        return out;
    }

    private static String validateVlrUrl(String value) {
        String raw = required(value, "matchUrl", 1000);
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (Exception e) {
            throw new IllegalArgumentException("matchUrl must be a valid VLR match URL");
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || !"www.vlr.gg".equalsIgnoreCase(uri.getHost()) || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null || !VLR_MATCH_PATH.matcher(uri.getPath()).matches()) {
            throw new IllegalArgumentException("matchUrl must be a clean https://www.vlr.gg/{matchId}/{slug} URL without query parameters");
        }
        return "https://www.vlr.gg" + uri.getPath().replaceAll("/$", "");
    }

    private static String required(String v, String name, int max) {
        String s = v == null ? "" : v.trim();
        if (s.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (s.length() > max) throw new IllegalArgumentException(name + " is too long");
        return s;
    }

    private static int roundNumber(Map<String, Object> map) {
        return roundNumber(Objects.toString(map.get("round"), "0"));
    }

    private static int roundNumber(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String str(Instant i) {
        return i == null ? null : i.toString();
    }

    private static String safeMessage(Exception e) {
        String s = Objects.toString(e.getMessage(), "Request failed");
        return s.length() > 450 ? s.substring(0, 450) : s;
    }

    @PreDestroy
    public void shutdown() {
        workers.shutdownNow();
    }
}
