package com.valorant.tracker.service.misc;

import com.valorant.tracker.model.VlrScheduledMatch;
import com.valorant.tracker.repository.VlrScheduledMatchRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Discovers today's scheduled VALORANT matches once, persists VLR map IDs, then starts polling at scheduled time. */
@Service
public class VlrScheduledMatchDiscoveryService {
    private static final Logger log = LoggerFactory.getLogger(VlrScheduledMatchDiscoveryService.class);
    private static final ZoneId SCHEDULE_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern VLR_MATCH_ID = Pattern.compile("https?://(?:www\\.)?vlr\\.gg/(\\d+)(?:/|$)");

    private final VlrMatchSearchService searchService;
    private final VlrGameUrlService gameUrlService;
    private final VlrScheduledMatchRepository scheduledRepository;
    private final VlrLiveRoundTrackerService liveTracker;

    public VlrScheduledMatchDiscoveryService(VlrMatchSearchService searchService,
            VlrGameUrlService gameUrlService, VlrScheduledMatchRepository scheduledRepository,
            VlrLiveRoundTrackerService liveTracker) {
        this.searchService = searchService;
        this.gameUrlService = gameUrlService;
        this.scheduledRepository = scheduledRepository;
        this.liveTracker = liveTracker;
    }

    /** Called by DynamicGameScheduler after its Liquipedia refresh has persisted today's matches. */
    public void discoverToday(List<LiquipediaEsportsScheduleScraper.EsportsMatch> matches) {
        LocalDate today = LocalDate.now(SCHEDULE_ZONE);
        List<LiquipediaEsportsScheduleScraper.EsportsMatch> expected = matches.stream()
                .filter(m -> m.game() != null && m.game().equalsIgnoreCase("VALORANT"))
                .filter(m -> m.startTime() != null && m.startTime().atZoneSameInstant(SCHEDULE_ZONE).toLocalDate().equals(today))
                .filter(m -> !m.finished() && !"COMPLETED".equalsIgnoreCase(m.status()))
                .filter(m -> m.matchId() != null && !m.matchId().isBlank())
                .toList();
        if (expected.isEmpty()) return;

        Set<String> alreadyDiscovered = new HashSet<>();
        for (VlrScheduledMatch row : scheduledRepository.findByMatchDate(today)) {
            alreadyDiscovered.add(row.getStremngMatchId());
        }
        List<LiquipediaEsportsScheduleScraper.EsportsMatch> missing = expected.stream()
                .filter(m -> !alreadyDiscovered.contains(m.matchId())).toList();
        if (missing.isEmpty()) {
            log.debug("VLR discovery already complete for {} ({} matches)", today, expected.size());
            return;
        }

        log.info("VLR discovery for {}: {} of {} eligible matches still need mapping", today, missing.size(), expected.size());
        for (var match : missing) {
            try {
                discoverOne(today, match);
            } catch (Exception e) {
                log.warn("VLR discovery failed for Stremng match {}: {}", match.matchId(), safeMessage(e));
            }
        }
    }

    private void discoverOne(LocalDate today, LiquipediaEsportsScheduleScraper.EsportsMatch match) {
        Map<String, Object> search = searchService.findMatch(match.team1(), match.team2());
        if (!"MATCH_FOUND".equals(search.get("status"))) {
            log.info("VLR match not resolved yet for {} vs {}: {}", match.team1(), match.team2(), search.get("status"));
            return; // Not marked complete; the next dynamic refresh retries.
        }
        Object value = search.get("matches");
        if (!(value instanceof List<?> candidates)) return;
        List<VlrMatchSearchService.VlrMatch> exact = candidates.stream()
                .filter(VlrMatchSearchService.VlrMatch.class::isInstance)
                .map(VlrMatchSearchService.VlrMatch.class::cast).toList();
        if (exact.size() > 1 && match.tournament() != null && !match.tournament().isBlank()) {
            List<VlrMatchSearchService.VlrMatch> eventMatches = exact.stream()
                    .filter(v -> containsNormalized(v.event(), match.tournament())).toList();
            if (eventMatches.size() == 1) exact = eventMatches;
        }
        if (exact.size() != 1) {
            log.warn("VLR candidate ambiguous for {} vs {} ({} candidates); refusing unsafe mapping", match.team1(), match.team2(), exact.size());
            return;
        }

        String matchUrl = exact.get(0).matchUrl();
        Map<String, Object> gamesResponse = gameUrlService.findGameUrls(matchUrl);
        if (!"GAMES_FOUND".equals(gamesResponse.get("status"))) {
            log.info("VLR map IDs not published yet for {}: {}", matchUrl, gamesResponse.get("status"));
            return;
        }
        Object gamesValue = gamesResponse.get("games");
        if (!(gamesValue instanceof List<?> games) || games.isEmpty()) return;
        Matcher idMatcher = VLR_MATCH_ID.matcher(matchUrl);
        String vlrMatchId = idMatcher.find() ? idMatcher.group(1) : null;
        Instant scheduledAt = match.startTime().toInstant();
        Instant now = Instant.now();
        int saved = 0;
        for (Object item : games) {
            if (!(item instanceof Map<?, ?> gameMap)) continue;
            String gameId = Objects.toString(gameMap.get("gameId"), "").trim();
            if (gameId.isBlank() || !gameId.matches("\\d{1,12}")) continue;
            String rowId = match.matchId() + "::" + gameId;
            VlrScheduledMatch row = scheduledRepository.findById(rowId).orElseGet(() ->
                    new VlrScheduledMatch(rowId, match.matchId(), "VALORANT", today, scheduledAt,
                            matchUrl, vlrMatchId, gameId, match.team1(), match.team2(), match.tournament(), now));
            scheduledRepository.save(row);
            saved++;
        }
        if (saved == 0) log.warn("No valid VLR map IDs found for Stremng match {}", match.matchId());
        else log.info("Discovered VLR match {} for {} vs {}; saved {} map IDs; scheduled at {}", vlrMatchId, match.team1(), match.team2(), saved, scheduledAt);
    }

