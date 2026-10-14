package com.valorant.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/** One persisted VLR map/game associated with a scheduled Stremng match. */
@Entity
@Table(name = "vlr_scheduled_matches")
public class VlrScheduledMatch {
    @Id
    @Column(length = 420)
    private String id;
    @Column(name = "stremng_match_id", nullable = false, length = 300)
    private String stremngMatchId;
    @Column(nullable = false, length = 100)
    private String game;
    @Column(name = "match_date", nullable = false)
    private LocalDate matchDate;
    @Column(name = "scheduled_at", nullable = false)
    private Instant scheduledAt;
    @Column(name = "match_url", nullable = false, length = 1000)
    private String matchUrl;
    @Column(name = "vlr_match_id", length = 30)
    private String vlrMatchId;
    @Column(name = "vlr_game_id", nullable = false, length = 20)
    private String vlrGameId;
    @Column(name = "team1", length = 300) private String team1;
    @Column(name = "team2", length = 300) private String team2;
    @Column(name = "tournament", length = 500) private String tournament;
    @Column(name = "discovery_status", nullable = false, length = 30)
    private String discoveryStatus = "DISCOVERED";
    @Column(name = "polling_status", nullable = false, length = 30)
    private String pollingStatus = "WAITING";
    @Column(name = "discovered_at", nullable = false)
    private Instant discoveredAt;
    @Column(name = "polling_started_at") private Instant pollingStartedAt;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "last_error", length = 500) private String lastError;

    protected VlrScheduledMatch() {}

    public VlrScheduledMatch(String id, String stremngMatchId, String game, LocalDate matchDate,
            Instant scheduledAt, String matchUrl, String vlrMatchId, String vlrGameId,
            String team1, String team2, String tournament, Instant discoveredAt) {
        this.id = id; this.stremngMatchId = stremngMatchId; this.game = game;
        this.matchDate = matchDate; this.scheduledAt = scheduledAt; this.matchUrl = matchUrl;
        this.vlrMatchId = vlrMatchId; this.vlrGameId = vlrGameId; this.team1 = team1;
        this.team2 = team2; this.tournament = tournament; this.discoveredAt = discoveredAt; this.nextAttemptAt = scheduledAt;
    }

    public void markPollingStarted(Instant at) { this.pollingStatus = "POLLING"; this.pollingStartedAt = at; this.nextAttemptAt = null; this.lastError = null; }
    public void markCompleted() { this.pollingStatus = "COMPLETED"; }
    public void markSkippedSeriesDecided() { this.pollingStatus = "SKIPPED_SERIES_DECIDED"; }
    public void markError(String message) { this.lastError = message == null ? "Unknown error" : message.substring(0, Math.min(500, message.length())); this.nextAttemptAt = Instant.now().plusSeconds(30); }
    public String getId() { return id; }
    public String getStremngMatchId() { return stremngMatchId; }
    public String getGame() { return game; }
    public LocalDate getMatchDate() { return matchDate; }
    public Instant getScheduledAt() { return scheduledAt; }
    public String getMatchUrl() { return matchUrl; }
    public String getVlrMatchId() { return vlrMatchId; }
    public String getVlrGameId() { return vlrGameId; }
    public String getTeam1() { return team1; }
    public String getTeam2() { return team2; }
    public String getTournament() { return tournament; }
    public String getDiscoveryStatus() { return discoveryStatus; }
    public String getPollingStatus() { return pollingStatus; }
    public Instant getDiscoveredAt() { return discoveredAt; }
    public Instant getPollingStartedAt() { return pollingStartedAt; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public String getLastError() { return lastError; }
}
