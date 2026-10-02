package com.valorant.tracker.model;

import jakarta.persistence.*;
import java.time.OffsetDateTime;

@Entity
@Table(name = "snapshots", indexes = {
    @Index(name = "idx_snapshots_timestamp", columnList = "timestamp")
})
public class Snapshot {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  Long id;

  OffsetDateTime timestamp;
  long youtubeViewers, twitchViewers, kickViewers, combinedViewers;
  int youtubeStreams, twitchStreams, kickStreams;
  Boolean youtubeAvailable, twitchAvailable, kickAvailable;

  protected Snapshot() {}

  public Snapshot(
      OffsetDateTime timestamp,
      long youtubeViewers,
      long twitchViewers,
      long kickViewers,
      int youtubeStreams,
      int twitchStreams,
      int kickStreams,
      boolean youtubeAvailable,
      boolean twitchAvailable,
      boolean kickAvailable) {
    this.timestamp = timestamp;
    this.youtubeViewers = youtubeViewers;
    this.twitchViewers = twitchViewers;
    this.kickViewers = kickViewers;
    this.combinedViewers = youtubeViewers + twitchViewers + kickViewers;
    this.youtubeStreams = youtubeStreams;
    this.twitchStreams = twitchStreams;
    this.kickStreams = kickStreams;
    this.youtubeAvailable = youtubeAvailable;
    this.twitchAvailable = twitchAvailable;
    this.kickAvailable = kickAvailable;
  }

  public Long getId() { return id; }
  public OffsetDateTime getTimestamp() { return timestamp; }
  public long getYoutubeViewers() { return youtubeViewers; }
  public long getTwitchViewers() { return twitchViewers; }
  public long getKickViewers() { return kickViewers; }
  public long getCombinedViewers() { return combinedViewers; }
  public int getYoutubeStreams() { return youtubeStreams; }
  public int getTwitchStreams() { return twitchStreams; }
  public int getKickStreams() { return kickStreams; }
  public Boolean getYoutubeAvailable() { return youtubeAvailable; }
  public Boolean getTwitchAvailable() { return twitchAvailable; }
  public Boolean getKickAvailable() { return kickAvailable; }
}
