package com.valorant.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "esports_matches")
public class EsportsMatchRecord {
  @Id
  @Column(name = "match_id", length = 300)
  private String matchId;

  @Column(nullable = false, length = 100)
  private String game;
  @Column(length = 500) private String tournament;
  @Column(length = 20) private String tier;
  @Column(length = 300) private String team1;
  @Column(length = 300) private String team2;
  private OffsetDateTime startTime;
  private OffsetDateTime endTime;
  @Column(length = 40) private String status;
  @Column(length = 1000) private String tournamentUrl;
  @Column(length = 1000) private String matchUrl;
  private OffsetDateTime firstSeen;
  private OffsetDateTime lastSeen;

  protected EsportsMatchRecord() {}

  public EsportsMatchRecord(String matchId, String game, String tournament, String tier,
                            String team1, String team2, OffsetDateTime startTime,
                            OffsetDateTime endTime, String status, String tournamentUrl,
                            String matchUrl, OffsetDateTime seenAt) {
    this.matchId = matchId;
    update(game, tournament, tier, team1, team2, startTime, endTime, status, tournamentUrl, matchUrl, seenAt);
    this.firstSeen = seenAt;
  }

  public void update(String game, String tournament, String tier, String team1, String team2,
                     OffsetDateTime startTime, OffsetDateTime endTime, String status,
                     String tournamentUrl, String matchUrl, OffsetDateTime seenAt) {
    this.game = game;
    this.tournament = tournament;
    this.tier = tier;
    this.team1 = team1;
    this.team2 = team2;
    this.startTime = startTime;
    this.endTime = endTime;
    this.status = status;
    this.tournamentUrl = tournamentUrl;
    this.matchUrl = matchUrl;
    this.lastSeen = seenAt;
  }

  public String getMatchId() { return matchId; }
  public String getGame() { return game; }
  public String getTournament() { return tournament; }
  public String getTier() { return tier; }
  public String getTeam1() { return team1; }
  public String getTeam2() { return team2; }
  public OffsetDateTime getStartTime() { return startTime; }
  public OffsetDateTime getEndTime() { return endTime; }
  public String getStatus() { return status; }
  public String getTournamentUrl() { return tournamentUrl; }
  public String getMatchUrl() { return matchUrl; }
  public OffsetDateTime getFirstSeen() { return firstSeen; }
  public OffsetDateTime getLastSeen() { return lastSeen; }
}
