package com.valorant.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** Persisted VLR map tracker state; round data is stored as normalized JSON for now. */
@Entity
@Table(name = "vlr_live_map_tracks")
public class VlrLiveMapTrack {
    @Id
    @Column(length = 420)
    private String id;

    @Column(name = "stremng_match_id", nullable = false, length = 300)
    private String stremngMatchId;
    @Column(nullable = false, length = 100)
    private String game;
    @Column(nullable = false, length = 1000)
    private String matchUrl;
    @Column(nullable = false, length = 20)
    private String gameId;
    @Column(length = 300) private String team1;
    @Column(length = 300) private String team2;
    @Column(nullable = false, length = 30) private String trackingStatus = "TRACKING";
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false) private int nextRound = 1;
    private Instant createdAt;
    private Instant lastPollAt;
    private Instant lastSuccessAt;
    private Instant nextPollAt;
    private Instant lastSeenWithoutRoundAt;
    private Instant firstSeenWithRoundAt;
    private Instant pendingRoundMissingSince;
    private Instant pendingRoundFirstSeenAt;
    @Column(nullable = false) private int consecutiveFailures;
    @Column(length = 500) private String lastError;
    @Column(columnDefinition = "TEXT") private String roundsJson = "[]";
    @Column(columnDefinition = "TEXT") private String latestResponseJson = "{}";

    protected VlrLiveMapTrack() {}

    public VlrLiveMapTrack(String id, String stremngMatchId, String game, String matchUrl,
                           String gameId, String team1, String team2, Instant now) {
        this.id = id;
        this.stremngMatchId = stremngMatchId;
        this.game = game;
        this.matchUrl = matchUrl;
        this.gameId = gameId;
        this.team1 = team1;
        this.team2 = team2;
        this.createdAt = now;
        this.nextPollAt = now;
    }

    public void markPollStarted(Instant now) { this.lastPollAt = now; this.nextPollAt = now.plusSeconds(5); }
    public void markSuccess(Instant now, String responseJson, String roundsJson, int nextRound) {
        this.lastSuccessAt = now; this.lastError = null; this.consecutiveFailures = 0;
        this.latestResponseJson = responseJson; this.roundsJson = roundsJson;
        this.nextRound = nextRound; this.trackingStatus = "TRACKING"; this.nextPollAt = now.plusSeconds(5);
    }
    public void markNotReady(Instant now) {
        if (pendingRoundMissingSince == null) pendingRoundMissingSince = now;
        lastSeenWithoutRoundAt = now;
        this.trackingStatus = "WAITING_FOR_ROUND"; this.nextPollAt = now.plusSeconds(5);
    }
    public void markRoundSeen(Instant now) {
        pendingRoundFirstSeenAt = now;
        if (firstSeenWithRoundAt == null) firstSeenWithRoundAt = now;
    }
    public void clearPendingRoundWindow() {
        pendingRoundMissingSince = null; pendingRoundFirstSeenAt = null; lastSeenWithoutRoundAt = null;
    }
    public void markFailure(Instant now, String error) {
        this.consecutiveFailures++;
        this.lastError = error == null ? "Request failed" : error.substring(0, Math.min(500, error.length()));
        this.trackingStatus = "DEGRADED";
        long backoff = Math.min(60, 5L << Math.min(3, Math.max(0, consecutiveFailures - 1)));
        this.nextPollAt = now.plusSeconds(backoff);
    }
    public void stop(String status) { this.enabled = false; this.trackingStatus = status; }

    public String getId() { return id; }
    public String getStremngMatchId() { return stremngMatchId; }
    public String getGame() { return game; }
    public String getMatchUrl() { return matchUrl; }
    public String getGameId() { return gameId; }
    public String getTeam1() { return team1; }
    public String getTeam2() { return team2; }
    public String getTrackingStatus() { return trackingStatus; }
    public boolean isEnabled() { return enabled; }
    public int getNextRound() { return nextRound; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getLastPollAt() { return lastPollAt; }
    public Instant getLastSuccessAt() { return lastSuccessAt; }
    public Instant getNextPollAt() { return nextPollAt; }
    public Instant getLastSeenWithoutRoundAt() { return lastSeenWithoutRoundAt; }
    public Instant getFirstSeenWithRoundAt() { return firstSeenWithRoundAt; }
    public Instant getPendingRoundMissingSince() { return pendingRoundMissingSince; }
    public Instant getPendingRoundFirstSeenAt() { return pendingRoundFirstSeenAt; }
    public int getConsecutiveFailures() { return consecutiveFailures; }
    public String getLastError() { return lastError; }
    public String getRoundsJson() { return roundsJson; }
    public String getLatestResponseJson() { return latestResponseJson; }
}
