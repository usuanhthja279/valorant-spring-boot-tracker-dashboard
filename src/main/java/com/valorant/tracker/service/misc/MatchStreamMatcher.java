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
            Map.entry("navi", List.of("navi", "natus vincere")),
            Map.entry("falcons", List.of("falcons", "team falcons", "falcons esports")),
            Map.entry("g2", List.of("g2", "g2 esports")),
            Map.entry("spirit", List.of("spirit", "team spirit")),
            Map.entry("furia", List.of("furia", "furia esports")),
            Map.entry("aurora", List.of("aurora", "aurora gaming")),
            Map.entry("legacy", List.of("legacy", "legacy esports")),
            Map.entry("m80", List.of("m80", "m80 esports")),
            Map.entry("9z", List.of("9z", "9z team")),
            Map.entry("bb", List.of("bb", "betboom", "betboom team")),
            Map.entry("vitality", List.of("vitality", "team vitality")),
            Map.entry("liquid", List.of("liquid", "team liquid")),
            Map.entry("nrg", List.of("nrg", "nrg esports")),
            Map.entry("cloud9", List.of("cloud9", "cloud 9")),
            Map.entry("virtus pro", List.of("virtus pro", "vp")),
            Map.entry("heroic", List.of("heroic", "heroic esports")),
            Map.entry("mouz", List.of("mouz", "mousesports")),
            Map.entry("faze", List.of("faze", "faze clan")),
            Map.entry("fnatic", List.of("fnatic", "fnatic esports")),
            Map.entry("gamerlegion", List.of("gamerlegion", "gamer legion")),
            Map.entry("astralis", List.of("astralis", "astralis esports")),
            Map.entry("pain", List.of("pain", "pain gaming")),
            Map.entry("imperial", List.of("imperial", "imperial esports"))
    );

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
        if (phrase == null || phrase.isBlank()) return false;
        return text.contains(phrase);
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