package com.valorant.tracker.constant;

public class URLData {
    public enum YoutubeURL {
        VALORANT_LIVE("https://www.youtube.com/results?search_query=valorant&sp=CAMSBBABQAE%253D"),
        VALORANT_TOPIC("https://www.youtube.com/channel/UCiMRGE8Sc6oxIGuu_JxFoHg/live"),
        CS2_LIVE("https://www.youtube.com/results?search_query=cs2&sp=CAMSBBABQAE%253D"),
        CS2_TOPIC("https://www.youtube.com/channel/UCD-6YWTBwjRFHKBDNpbQgyQ/live"),
        LOL_LIVE("https://www.youtube.com/results?search_query=league+of+legends&sp=CAMSBBABQAE%253D"),
        LOL_TOPIC("https://www.youtube.com/channel/UCZtmNrG53nmbq-Ww2VJrxEQ/live"),
        DOTA2_LIVE("https://www.youtube.com/results?search_query=dota2&sp=CAMSBBABQAE%253D"),
        DOTA2_TOPIC("https://www.youtube.com/channel/UCjkem1Rik-q4xKeETu9geUw/live"),
        FORTNITE_LIVE("https://www.youtube.com/results?search_query=fortnite&sp=CAMSBBABQAE%253D"),
        FORTNITE_TOPIC("https://www.youtube.com/results?search_query=fortnite&sp=CAMSBBABQAE%253D"),
        RAINBOW_SIX_SIEGE_LIVE("https://www.youtube.com/results?search_query=rainbow+six+siege&sp=CAMSBBABQAE%253D"),
        RAINBOW_SIX_SIEGE_TOPIC("https://www.youtube.com/results?search_query=rainbow+six+siege&sp=CAMSBBABQAE%253D"),
        ROCKET_LEAGUE_LIVE("https://www.youtube.com/results?search_query=rocket+league&sp=CAMSBBABQAE%253D"),
        ROCKET_LEAGUE_TOPIC("https://www.youtube.com/results?search_query=rocket+league&sp=CAMSBBABQAE%253D"),
        APEX_LEGENDS_LIVE("https://www.youtube.com/results?search_query=apex+legends&sp=CAMSBBABQAE%253D"),
        APEX_LEGENDS_TOPIC("https://www.youtube.com/results?search_query=apex+legends&sp=CAMSBBABQAE%253D"),
        OVERWATCH_2_LIVE("https://www.youtube.com/results?search_query=overwatch+2&sp=CAMSBBABQAE%253D"),
        OVERWATCH_2_TOPIC("https://www.youtube.com/results?search_query=overwatch+2&sp=CAMSBBABQAE%253D"),
        EA_SPORTS_FC_LIVE("https://www.youtube.com/results?search_query=ea+sports+fc&sp=CAMSBBABQAE%253D"),
        EA_SPORTS_FC_TOPIC("https://www.youtube.com/results?search_query=ea+sports+fc&sp=CAMSBBABQAE%253D"),
        PUBG_LIVE("https://www.youtube.com/results?search_query=pubg&sp=CAMSBBABQAE%253D"),
        PUBG_TOPIC("https://www.youtube.com/results?search_query=pubg&sp=CAMSBBABQAE%253D"),
        CALL_OF_DUTY_LIVE("https://www.youtube.com/results?search_query=call+of+duty&sp=CAMSBBABQAE%253D"),
        CALL_OF_DUTY_TOPIC("https://www.youtube.com/results?search_query=call+of+duty&sp=CAMSBBABQAE%253D"),
        MOBILE_LEGENDS_LIVE("https://www.youtube.com/results?search_query=mobile+legends+bang+bang&sp=CAMSBBABQAE%253D"),
        MOBILE_LEGENDS_TOPIC("https://www.youtube.com/results?search_query=mobile+legends+bang+bang&sp=CAMSBBABQAE%253D"),
        FREE_FIRE_LIVE("https://www.youtube.com/results?search_query=free+fire&sp=CAMSBBABQAE%253D"),
        FREE_FIRE_TOPIC("https://www.youtube.com/results?search_query=free+fire&sp=CAMSBBABQAE%253D");

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
        CS2("https://www.twitch.tv/directory/category/counter-strike?sort=VIEWER_COUNT"),
        LOL("https://www.twitch.tv/directory/category/league-of-legends?sort=VIEWER_COUNT"),
        DOTA2("https://www.twitch.tv/directory/category/dota-2?sort=VIEWER_COUNT"),
        FORTNITE("https://www.twitch.tv/directory/category/fortnite?sort=VIEWER_COUNT"),
        RAINBOW_SIX_SIEGE("https://www.twitch.tv/directory/category/tom-clancys-rainbow-six-siege?sort=VIEWER_COUNT"),
        ROCKET_LEAGUE("https://www.twitch.tv/directory/category/rocket-league?sort=VIEWER_COUNT"),
        APEX_LEGENDS("https://www.twitch.tv/directory/category/apex-legends?sort=VIEWER_COUNT"),
        OVERWATCH_2("https://www.twitch.tv/directory/category/overwatch-2?sort=VIEWER_COUNT"),
        EA_SPORTS_FC("https://www.twitch.tv/directory/category/ea-sports-fc-26?sort=VIEWER_COUNT"),
        PUBG("https://www.twitch.tv/directory/category/pubg-battlegrounds?sort=VIEWER_COUNT"),
        CALL_OF_DUTY("https://www.twitch.tv/directory/category/call-of-duty?sort=VIEWER_COUNT"),
        MOBILE_LEGENDS("https://www.twitch.tv/directory/category/mobile-legends-bang-bang?sort=VIEWER_COUNT"),
        FREE_FIRE("https://www.twitch.tv/directory/category/garena-free-fire?sort=VIEWER_COUNT");

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
        CS2("https://kick.com/category/counter-strike-2?sort=viewers_high_to_low"),
        LOL("https://kick.com/category/league-of-legends?sort=viewers_high_to_low"),
        DOTA2("https://kick.com/category/dota-2?sort=viewers_high_to_low"),
        FORTNITE("https://kick.com/category/fortnite?sort=viewers_high_to_low"),
        RAINBOW_SIX_SIEGE("https://kick.com/category/rainbow-six-siege?sort=viewers_high_to_low"),
        ROCKET_LEAGUE("https://kick.com/category/rocket-league?sort=viewers_high_to_low"),
        APEX_LEGENDS("https://kick.com/category/apex-legends?sort=viewers_high_to_low"),
        OVERWATCH_2("https://kick.com/category/overwatch-2?sort=viewers_high_to_low"),
        EA_SPORTS_FC("https://kick.com/category/ea-sports-fc?sort=viewers_high_to_low"),
        PUBG("https://kick.com/category/pubg?sort=viewers_high_to_low"),
        CALL_OF_DUTY("https://kick.com/category/call-of-duty?sort=viewers_high_to_low"),
        MOBILE_LEGENDS("https://kick.com/category/mobile-legends-bang-bang?sort=viewers_high_to_low"),
        FREE_FIRE("https://kick.com/category/free-fire?sort=viewers_high_to_low");

        private final String url;

        KickURL(String url) {
            this.url = url;
        }

        public String getUrl() {
            return url;
        }
    }
}
