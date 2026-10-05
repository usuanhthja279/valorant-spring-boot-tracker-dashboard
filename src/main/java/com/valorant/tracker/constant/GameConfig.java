package com.valorant.tracker.constant;

import java.util.Arrays;

public enum GameConfig {
  VALORANT("VALORANT", "VALORANT", URLData.YoutubeURL.VALORANT_TOPIC.getUrl(), URLData.TwitchURL.VALORANT.name(), URLData.KickURL.VALORANT.getUrl()),
  CS2("counter-strike", "Counter-Strike 2", URLData.YoutubeURL.CS2_TOPIC.getUrl(), URLData.TwitchURL.CS2.name(), URLData.KickURL.CS2.getUrl());

  private final String id;
  private final String displayName;
  private final String youtubeUrl;
  private final String twitchCategory;
  private final String kickUrl;

  GameConfig(String id, String displayName, String youtubeUrl, String twitchCategory, String kickUrl) {
    this.id = id; this.displayName = displayName; this.youtubeUrl = youtubeUrl; this.twitchCategory = twitchCategory; this.kickUrl = kickUrl;
  }
  public String getId(){ return id; }
  public String getDisplayName(){ return displayName; }
  public String getYoutubeUrl(){ return youtubeUrl; }
  public String getTwitchCategory(){ return twitchCategory; }
  public String getKickUrl(){ return kickUrl; }
  public static GameConfig from(String value){
    if(value == null || value.isBlank()) return VALORANT;
    return Arrays.stream(values()).filter(g -> g.id.equalsIgnoreCase(value) || g.displayName.equalsIgnoreCase(value)).findFirst().orElseThrow(() -> new IllegalArgumentException("Unknown game: " + value));
  }
}
