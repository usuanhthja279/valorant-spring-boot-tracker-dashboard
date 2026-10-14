package com.valorant.tracker.service.misc;

import com.valorant.tracker.model.LiveStream;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Associates a live stream with the currently active Liquipedia match.
 *
 * Matching is intentionally based on team aliases rather than exact title
 * equality. Broadcasters frequently use names such as:
 *
 *   Liquipedia: Falcons vs NAVI
 *   Stream:     Team Falcons vs. Natus Vincere
 *
 * Both must resolve to the same match.
 */
@Service
public class MatchStreamMatcher {

    private static final int MATCH_THRESHOLD = 45;

    /**
     * Possible Liquipedia team-name variations -> title/broadcast aliases.
     * A key list groups names that refer to the same team; its value list
     * contains all aliases that may appear in stream titles/channel names.
     * Keys and values are normalized before comparison.
     */
    private static final Map<List<String>, List<String>> TEAM_ALIASES = Map.ofEntries(
            // Cross-game organizations / commonly broadcast names
            Map.entry(List.of("team liquid", "liquid", "tl", "tliquid"), List.of("tl", "team liquid", "liquid", "t liquid")),
            Map.entry(List.of("pv"), List.of("pv", "parivision", "pari vision")),
            Map.entry(List.of("navi"), List.of("navi", "natus vincere")),
            Map.entry(List.of("team secret", "secret", "ts", "tsecret"), List.of("ts", "tsecret", "team secret", "secret")),
            Map.entry(List.of("team falcons", "falcons", "tfalcons"), List.of("falcons", "team falcons", "tfalcons", "falcons esports")),
            Map.entry(List.of("g2", "g2 esports"), List.of("g2", "g2 esports")),
            Map.entry(List.of("team spirit", "spirit", "ts", "tspirit"), List.of("ts", "tspirit", "spirit", "team spirit")),
            Map.entry(List.of("furia"), List.of("furia", "furia esports")),
            Map.entry(List.of("aurora"), List.of("aurora", "aurora gaming", "aurora esports")),
            Map.entry(List.of("legacy"), List.of("legacy", "legacy esports")),
            Map.entry(List.of("m80"), List.of("m80", "m80 esports")),
            Map.entry(List.of("9z"), List.of("9z", "9z team")),
            Map.entry(List.of("bb"), List.of("bb", "betboom", "betboom team", "betboom team")),
            Map.entry(List.of("betboom"), List.of("betboom", "betboom team", "bb")),
            Map.entry(List.of("team vitality", "vitality", "vit", "tvitality"), List.of("vit", "tvitality", "vitality", "team vitality")),
            Map.entry(List.of("liquid"), List.of("liquid", "team liquid", "liquid esports", "tl")),
            Map.entry(List.of("nrg"), List.of("nrg", "nrg esports")),
            Map.entry(List.of("cloud9"), List.of("cloud9", "cloud 9", "c9")),
            Map.entry(List.of("virtus pro"), List.of("virtus pro", "vp")),
            Map.entry(List.of("heroic"), List.of("heroic", "heroic esports")),
            Map.entry(List.of("mouz"), List.of("mouz", "mousesports")),
            Map.entry(List.of("faze"), List.of("faze", "faze clan", "faze vegas")),
            Map.entry(List.of("fnatic"), List.of("fnatic", "fnatic esports")),
            Map.entry(List.of("gamerlegion"), List.of("gamerlegion", "gamer legion", "gl")),
            Map.entry(List.of("astralis"), List.of("astralis", "astralis esports")),
            Map.entry(List.of("pain"), List.of("pain", "pain gaming")),
            Map.entry(List.of("imperial"), List.of("imperial", "imperial esports")),
            Map.entry(List.of("t1"), List.of("t1", "sk telecom t1", "sk telecom", "t1 esports")),
            Map.entry(List.of("gen g"), List.of("gen g", "gen.g", "gen g esports", "geng")),
            Map.entry(List.of("sentinels"), List.of("sentinels", "sen")),
            Map.entry(List.of("paper rex"), List.of("paper rex", "prx")),
            Map.entry(List.of("rex regum qeon"), List.of("rex regum qeon", "rrq")),
            Map.entry(List.of("team heretics"), List.of("team heretics", "heretics", "th")),
            Map.entry(List.of("bbl", "bbl esports"), List.of("bbl", "bbl esports")),
            Map.entry(List.of("misa esports", "misa", "me"), List.of("misa esports", "misaesports", "misa")),
            Map.entry(List.of("fut"), List.of("fut", "fut esports")),
            Map.entry(List.of("karmine corp"), List.of("karmine corp", "karmine", "kc")),
            Map.entry(List.of("team bds"), List.of("team bds", "bds")),
            Map.entry(List.of("mibr"), List.of("mibr", "mibr esports")),
            Map.entry(List.of("loud"), List.of("loud", "loud esports")),
            Map.entry(List.of("kru"), List.of("kru", "kru esports", "krü")),
            Map.entry(List.of("drx"), List.of("drx", "drx esports")),
            Map.entry(List.of("rrq"), List.of("rrq", "rex regum qeon")),
            Map.entry(List.of("edward gaming"), List.of("edward gaming", "edg")),
            Map.entry(List.of("bilibili gaming"), List.of("bilibili gaming", "bilibili", "blg")),
            Map.entry(List.of("weibo gaming"), List.of("weibo gaming", "weibo", "wbg")),
            Map.entry(List.of("top esports"), List.of("top esports", "tes")),
            Map.entry(List.of("jd gaming"), List.of("jd gaming", "jdg")),
            Map.entry(List.of("jdg"), List.of("jdg", "jd gaming")),
            Map.entry(List.of("hanwha life esports"), List.of("hanwha life esports", "hanwha life", "hle")),
            Map.entry(List.of("kt rolster"), List.of("kt rolster", "kt", "kt rolster esports")),
            Map.entry(List.of("dplus kia"), List.of("dplus kia", "dplus", "dk", "damwon kia", "damwon gaming")),
            Map.entry(List.of("g2 esports"), List.of("g2 esports", "g2")),
            Map.entry(List.of("flyquest"), List.of("flyquest", "flyquest gaming", "flyquest red")),
            Map.entry(List.of("100 thieves"), List.of("100 thieves", "100t")),
            Map.entry(List.of("mad lions"), List.of("mad lions", "mad", "mad lions koi", "mkoI")),
            Map.entry(List.of("psg talon"), List.of("psg talon", "psg", "talon")),
            Map.entry(List.of("gam esports"), List.of("gam esports", "gam")),
            
            Map.entry(List.of("og"), List.of("og", "og esports")),
            Map.entry(List.of("gaimin gladiators"), List.of("gaimin gladiators", "gg", "gaimin")),
            Map.entry(List.of("tundra esports"), List.of("tundra esports", "tundra")),
            Map.entry(List.of("nigma galaxy"), List.of("nigma galaxy", "nigma")),
            Map.entry(List.of("xtreme gaming"), List.of("xtreme gaming", "xtreme", "xg")),
            Map.entry(List.of("azure ray"), List.of("azure ray", "azr")),
            Map.entry(List.of("parivision"), List.of("parivision", "pari")),
            Map.entry(List.of("pari"), List.of("pari", "pv", "pari vision", "pvision", "parivision")),
            Map.entry(List.of("wbg"), List.of("wbg", "weibo gaming")),
            Map.entry(List.of("darkzero"), List.of("darkzero", "darkzero esports", "dz")),
            Map.entry(List.of("spacestation gaming"), List.of("spacestation gaming", "spacestation", "ssg")),
            Map.entry(List.of("soniqs"), List.of("soniqs", "soniqs esports")),
            Map.entry(List.of("w7m esports"), List.of("w7m esports", "w7m")),
            Map.entry(List.of("scarz"), List.of("scarz", "scarZ", "scz")),
            
            Map.entry(List.of("crazy raccoon"), List.of("crazy raccoon", "cr")),
            Map.entry(List.of("toronto defiant"), List.of("toronto defiant", "defiant", "td")),
            Map.entry(List.of("seoul dynasty"), List.of("seoul dynasty", "dynasty")),
            Map.entry(List.of("seoul infernal"), List.of("seoul infernal", "infernal")),
            Map.entry(List.of("dallas fuel"), List.of("dallas fuel", "fuel")),
            Map.entry(List.of("houston outlaws"), List.of("houston outlaws", "outlaws")),
            Map.entry(List.of("hangzhou spark"), List.of("hangzhou spark", "spark")),
            Map.entry(List.of("twisted minds"), List.of("twisted minds", "tm")),
            Map.entry(List.of("danawa e arena"), List.of("danawa e arena", "danawa", "dnw")),
            Map.entry(List.of("17 gaming"), List.of("17 gaming", "17", "17gaming")),
            Map.entry(List.of("tianba"), List.of("tianba", "tianba esports")),
            Map.entry(List.of("four angry men"), List.of("four angry men", "4am")),
            
            Map.entry(List.of("global esports"), List.of("global esports", "ge")),
            Map.entry(List.of("s8ul esports"), List.of("s8ul esports", "s8ul")),
            Map.entry(List.of("gods reign"), List.of("gods reign", "gr")),
            Map.entry(List.of("boom esports"), List.of("boom esports", "boom")),
            Map.entry(List.of("alter ego"), List.of("alter ego", "ae")),
            Map.entry(List.of("todak"), List.of("todak", "todak esports")),
            Map.entry(List.of("natus vincere"), List.of("natus vincere", "navi")),
            
            Map.entry(List.of("complexity"), List.of("complexity gaming", "complexity", "coL")),
            Map.entry(List.of("oxygen"), List.of("oxygen esports", "oxygen", "oxg")),
            Map.entry(List.of("gen g mobil1 racing"), List.of("gen.g mobil1 racing", "gen.g", "gen g", "geng")),
            Map.entry(List.of("gentle mates"), List.of("gentle mates", "gentlemates", "m8")),
            Map.entry(List.of("rule one"), List.of("rule one", "r1")),
            Map.entry(List.of("team falcons"), List.of("team falcons", "falcons")),
            Map.entry(List.of("paris gentle mates"), List.of("paris gentle mates", "gentle mates", "m8")),
            Map.entry(List.of("faze clan"), List.of("faze clan", "faze")),
            Map.entry(List.of("luminosity gaming"), List.of("luminosity gaming", "luminosity", "lg")),
            Map.entry(List.of("alliance"), List.of("alliance", "alliance esports")),
            Map.entry(List.of("tsm"), List.of("tsm", "team solo mid")),
            Map.entry(List.of("complexity gaming"), List.of("complexity gaming", "complexity", "col")),

            // Fortnite / Call of Duty
            Map.entry(List.of("optic gaming"), List.of("optic gaming", "optic", "opTic")),
            Map.entry(List.of("los angeles thieves"), List.of("los angeles thieves", "la thieves", "lat")),
            Map.entry(List.of("toronto ultra"), List.of("toronto ultra", "ultra")),
            Map.entry(List.of("boston breach"), List.of("boston breach", "breach")),
            Map.entry(List.of("vegas falcons"), List.of("vegas falcons", "falcons")),
            Map.entry(List.of("atlanta faze"), List.of("atlanta faze", "atl faze")),
            Map.entry(List.of("dignitas"), List.of("dignitas", "dig")),
            Map.entry(List.of("xset"), List.of("xset", "xSET")),

            // Mobile Legends: Bang Bang
            Map.entry(List.of("onic"), List.of("onic", "onic esports", "onic id")),
            Map.entry(List.of("fnatic onic"), List.of("fnatic onic", "fnatic onic esports", "onic")),
            Map.entry(List.of("blacklist international"), List.of("blacklist international", "blacklist", "blck")),
            Map.entry(List.of("ap bren"), List.of("ap bren", "bren esports", "bren")),
            Map.entry(List.of("echo"), List.of("echo", "echo esports")),
            Map.entry(List.of("rsg"), List.of("rsg", "rsg philippines", "rsg ph")),
            Map.entry(List.of("aurora gaming"), List.of("aurora gaming", "aurora", "aurora mlbb")),
            Map.entry(List.of("evos"), List.of("evos", "evos esports")),
            Map.entry(List.of("geekay"), List.of("geekay", "geek fam", "geekay esports")),

            // Free Fire
            Map.entry(List.of("fluxo"), List.of("fluxo", "fluxo esports")),
            Map.entry(List.of("magic squad"), List.of("magic squad", "magic squad esports")),
            Map.entry(List.of("corinthians esports"), List.of("corinthians esports", "corinthians")),
            Map.entry(List.of("w7m"), List.of("w7m", "w7m esports")),
            Map.entry(List.of("pain gaming"), List.of("pain gaming", "paiN", "pain")),

            // PUBG / PUBG Mobile

            // EA Sports FC / football esports organizations
            Map.entry(List.of("manchester city esports"), List.of("manchester city esports", "manchester city", "man city")),
            Map.entry(List.of("ajax esports"), List.of("ajax esports", "ajax")),
            Map.entry(List.of("fc bayern esports"), List.of("fc bayern esports", "bayern esports", "bayern")),
            Map.entry(List.of("psv esports"), List.of("psv esports", "psv")),
            Map.entry(List.of("futwiz"), List.of("futwiz", "futwiz fc")),

            // Common regional/secondary aliases
            Map.entry(List.of("giants"), List.of("giants", "giants gaming", "giantsx")),
            Map.entry(List.of("zeta division"), List.of("zeta division", "zeta")),
            Map.entry(List.of("detonation focusme"), List.of("detonation focusme", "dfm", "detonationfm")),
            Map.entry(List.of("trace esports"), List.of("trace esports", "trace")),
            Map.entry(List.of("tyloo"), List.of("tyloo", "tyloo esports")),
            Map.entry(List.of("nongshim redforce", "nongshim", "ns", "ns redforce"), List.of("nongshim redforce", "nongshim", "ns", "ns redforce")),
            // VALORANT / League of Legends / CS2 / Dota 2: additional major teams
            Map.entry(List.of("leviatan", "leviatan esports", "lev"), List.of("lev", "leviatan", "leviatan esports")),
            Map.entry(List.of("evil geniuses", "eg"), List.of("eg", "evil geniuses")),
            Map.entry(List.of("envy", "team envy"), List.of("envy", "team envy")),
            Map.entry(List.of("pcific", "pcific esports"), List.of("pcific", "pcific esports")),
            Map.entry(List.of("ulf esports", "ulf"), List.of("ulf", "ulf esports")),
            Map.entry(List.of("full sense", "fs"), List.of("full sense", "fullsense", "fs")),
            Map.entry(List.of("varrel", "vl"), List.of("varrel", "vl")),
            Map.entry(List.of("all gamers", "ag"), List.of("all gamers", "allgamers", "ag")),
            Map.entry(List.of("funplus phoenix", "fpX", "fpx"), List.of("funplus phoenix", "fpx", "fp")),
            Map.entry(List.of("nova esports", "nova"), List.of("nova esports", "nova")),
            Map.entry(List.of("wolves esports", "wolves"), List.of("wolves esports", "wolves")),
            Map.entry(List.of("titan esports club", "tec"), List.of("titan esports club", "titan", "tec")),
            Map.entry(List.of("dragon ranger gaming", "drg"), List.of("dragon ranger gaming", "drg")),
            Map.entry(List.of("xlg esports", "xlg"), List.of("xlg esports", "xlg")),
            Map.entry(List.of("lyon", "lyon gaming"), List.of("lyon", "lyon gaming")),
            Map.entry(List.of("anyones legend", "anyone's legend", "al"), List.of("anyones legend", "anyone's legend", "al")),
            Map.entry(List.of("bilibili gaming", "bilibili", "blg"), List.of("bilibili gaming", "bilibili", "blg")),
            Map.entry(List.of("weibo gaming", "weibo", "wbg"), List.of("weibo gaming", "weibo", "wbg")),
            Map.entry(List.of("top esports", "tes"), List.of("top esports", "tes")),
            Map.entry(List.of("jd gaming", "jdg"), List.of("jd gaming", "jdg")),
            Map.entry(List.of("lgd gaming", "lgd"), List.of("lgd gaming", "lgd")),
            Map.entry(List.of("royal never give up", "rng"), List.of("royal never give up", "rng")),
            Map.entry(List.of("weibo gaming", "wbg"), List.of("weibo gaming", "weibo", "wbg")),
            Map.entry(List.of("the mongolz", "mongolz"), List.of("the mongolz", "mongolz")),
            Map.entry(List.of("eternal fire", "ef"), List.of("eternal fire", "ef")),
            Map.entry(List.of("pain gaming", "pain"), List.of("pain gaming", "pain")),
            Map.entry(List.of("the union", "union"), List.of("the union", "union")),
            Map.entry(List.of("m80", "m80 esports"), List.of("m80", "m80 esports")),
            Map.entry(List.of("parivision", "pari vision", "pv"), List.of("parivision", "pari vision", "pv")),
            Map.entry(List.of("betboom team", "betboom", "bb"), List.of("betboom team", "betboom", "bb")),
            Map.entry(List.of("team aurora", "aurora gaming", "aurora"), List.of("team aurora", "aurora gaming", "aurora")),
            Map.entry(List.of("xtreme gaming", "xg"), List.of("xtreme gaming", "xtreme", "xg")),
            Map.entry(List.of("team resilience", "resilience"), List.of("team resilience", "resilience")),
            Map.entry(List.of("team vision", "vision"), List.of("team vision", "vision")),
            Map.entry(List.of("iron wing", "1w", "one win"), List.of("iron wing", "1w", "one win")),
            Map.entry(List.of("boom esports", "boom"), List.of("boom esports", "boom")),
            Map.entry(List.of("team yandex", "yandex"), List.of("team yandex", "yandex")),
            Map.entry(List.of("nigma galaxy", "nigma"), List.of("nigma galaxy", "nigma")));

