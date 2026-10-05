package com.valorant.tracker.constant;

public class URLData {
    public enum YoutubeURL {
        VALORANT_LIVE("https://www.youtube.com/results?search_query=valorant&sp=CAMSBBABQAE%253D"),
        VALORANT_TOPIC("https://www.youtube.com/channel/UCiMRGE8Sc6oxIGuu_JxFoHg/live"),
        CS2_LIVE("https://www.youtube.com/results?search_query=cs2&sp=CAMSBBABQAE%253D"),
        CS2_TOPIC("https://www.youtube.com/channel/UCD-6YWTBwjRFHKBDNpbQgyQ/live");

        private final String url;

        YoutubeURL(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }

    public enum TwitchURL {
        VALORANT("https://www.twitch.tv/directory/category/valorant?sort=VIEWER_COUNT"),
        CS2("https://www.twitch.tv/directory/category/counter-strike?sort=VIEWER_COUNT");

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
        CS2("https://kick.com/category/counter-strike-2?sort=viewers_high_to_low");

        private final String url;

        KickURL(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }
}
