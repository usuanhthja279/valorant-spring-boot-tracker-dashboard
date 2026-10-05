package com.valorant.tracker.constant;

public class URLData {
    public enum YoutubeURL {
        VALORANT_LIVE("https://www.youtube.com/@VALORANTLIVE"),
        VALORANT_TOPIC("https://www.youtube.com/channel/UCiMRGE8Sc6oxIGuu_JxFoHg/live"),
        CS2_LIVE("https://www.youtube.com/@CS2LIVE"),
        CS2_TOPIC("https://www.youtube.com/channel/UCiMRGE8Sc6oxIGuu_JxFoHg/live");

        private final String url;

        YoutubeURL(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }

    public enum TwitchURL {
        VALORANT("https://www.twitch.tv/valorant"),
        CS2("https://www.twitch.tv/cs2");

        private final String url;

        TwitchURL(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }

    public enum KickURL {
        VALORANT("https://kick.com/category/valorant?sort=viewers_high_to_low"),
        CS2("https://kick.com/category/valorant?sort=viewers_high_to_low");

        private final String url;

        KickURL(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }
}
