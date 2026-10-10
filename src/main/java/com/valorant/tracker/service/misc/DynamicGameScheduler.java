package com.valorant.tracker.service.misc;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.annotation.Transactional;
import com.valorant.tracker.model.EsportsMatchRecord;
import com.valorant.tracker.repository.EsportsMatchRecordRepository;
import java.time.OffsetDateTime;
import java.time.Instant;
import java.time.Duration;
import org.springframework.stereotype.Component;
import org.springframework.context.event.EventListener;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.core.annotation.Order;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Component
public class DynamicGameScheduler {

    private static final Logger log = LoggerFactory.getLogger(DynamicGameScheduler.class);

    private final LiquipediaEsportsScheduleScraper scraper;
    private final EsportsMatchRecordRepository matchRepository;

    /**
     * Games for which we want dynamic esports scheduling.
     */
    private static final List<String> ESPORTS_GAMES = List.of(
            "VALORANT",
            "COUNTER-STRIKE 2",
            "LEAGUE OF LEGENDS",
            "DOTA 2",
            "FORTNITE",
            "RAINBOW SIX SIEGE",
            "ROCKET LEAGUE",
            "APEX LEGENDS",
            "OVERWATCH 2",
            "EA SPORTS FC",
            "PUBG",
            "CALL OF DUTY",
            "MOBILE LEGENDS",
            "FREE FIRE"
    );

    /**
     * Games currently having an active S/A-tier match.
     */
    private final Set<String> activeGames =
            ConcurrentHashMap.newKeySet();

    /** All currently active Liquipedia matches per game. Multiple matches may overlap. */
    private final ConcurrentHashMap<String, List<LiquipediaEsportsScheduleScraper.EsportsMatch>> activeMatches =
            new ConcurrentHashMap<>();

    /**
     * Cached future/upcoming matches discovered during the normal 5-minute
     * Liquipedia refresh. This lets the scheduler know exactly when a match
     * should be checked without waiting for the next 5-minute refresh.
     */
    private final ConcurrentHashMap<String, List<LiquipediaEsportsScheduleScraper.EsportsMatch>> scheduledMatches =
            new ConcurrentHashMap<>();

    /** One exact start-time confirmation task per scheduled match. */
    private final ConcurrentHashMap<String, ScheduledFuture<?>> startConfirmationTasks =
            new ConcurrentHashMap<>();

