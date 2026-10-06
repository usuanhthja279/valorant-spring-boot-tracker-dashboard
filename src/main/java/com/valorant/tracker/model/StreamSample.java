package com.valorant.tracker.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;

@Entity
@Table(name = "stream_samples", indexes = {
    @Index(name = "idx_stream_samples_timestamp", columnList = "timestamp"),
    @Index(name = "idx_stream_samples_timestamp_id", columnList = "timestamp,id"),
    @Index(name = "idx_stream_samples_channel_time", columnList = "channel,timestamp"),
    @Index(name = "idx_stream_samples_channel_time_id", columnList = "channel,timestamp,id"),
    @Index(name = "idx_stream_samples_platform_time", columnList = "platform,timestamp"),
    @Index(name = "idx_stream_samples_stream_time", columnList = "stream_id,timestamp")
})
public class StreamSample {
  @Id @GeneratedValue(strategy = GenerationType.IDENTITY) Long id;
  OffsetDateTime timestamp;
  String game;
  String platform, streamId, channelId, channel;
  @Column(length = 1000) String title;
  long viewers;
  @Column(length = 1000) String url;

  protected StreamSample() {}

  public StreamSample(OffsetDateTime timestamp, LiveStream stream) {
    this(timestamp, "VALORANT", stream);
  }

  public StreamSample(OffsetDateTime timestamp, String game, LiveStream stream) {
    this.timestamp = timestamp;
    this.game = game;
    this.platform = stream.platform();
    this.streamId = stream.id();
    this.channelId = stream.channelId();
    this.channel = stream.channelTitle();
    this.title = stream.title();
    this.viewers = stream.viewers();
    this.url = stream.url();
  }

  public Long getId() { return id; }
  public OffsetDateTime getTimestamp() { return timestamp; }
  public String getGame() { return game; }
  public String getPlatform() { return platform; }
  public String getStreamId() { return streamId; }
  public String getChannelId() { return channelId; }
  public String getChannel() { return channel; }
  public String getTitle() { return title; }
  public long getViewers() { return viewers; }
  public String getUrl() { return url; }
}
