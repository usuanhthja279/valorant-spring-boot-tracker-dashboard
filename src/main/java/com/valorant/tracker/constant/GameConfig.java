package com.valorant.tracker.constant;

import java.util.Arrays;

public enum GameConfig {
  VALORANT("VALORANT", "VALORANT", URLData.YoutubeURL.VALORANT_TOPIC.getUrl(), URLData.YoutubeURL.VALORANT_LIVE.getUrl(), URLData.TwitchURL.VALORANT.name(), URLData.KickURL.VALORANT.getUrl()),
  CS2("counter-strike", "Counter-Strike 2", URLData.YoutubeURL.CS2_TOPIC.getUrl(), URLData.YoutubeURL.CS2_LIVE.getUrl(), URLData.TwitchURL.CS2.name(), URLData.KickURL.CS2.getUrl()),
  LOL("League of Legends", "League of Legends", URLData.YoutubeURL.LOL_TOPIC.getUrl(), URLData.YoutubeURL.LOL_LIVE.getUrl(), URLData.TwitchURL.LOL.name(), URLData.KickURL.LOL.getUrl()),
  DOTA2("Dota 2", "Dota 2", URLData.YoutubeURL.DOTA2_TOPIC.getUrl(), URLData.YoutubeURL.DOTA2_LIVE.getUrl(), URLData.TwitchURL.DOTA2.name(), URLData.KickURL.DOTA2.getUrl());

  private final String id;
  private final String displayName;
  private final String youtubeTopicUrl;
  private final String youtubeLiveUrl;
  private final String twitchCategory;
  private final String kickUrl;

  GameConfig(String id, String displayName, String youtubeTopicUrl, String youtubeLiveUrl, String twitchCategory, String kickUrl) {
    this.id = id; this.displayName = displayName; this.youtubeTopicUrl = youtubeTopicUrl;
      this.youtubeLiveUrl = youtubeLiveUrl;
      this.twitchCategory = twitchCategory; this.kickUrl = kickUrl;
  }
  public String getId(){ return id; }
  public String getDisplayName(){ return displayName; }
  public String getYoutubeTopicUrl(){ return youtubeTopicUrl; }
  public String getYoutubeLiveUrl(){ return youtubeLiveUrl; }
  public String getTwitchCategory(){ return twitchCategory; }
  public String getKickUrl(){ return kickUrl; }
  public static GameConfig from(String value){
    if(value == null || value.isBlank()) return VALORANT;
    return Arrays.stream(values()).filter(g -> g.id.equalsIgnoreCase(value) || g.displayName.equalsIgnoreCase(value)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown game: " + value));
  }
}