    public MatchMatchResult match(LiveStream stream,
                                  LiquipediaEsportsScheduleScraper.EsportsMatch match,
                                  String game) {
        if (stream == null || match == null || !match.isActive(Instant.now())) {
            return MatchMatchResult.noMatch();
        }

        String title = normalize(stream.title());
        String channel = normalize(stream.channelTitle());
        String tournament = normalize(match.tournament());
        String combined = title + " " + channel;

        List<String> team1Aliases = aliases(match.team1());
        List<String> team2Aliases = aliases(match.team2());

        boolean team1 = containsAlias(combined, team1Aliases);
        boolean team2 = containsAlias(combined, team2Aliases);

        int score = 0;
        List<String> reasons = new ArrayList<>();

        if (team1) {
            score += 20;
            reasons.add("team1");
        }
        if (team2) {
            score += 20;
            reasons.add("team2");
        }
        if (team1 && team2) {
            score += 20;
            reasons.add("both-teams");
        }
        if (!tournament.isBlank() && containsPhrase(title, tournament)) {
            score += 15;
            reasons.add("tournament");
        }
        if (hasMatchSeparator(title)) {
            score += 5;
            reasons.add("match-title-format");
        }
        if (isOfficialLookingChannel(channel, match)) {
            score += 10;
            reasons.add("official-looking-channel");
        }

        // The stream was discovered through this game's provider/category, so
        // game is a supporting signal rather than an independent title match.
        if (!normalize(game).isBlank()) {
            score += 5;
            reasons.add("game-context");
        }

        boolean matched = score >= MATCH_THRESHOLD && (team1 || team2);

        return matched
                ? new MatchMatchResult(match.matchId(), score, List.copyOf(reasons))
                : MatchMatchResult.noMatch();
    }

