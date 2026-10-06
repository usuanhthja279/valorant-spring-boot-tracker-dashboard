package com.valorant.tracker.service.misc;

import com.valorant.tracker.model.LiveStream;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Associates a live stream with the currently active Liquipedia match.
 *
 * This deliberately uses a confidence score instead of exact title matching:
 * broadcasters frequently change title punctuation, abbreviate team names, or
 * omit the tournament name entirely.
 */
@Service
public class MatchStreamMatcher {

    private static final int MATCH_THRESHOLD = 45;

    public MatchMatchResult match(LiveStream stream,
                                  LiquipediaEsportsScheduleScraper.EsportsMatch match,
                                  String game) {
        if (stream == null || match == null || !match.isActive(java.time.Instant.now())) {
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
        return channel.contains(t1) || channel.contains(t2)
                || channel.contains("official")
                || channel.contains("esl")
                || channel.contains("riot")
                || channel.contains("vlr")
                || channel.contains("blast")
                || channel.contains("pgl")
                || channel.contains("esports");
    }

    private boolean hasMatchSeparator(String title) {
        return title.contains(" vs ") || title.contains(" v ") || title.contains(" versus ")
                || title.contains(" vs. ") || title.contains(" - ");
    }

    private boolean containsAlias(String text, List<String> aliases) {
        for (String alias : aliases) {
            if (!alias.isBlank() && containsPhrase(text, alias)) return true;
        }
        return false;
    }

    private boolean containsPhrase(String text, String phrase) {
        if (phrase == null || phrase.isBlank()) return false;
        return text.contains(phrase);
    }

    private List<String> aliases(String team) {
        String normalized = normalize(team);
        List<String> values = new ArrayList<>();
        if (!normalized.isBlank()) values.add(normalized);
        String compact = normalized.replace(" ", "");
        if (compact.length() >= 4 && !compact.equals(normalized)) values.add(compact);
        return values;
    }

    private String normalize(String value) {
        if (value == null) return "";
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", " ")
                .trim()
                .replaceAll("\\s+", " ");
    }

    public record MatchMatchResult(String matchId, int score, List<String> reasons) {
        public static MatchMatchResult noMatch() {
            return new MatchMatchResult(null, 0, List.of());
        }
        public boolean matched() { return matchId != null; }
    }
}