    /** Starts map polling only once its persisted scheduled start time has arrived. */
    @Scheduled(fixedDelay = 1000)
    public void startDuePolls() {
        Instant now = Instant.now();
//        log.info("VLR scheduled match discovery: checking for due polls at {}", now);
        List<VlrScheduledMatch> todayRows = scheduledRepository.findByMatchDate(LocalDate.now(SCHEDULE_ZONE));
        Set<String> justCompletedMatches = new HashSet<>();
        for (VlrScheduledMatch row : todayRows) {
            if ("POLLING".equals(row.getPollingStatus()) && liveTracker.isMapCompleted(row.getStremngMatchId(), row.getVlrGameId())) {
                row.markCompleted();
                scheduledRepository.save(row);
                justCompletedMatches.add(row.getStremngMatchId());
                log.info("Marked VLR map completed for match {} map {}", row.getStremngMatchId(), row.getVlrGameId());
            }
        }
        // A best-of-3 needs two map wins; a best-of-5 needs three. Stop scheduling once a team clinches.
        for (String matchId : justCompletedMatches) {
            List<VlrScheduledMatch> series = scheduledRepository.findByStremngMatchId(matchId);
            int requiredWins = series.size() >= 5 ? 3 : 2;
            Map<String, Integer> wins = new HashMap<>();
            for (VlrScheduledMatch map : series) {
                if (!"COMPLETED".equals(map.getPollingStatus())) continue;
                String winner = liveTracker.getMapWinner(matchId, map.getVlrGameId());
                if (winner != null && !winner.isBlank()) wins.merge(winner, 1, Integer::sum);
            }
            Optional<String> seriesWinner = wins.entrySet().stream().filter(e -> e.getValue() >= requiredWins).map(Map.Entry::getKey).findFirst();
            if (seriesWinner.isPresent()) {
                for (VlrScheduledMatch map : series) {
                    if ("WAITING".equals(map.getPollingStatus())) {
                        map.markSkippedSeriesDecided();
                        scheduledRepository.save(map);
                    }
                }
                liveTracker.stopMatchTrackers(matchId, "SERIES_COMPLETED");
                log.info("VLR series completed: match={} winner={} mapWins={}; remaining maps will not be polled", matchId, seriesWinner.get(), requiredWins);
            }
        }
        List<VlrScheduledMatch> due = scheduledRepository.findByPollingStatusAndNextAttemptAtLessThanEqual("WAITING", now);
        Map<String, List<VlrScheduledMatch>> byMatch = new LinkedHashMap<>();
        for (VlrScheduledMatch row : due) byMatch.computeIfAbsent(row.getStremngMatchId(), k -> new ArrayList<>()).add(row);
        for (List<VlrScheduledMatch> candidates : byMatch.values()) {
            candidates.sort(Comparator.comparingLong(r -> Long.parseLong(r.getVlrGameId())));
            VlrScheduledMatch row = candidates.get(0);
            List<VlrScheduledMatch> all = scheduledRepository.findByStremngMatchId(row.getStremngMatchId());
            all.sort(Comparator.comparingLong(r -> Long.parseLong(r.getVlrGameId())));
            VlrScheduledMatch firstUnfinished = all.stream().filter(r -> !Set.of("COMPLETED", "SKIPPED_SERIES_DECIDED").contains(r.getPollingStatus())).findFirst().orElse(null);
            if (firstUnfinished == null || !firstUnfinished.getId().equals(row.getId())) continue;
            try {
                liveTracker.register(row.getStremngMatchId(), row.getGame(), row.getMatchUrl(), row.getVlrGameId());
                row.markPollingStarted(now);
                scheduledRepository.save(row);
                log.info("Started sequential VLR map polling for Stremng match {} map {} at scheduled time {}", row.getStremngMatchId(), row.getVlrGameId(), row.getScheduledAt());
            } catch (Exception e) {
                row.markError(safeMessage(e));
                scheduledRepository.save(row);
                log.warn("Unable to start VLR polling for {} map {}: {}", row.getStremngMatchId(), row.getVlrGameId(), safeMessage(e));
            }
        }
//        log.info("VLR scheduled match discovery: {} due polls processed at {}", due.size(), now);
    }

    private static boolean containsNormalized(String value, String expected) {
        return normalize(value).contains(normalize(expected)) || normalize(expected).contains(normalize(value));
    }
    private static String normalize(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]", ""); }
    private static String safeMessage(Exception e) { String m = Objects.toString(e.getMessage(), e.getClass().getSimpleName()); return m.length() > 450 ? m.substring(0, 450) : m; }
}