    private boolean isOfficialLookingChannel(String channel,
                                             LiquipediaEsportsScheduleScraper.EsportsMatch match) {
        if (channel.isBlank()) return false;

        String t1 = normalize(match.team1());
        String t2 = normalize(match.team2());

        return (!t1.isBlank() && channel.contains(t1))
                || (!t2.isBlank() && channel.contains(t2))
                || channel.contains("official")
                || channel.contains("esl")
                || channel.contains("riot")
                || channel.contains("vlr")
                || channel.contains("blast")
                || channel.contains("pgl")
                || channel.contains("valorant")
                || channel.contains("vct")
                || channel.contains("esports");
    }

    private boolean hasMatchSeparator(String title) {
        return title.contains(" vs ")
                || title.contains(" v ")
                || title.contains(" versus ")
                || title.contains(" vs. ")
                || title.contains(" - ");
    }

    private boolean containsAlias(String text, List<String> aliases) {
        for (String alias : aliases) {
            if (!alias.isBlank() && containsPhrase(text, alias)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsPhrase(String text, String phrase) {
        if (text == null || phrase == null || phrase.isBlank()) return false;

        String normalizedText = normalize(text);
        String normalizedPhrase = normalize(phrase);
        if (normalizedPhrase.isBlank()) return false;

        // Match whole normalized phrases. This prevents short aliases such as
        // "bb", "g2", "vp", or "tl" from matching unrelated words.
        String paddedText = " " + normalizedText + " ";
        String paddedPhrase = " " + normalizedPhrase + " ";
        return paddedText.contains(paddedPhrase);
    }

    /**
     * Returns the canonical team name plus known broadcast aliases.
     *
     * Example:
     *   aliases("Falcons") -> [falcons, team falcons, falcons esports]
     *   aliases("NAVI")    -> [navi, natus vincere]
     */
    private List<String> aliases(String team) {
        String normalized = normalize(team);
        if (normalized.isBlank()) {
            return List.of();
        }

        List<String> values = new ArrayList<>();
        addUnique(values, normalized);

        // Liquipedia may use either the full organization name or a shortened
        // form. Match against every key variation in both directions, but do
        // not use arbitrary substring matching for very short aliases (e.g. "tl").
        for (Map.Entry<List<String>, List<String>> entry : TEAM_ALIASES.entrySet()) {
            boolean keyMatches = entry.getKey().stream()
                    .map(this::normalize)
                    .anyMatch(key -> teamNameMatches(normalized, key));
            if (keyMatches) {
                entry.getValue().forEach(alias -> addUnique(values, normalize(alias)));
            }
        }

        // Also support compact forms for names such as "Gamer Legion".
        String compact = normalized.replace(" ", "");
        if (compact.length() >= 4) {
            addUnique(values, compact);
        }
        return values;
    }

    private boolean teamNameMatches(String teamName, String keyVariation) {
        if (teamName.isBlank() || keyVariation.isBlank()) return false;
        if (teamName.equals(keyVariation)) return true;

        // Allow "Vitality" to resolve "Team Vitality", and "TVitality" to
        // resolve a key variation "t vitality" after normalization. For short
        // tags, require equality to avoid collisions such as "TL" in other words.
        int shorterLength = Math.min(teamName.replace(" ", "").length(),
                keyVariation.replace(" ", "").length());
        if (shorterLength <= 3) return false;
        return teamName.contains(keyVariation) || keyVariation.contains(teamName)
                || teamName.replace(" ", "").equals(keyVariation.replace(" ", ""));
    }

    private void addUnique(List<String> values, String value) {
        if (value != null && !value.isBlank() && !values.contains(value)) {
            values.add(value);
        }
    }

    private String normalize(String value) {
        if (value == null) return "";

        return value
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    public record MatchMatchResult(String matchId, int score, List<String> reasons) {
        public static MatchMatchResult noMatch() {
            return new MatchMatchResult(null, 0, List.of());
        }

        public boolean matched() {
            return matchId != null;
        }
    }
}