    private final ScheduledExecutorService confirmationExecutor =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "esports-start-confirmation");
                t.setDaemon(true);
                return t;
            });

    /** Retry a start confirmation shortly after a transient Liquipedia failure. */
    private static final long START_CONFIRMATION_RETRY_SECONDS = 30;

    public DynamicGameScheduler(
            LiquipediaEsportsScheduleScraper scraper,
            EsportsMatchRecordRepository matchRepository) {

        this.scraper = scraper;
        this.matchRepository = matchRepository;
    }

    /**
     * Populate the scheduler immediately at application startup so an active
     * match does not wait for the first 5-minute scheduled refresh.
     */
    /**
     * Run after Hibernate and the idempotent schema repair are ready.
     * This avoids the startup race where the scheduler queried
     * esports_matches before newly-added score/status columns existed.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Order(100)
    public void initializeSchedule() {
        refreshSchedule();
    }

    /**
     * Check Liquipedia every 5 minutes.
     */
    @Scheduled(fixedRateString = "${tracker.esports.schedule-check-ms:300000}")
    @Transactional
    // API cache invalidation is owned by TrackerService and runs asynchronously
    // so this scheduling thread is never blocked by cache clearing.
    public void refreshSchedule() {
        log.info("Refreshing esports game schedule...");
        Instant now = Instant.now();

        for (String game : ESPORTS_GAMES) {
            try {
                List<LiquipediaEsportsScheduleScraper.EsportsMatch> scraped =
                        scraper.getUpcomingAndLive(game).stream()
                                .filter(match -> match.startTime() != null)
                                .toList();

                // Persist every returned state, including COMPLETED, so the
                // database remains the catalog/history source of truth.
                persistMatchCatalog(game, scraped);

                List<LiquipediaEsportsScheduleScraper.EsportsMatch> discovered = scraped.stream()
                        .filter(match -> !match.finished())
                        .filter(match -> !"COMPLETED".equalsIgnoreCase(match.status()))
                        .sorted(java.util.Comparator.comparing(
                                LiquipediaEsportsScheduleScraper.EsportsMatch::startTime))
                        .toList();

                // Keep all future matches in memory so the UI can show them as
                // Scheduled and the exact start-time task can verify them.
                List<LiquipediaEsportsScheduleScraper.EsportsMatch> future = discovered.stream()
                        .filter(match -> match.startTime().toInstant().isAfter(now))
                        .toList();
                scheduledMatches.put(game, List.copyOf(future));

                for (var match : future) {
                    scheduleStartConfirmation(game, match);
                }

                // A normal 5-minute refresh also updates already-live matches
                // and is the source of truth for completion/removal.
                List<LiquipediaEsportsScheduleScraper.EsportsMatch> live = discovered.stream()
                        .filter(match -> match.isActive(now))
                        .sorted(java.util.Comparator.comparing(
                                LiquipediaEsportsScheduleScraper.EsportsMatch::startTime))
                        .toList();

                updateActiveState(game, live, scraped);
            } catch (Exception e) {
                // A Liquipedia failure must NOT turn a known live game off or
                // erase its scheduled matches. Keep the last known cache/state.
                log.warn("Failed to check esports schedule for game={}: {}", game, e.getMessage());
            }
        }

        log.info("Currently active esports games: {}", activeGames);
    }

    /**
     * Schedule an exact start-time confirmation for a future match. The task
     * re-queries Liquipedia at the scheduled time so a delayed match is not
     * incorrectly activated.
     */
    private void scheduleStartConfirmation(
            String game,
            LiquipediaEsportsScheduleScraper.EsportsMatch match) {

        if (match.matchId() == null || match.startTime() == null || match.finished()) {
            return;
        }

        String key = match.matchId();
        // Refreshing the Liquipedia schedule may move a match's start time.
        // Cancel the previous timer and always schedule against the latest time.
        ScheduledFuture<?> existing = startConfirmationTasks.remove(key);
        if (existing != null && !existing.isDone() && !existing.isCancelled()) {
            existing.cancel(false);
        }

        long delayMs = Math.max(
                0L,
                Duration.between(Instant.now(), match.startTime().toInstant()).toMillis());

        ScheduledFuture<?> future = confirmationExecutor.schedule(
                () -> confirmMatchStart(game, key),
                delayMs,
                TimeUnit.MILLISECONDS);

        ScheduledFuture<?> previous = startConfirmationTasks.put(key, future);
        if (previous != null && !previous.isDone() && !previous.isCancelled()) {
            previous.cancel(false);
        }

        log.info("ESPORTS MATCH SCHEDULED: {} | {} vs {} | starts {}",
                game, match.team1(), match.team2(), match.startTime());
    }

    /**
     * At the scheduled start time, refresh Liquipedia immediately. If the
     * match is really LIVE, activate the game and allow TrackerService to
     * collect it. If Liquipedia moved the start time, reschedule. If the
     * provider temporarily fails, retain the scheduled state and retry.
     */
    private void confirmMatchStart(String game, String matchId) {
        try {
            List<LiquipediaEsportsScheduleScraper.EsportsMatch> latest =
                    scraper.getUpcomingAndLive(game).stream()
                            .filter(match -> match.matchId() != null)
                            .filter(match -> matchId.equals(match.matchId()))
                            .toList();

            if (latest.isEmpty()) {
                log.warn("Start confirmation unavailable for {} | {}. Retaining scheduled state and retrying.",
                        game, matchId);
                retryStartConfirmation(game, matchId);
                return;
            }

            LiquipediaEsportsScheduleScraper.EsportsMatch match = latest.get(0);
            persistMatchCatalog(game, List.of(match));

            Instant now = Instant.now();
            if (match.finished() || "COMPLETED".equalsIgnoreCase(match.status())) {
                removeScheduledMatch(game, matchId);
                startConfirmationTasks.remove(matchId);
                log.info("ESPORTS MATCH COMPLETED BEFORE START: {} | {}", game, matchId);
                return;
            }

            if (match.isActive(now)) {
                activateMatch(game, match);
                startConfirmationTasks.remove(matchId);
                return;
            }

            // Liquipedia says the match is still upcoming. Use its updated
            // start time, if available, and schedule another exact check.
            if (match.startTime() != null && match.startTime().toInstant().isAfter(now)) {
                upsertScheduledMatch(game, match);
                startConfirmationTasks.remove(matchId);
                scheduleStartConfirmation(game, match);
                log.info("ESPORTS MATCH DELAYED: {} | {} | new start {}",
                        game, matchId, match.startTime());
                return;
            }

            // Start time has arrived but Liquipedia has not confirmed LIVE yet.
            retryStartConfirmation(game, matchId);
        } catch (Exception e) {
            log.warn("Start confirmation failed for game={} match={}: {}",
                    game, matchId, e.getMessage());
            retryStartConfirmation(game, matchId);
        }
    }

    private void retryStartConfirmation(String game, String matchId) {
        ScheduledFuture<?> previous = startConfirmationTasks.remove(matchId);
        if (previous != null && !previous.isDone()) {
            previous.cancel(false);
        }

        ScheduledFuture<?> retry = confirmationExecutor.schedule(
                () -> confirmMatchStart(game, matchId),
                START_CONFIRMATION_RETRY_SECONDS,
                TimeUnit.SECONDS);
        startConfirmationTasks.put(matchId, retry);
    }

    private void activateMatch(
            String game,
            LiquipediaEsportsScheduleScraper.EsportsMatch match) {

        activeGames.add(game);
        activeMatches.compute(game, (key, existing) -> {
            List<LiquipediaEsportsScheduleScraper.EsportsMatch> values =
                    existing == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(existing);
            values.removeIf(existingMatch -> match.matchId().equals(existingMatch.matchId()));
            values.add(match);
            values.sort(java.util.Comparator.comparing(
                    LiquipediaEsportsScheduleScraper.EsportsMatch::startTime));
            return List.copyOf(values);
        });

        removeScheduledMatch(game, match.matchId());
        log.info("ESPORTS GAME STARTED: {} | MATCH LIVE: {} | {} vs {}",
                game, match.matchId(), match.team1(), match.team2());
    }

    private void updateActiveState(
            String game,
            List<LiquipediaEsportsScheduleScraper.EsportsMatch> live,
            List<LiquipediaEsportsScheduleScraper.EsportsMatch> scraped) {

        if (!live.isEmpty()) {
            activeGames.add(game);
            activeMatches.put(game, List.copyOf(live));
            return;
        }

        List<LiquipediaEsportsScheduleScraper.EsportsMatch> existing =
                activeMatches.getOrDefault(game, List.of());

        if (existing.isEmpty()) {
            activeGames.remove(game);
            activeMatches.remove(game);
            return;
        }

        // Do not turn a live game off merely because a normal Liquipedia
        // refresh temporarily classifies the match differently or omits it.
        // Stop only when Liquipedia explicitly reports completion/finished,
        // or when the known match has an endTime that has passed.
        List<LiquipediaEsportsScheduleScraper.EsportsMatch> completed = scraped.stream()
                .filter(match -> match.finished()
                        || "COMPLETED".equalsIgnoreCase(match.status()))
                .toList();

        List<LiquipediaEsportsScheduleScraper.EsportsMatch> stillActive = existing.stream()
                .filter(existingMatch -> completed.stream().noneMatch(
                        completedMatch -> existingMatch.matchId().equals(completedMatch.matchId())))
                .filter(existingMatch -> existingMatch.endTime() == null
                        || existingMatch.endTime().toInstant().isAfter(Instant.now()))
                .toList();

        if (!stillActive.isEmpty()) {
            activeGames.add(game);
            activeMatches.put(game, List.copyOf(stillActive));
            return;
        }

        activeGames.remove(game);
        activeMatches.remove(game);
        log.info("ESPORTS GAME STOPPED: {}", game);
    }

    private void upsertScheduledMatch(
            String game,
            LiquipediaEsportsScheduleScraper.EsportsMatch match) {
        scheduledMatches.compute(game, (key, existing) -> {
            List<LiquipediaEsportsScheduleScraper.EsportsMatch> values =
                    existing == null ? new java.util.ArrayList<>() : new java.util.ArrayList<>(existing);
            values.removeIf(existingMatch -> match.matchId().equals(existingMatch.matchId()));
            values.add(match);
            values.sort(java.util.Comparator.comparing(
                    LiquipediaEsportsScheduleScraper.EsportsMatch::startTime));
            return List.copyOf(values);
        });
    }

    private void removeScheduledMatch(String game, String matchId) {
        scheduledMatches.computeIfPresent(game, (key, existing) -> {
            List<LiquipediaEsportsScheduleScraper.EsportsMatch> values = new java.util.ArrayList<>(existing);
            values.removeIf(match -> matchId.equals(match.matchId()));
            return values.isEmpty() ? List.of() : List.copyOf(values);
        });
    }

    private void persistMatchCatalog(String game, List<LiquipediaEsportsScheduleScraper.EsportsMatch> matches) {
        OffsetDateTime seenAt = OffsetDateTime.now(java.time.ZoneOffset.UTC);
        for (var match : matches) {
            if (match.matchId() == null || match.matchId().isBlank()) continue;
            EsportsMatchRecord record = matchRepository.findById(match.matchId()).orElse(null);

            // Older builds used the full Liquipedia match URL as the database
            // primary key. Migrate that legacy record to the normalized
            // match:<hash> identity when the same match URL is seen again.
            if (record == null && match.matchUrl() != null && !match.matchUrl().isBlank()) {
                List<EsportsMatchRecord> legacy = matchRepository.findByMatchUrl(match.matchUrl());
                if (!legacy.isEmpty()) {
                    for (EsportsMatchRecord oldRecord : legacy) {
                        if (!match.matchId().equals(oldRecord.getMatchId())) {
                            matchRepository.delete(oldRecord);
                        }
                    }
                }
            }

            if (record == null) {
                record = new EsportsMatchRecord(match.matchId(), game, match.tournament(), match.tier(),
                        match.team1(), match.team2(), match.team1Score(), match.team2Score(),
                        match.bestOf(), match.winner(), match.finished(), match.startTime(), match.endTime(),
                        match.status(), match.tournamentUrl(), match.matchUrl(), seenAt);
            } else {
                record.update(game, match.tournament(), match.tier(), match.team1(), match.team2(),
                        match.team1Score(), match.team2Score(), match.bestOf(), match.winner(), match.finished(),
                        match.startTime(), match.endTime(), match.status(), match.tournamentUrl(), match.matchUrl(), seenAt);
            }
            matchRepository.save(record);
        }
    }

    /**
     * Returns true when the game currently has
     * an active esports match.
     */
    public boolean isGameActive(String game) {
        if (game == null) {
            return false;
        }
        return activeGames.contains(normalizeGame(game));
    }

    /** Returns all currently scheduled/future Liquipedia matches for a game. */
    public List<LiquipediaEsportsScheduleScraper.EsportsMatch> getScheduledMatches(String game) {
        if (game == null) {
            return List.of();
        }
        return scheduledMatches.getOrDefault(normalizeGame(game), List.of());
    }

    /** Returns all active Liquipedia matches for a game. */
    public List<LiquipediaEsportsScheduleScraper.EsportsMatch> getActiveMatches(String game) {
        if (game == null) {
            return List.of();
        }
        return activeMatches.getOrDefault(normalizeGame(game), List.of());
    }

    /** Backward-compatible convenience method returning the first active match. */
    public Optional<LiquipediaEsportsScheduleScraper.EsportsMatch> getActiveMatch(String game) {
        return getActiveMatches(game).stream().findFirst();
    }

    public boolean isManagedGame(String game) {
        if (game == null) {
            return false;
        }
        return ESPORTS_GAMES.contains(normalizeGame(game));
    }

    private String normalizeGame(String game) {

        String value = game.trim()
                .toUpperCase()
                .replace('_', ' ');

        return switch (value) {
            case "CS2", "COUNTER STRIKE", "COUNTER-STRIKE" -> "COUNTER-STRIKE 2";
            case "LOL" -> "LEAGUE OF LEGENDS";
            case "DOTA2" -> "DOTA 2";
            default -> value;
        };
    }

    /**
     * Returns a snapshot of currently active games.
     */
    public Set<String> getActiveGames() {
        return Set.copyOf(activeGames);
    }

    /**
     * Returns all games managed by this scheduler.
     */
    public List<String> getManagedGames() {
        return ESPORTS_GAMES;
    }

    /**
     * Manually refresh the schedule.
     *
     * Useful for testing from a controller.
     */
    public void refreshNow() {
        refreshSchedule();
    }

    @jakarta.annotation.PreDestroy
    public void shutdown() {
        confirmationExecutor.shutdownNow();
        startConfirmationTasks.clear();
    }

}