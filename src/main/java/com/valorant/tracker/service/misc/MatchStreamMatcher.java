package com.valorant.tracker.service.misc;

import com.valorant.tracker.model.LiveStream;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

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
     * Canonical team names -> known title/broadcast aliases.
     * Keys and values are normalized before comparison.
     */
    private static final Map<String, List<String>> TEAM_ALIASES = Map.ofEntries(
            // Cross-game organizations / commonly broadcast names
            Map.entry("tl", List.of("tl", "team liquid", "liquid")),
            Map.entry("pv", List.of("pv", "parivision", "pari vision")),
            Map.entry("navi", List.of("navi", "natus vincere")),
            Map.entry("team secret", List.of("ts", "team secret", "secret")),
            Map.entry("falcons", List.of("falcons", "team falcons", "falcons esports")),
            Map.entry("g2", List.of("g2", "g2 esports")),
            Map.entry("spirit", List.of("spirit", "team spirit")),
            Map.entry("furia", List.of("furia", "furia esports")),
            Map.entry("aurora", List.of("aurora", "aurora gaming", "aurora esports")),
            Map.entry("legacy", List.of("legacy", "legacy esports")),
            Map.entry("m80", List.of("m80", "m80 esports")),
            Map.entry("9z", List.of("9z", "9z team")),
            Map.entry("bb", List.of("bb", "betboom", "betboom team", "betboom team")),
            Map.entry("betboom", List.of("betboom", "betboom team", "bb")),
            Map.entry("vitality", List.of("vitality", "team vitality")),
            Map.entry("liquid", List.of("liquid", "team liquid", "liquid esports", "tl")),
            Map.entry("nrg", List.of("nrg", "nrg esports")),
            Map.entry("cloud9", List.of("cloud9", "cloud 9", "c9")),
            Map.entry("virtus pro", List.of("virtus pro", "vp")),
            Map.entry("heroic", List.of("heroic", "heroic esports")),
            Map.entry("mouz", List.of("mouz", "mousesports")),
            Map.entry("faze", List.of("faze", "faze clan", "faze vegas")),
            Map.entry("fnatic", List.of("fnatic", "fnatic esports")),
            Map.entry("gamerlegion", List.of("gamerlegion", "gamer legion", "gl")),
            Map.entry("astralis", List.of("astralis", "astralis esports")),
            Map.entry("pain", List.of("pain", "pain gaming")),
            Map.entry("imperial", List.of("imperial", "imperial esports")),
            Map.entry("t1", List.of("t1", "sk telecom t1", "sk telecom", "t1 esports")),
            Map.entry("gen g", List.of("gen g", "gen.g", "gen g esports", "geng")),
            Map.entry("sentinels", List.of("sentinels", "sen")),
            Map.entry("paper rex", List.of("paper rex", "prx")),
            Map.entry("rex regum qeon", List.of("rex regum qeon", "rrq")),
            Map.entry("team heretics", List.of("team heretics", "heretics", "th")),
            Map.entry("bbl", List.of("bbl", "bbl esports")),
            Map.entry("fut", List.of("fut", "fut esports")),
            Map.entry("karmine corp", List.of("karmine corp", "karmine", "kc")),
            Map.entry("team bds", List.of("team bds", "bds")),
            Map.entry("mibr", List.of("mibr", "mibr esports")),
            Map.entry("loud", List.of("loud", "loud esports")),
            Map.entry("kru", List.of("kru", "kru esports", "krü")),
            Map.entry("drx", List.of("drx", "drx esports")),
            Map.entry("rrq", List.of("rrq", "rex regum qeon")),
            Map.entry("edward gaming", List.of("edward gaming", "edg")),
            Map.entry("bilibili gaming", List.of("bilibili gaming", "bilibili", "blg")),
            Map.entry("weibo gaming", List.of("weibo gaming", "weibo", "wbg")),
            Map.entry("top esports", List.of("top esports", "tes")),
            Map.entry("jd gaming", List.of("jd gaming", "jdg")),
            Map.entry("jdg", List.of("jdg", "jd gaming")),
            Map.entry("hanwha life esports", List.of("hanwha life esports", "hanwha life", "hle")),
            Map.entry("kt rolster", List.of("kt rolster", "kt", "kt rolster esports")),
            Map.entry("dplus kia", List.of("dplus kia", "dplus", "dk", "damwon kia", "damwon gaming")),
            Map.entry("g2 esports", List.of("g2 esports", "g2")),
            Map.entry("flyquest", List.of("flyquest", "flyquest gaming", "flyquest red")),
            Map.entry("100 thieves", List.of("100 thieves", "100t")),
            Map.entry("mad lions", List.of("mad lions", "mad", "mad lions koi", "mkoI")),
            Map.entry("psg talon", List.of("psg talon", "psg", "talon")),
            Map.entry("gam esports", List.of("gam esports", "gam")),
            
            Map.entry("og", List.of("og", "og esports")),
            Map.entry("gaimin gladiators", List.of("gaimin gladiators", "gg", "gaimin")),
            Map.entry("tundra esports", List.of("tundra esports", "tundra")),
            Map.entry("nigma galaxy", List.of("nigma galaxy", "nigma")),
            Map.entry("xtreme gaming", List.of("xtreme gaming", "xtreme", "xg")),
            Map.entry("azure ray", List.of("azure ray", "azr")),
            Map.entry("parivision", List.of("parivision", "pari")),
            Map.entry("pari", List.of("pari", "pv", "pari vision", "pvision", "parivision")),
            Map.entry("wbg", List.of("wbg", "weibo gaming")),
            Map.entry("darkzero", List.of("darkzero", "darkzero esports", "dz")),
            Map.entry("spacestation gaming", List.of("spacestation gaming", "spacestation", "ssg")),
            Map.entry("soniqs", List.of("soniqs", "soniqs esports")),
            Map.entry("w7m esports", List.of("w7m esports", "w7m")),
            Map.entry("scarz", List.of("scarz", "scarZ", "scz")),
            
            Map.entry("crazy raccoon", List.of("crazy raccoon", "cr")),
            Map.entry("toronto defiant", List.of("toronto defiant", "defiant", "td")),
            Map.entry("seoul dynasty", List.of("seoul dynasty", "dynasty")),
            Map.entry("seoul infernal", List.of("seoul infernal", "infernal")),
            Map.entry("dallas fuel", List.of("dallas fuel", "fuel")),
            Map.entry("houston outlaws", List.of("houston outlaws", "outlaws")),
            Map.entry("hangzhou spark", List.of("hangzhou spark", "spark")),
            Map.entry("twisted minds", List.of("twisted minds", "tm")),
            Map.entry("danawa e arena", List.of("danawa e arena", "danawa", "dnw")),
            Map.entry("17 gaming", List.of("17 gaming", "17", "17gaming")),
            Map.entry("tianba", List.of("tianba", "tianba esports")),
            Map.entry("four angry men", List.of("four angry men", "4am")),
            
            Map.entry("global esports", List.of("global esports", "ge")),
            Map.entry("s8ul esports", List.of("s8ul esports", "s8ul")),
            Map.entry("gods reign", List.of("gods reign", "gr")),
            Map.entry("boom esports", List.of("boom esports", "boom")),
            Map.entry("alter ego", List.of("alter ego", "ae")),
            Map.entry("todak", List.of("todak", "todak esports")),
            Map.entry("natus vincere", List.of("natus vincere", "navi")),
            
            Map.entry("complexity", List.of("complexity gaming", "complexity", "coL")),
            Map.entry("oxygen", List.of("oxygen esports", "oxygen", "oxg")),
            Map.entry("gen g mobil1 racing", List.of("gen.g mobil1 racing", "gen.g", "gen g", "geng")),
            Map.entry("gentle mates", List.of("gentle mates", "gentlemates", "m8")),
            Map.entry("rule one", List.of("rule one", "r1")),
            Map.entry("team falcons", List.of("team falcons", "falcons")),
            Map.entry("paris gentle mates", List.of("paris gentle mates", "gentle mates", "m8")),
            Map.entry("faze clan", List.of("faze clan", "faze")),
            Map.entry("luminosity gaming", List.of("luminosity gaming", "luminosity", "lg")),
            Map.entry("alliance", List.of("alliance", "alliance esports")),
            Map.entry("tsm", List.of("tsm", "team solo mid")),
            Map.entry("complexity gaming", List.of("complexity gaming", "complexity", "col")),

            // Fortnite / Call of Duty
            Map.entry("optic gaming", List.of("optic gaming", "optic", "opTic")),
            Map.entry("los angeles thieves", List.of("los angeles thieves", "la thieves", "lat")),
            Map.entry("toronto ultra", List.of("toronto ultra", "ultra")),
            Map.entry("boston breach", List.of("boston breach", "breach")),
            Map.entry("vegas falcons", List.of("vegas falcons", "falcons")),
            Map.entry("atlanta faze", List.of("atlanta faze", "atl faze")),
            Map.entry("dignitas", List.of("dignitas", "dig")),
            Map.entry("xset", List.of("xset", "xSET")),

            // Mobile Legends: Bang Bang
            Map.entry("onic", List.of("onic", "onic esports", "onic id")),
            Map.entry("fnatic onic", List.of("fnatic onic", "fnatic onic esports", "onic")),
            Map.entry("blacklist international", List.of("blacklist international", "blacklist", "blck")),
            Map.entry("ap bren", List.of("ap bren", "bren esports", "bren")),
            Map.entry("echo", List.of("echo", "echo esports")),
            Map.entry("rsg", List.of("rsg", "rsg philippines", "rsg ph")),
            Map.entry("aurora gaming", List.of("aurora gaming", "aurora", "aurora mlbb")),
            Map.entry("evos", List.of("evos", "evos esports")),
            Map.entry("geekay", List.of("geekay", "geek fam", "geekay esports")),

            // Free Fire
            Map.entry("fluxo", List.of("fluxo", "fluxo esports")),
            Map.entry("magic squad", List.of("magic squad", "magic squad esports")),
            Map.entry("corinthians esports", List.of("corinthians esports", "corinthians")),
            Map.entry("w7m", List.of("w7m", "w7m esports")),
            Map.entry("pain gaming", List.of("pain gaming", "paiN", "pain")),

            // PUBG / PUBG Mobile

            // EA Sports FC / football esports organizations
            Map.entry("manchester city esports", List.of("manchester city esports", "manchester city", "man city")),
            Map.entry("ajax esports", List.of("ajax esports", "ajax")),
            Map.entry("fc bayern esports", List.of("fc bayern esports", "bayern esports", "bayern")),
            Map.entry("psv esports", List.of("psv esports", "psv")),
            Map.entry("futwiz", List.of("futwiz", "futwiz fc")),

            // Common regional/secondary aliases
            Map.entry("giants", List.of("giants", "giants gaming", "giantsx")),
            Map.entry("zeta division", List.of("zeta division", "zeta")),
            Map.entry("detonation focusme", List.of("detonation focusme", "dfm", "detonationfm")),
            Map.entry("trace esports", List.of("trace esports", "trace")),
            Map.entry("tyloo", List.of("tyloo", "tyloo esports")),
            Map.entry("nongshim redforce", List.of("nongshim redforce", "nongshim", "ns")));

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

        List<String> knownAliases = TEAM_ALIASES.get(normalized);
        if (knownAliases != null) {
            for (String alias : knownAliases) {
                addUnique(values, normalize(alias));
            }
        }

        // Also support compact forms for names such as "Gamer Legion".
        String compact = normalized.replace(" ", "");
        if (compact.length() >= 4) {
            addUnique(values, compact);
        }

        return values;
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
