package com.brandsmith.api.det;

import java.util.Locale;
import java.util.Set;

/**
 * Local pronounceability heuristic (S4): vowel-group syllable estimate, consonant cluster
 * penalty, and length bound. Scores 0-100; higher is easier to say.
 */
public final class Pronounceability {

    private static final int MAX_LENGTH = 14;
    private static final Set<String> ALLOWED_CLUSTERS = Set.of(
            "str", "spr", "spl", "scr", "shr", "thr", "chr", "phr", "sch", "squ");

    private Pronounceability() {
    }

    public static int score(String name) {
        if (name == null || name.isBlank()) {
            return 0;
        }
        String w = name.trim().toLowerCase(Locale.ROOT);
        int syllables = ReadingGrade.syllables(w);
        int penalty = 0;

        // ReadingGrade.syllables floors at 1; zero vowel letters means unsayable.
        if (!w.matches(".*[aeiou].*")) {
            penalty += 50;
        } else if (syllables == 0) {
            penalty += 50;
        }

        int len = w.length();
        if (len > MAX_LENGTH) {
            penalty += (len - MAX_LENGTH) * 6;
        }
        if (len < 3) {
            penalty += 15;
        }

        penalty += consonantClusterPenalty(w);

        if (!w.matches("[a-z]+")) {
            penalty += 10;
        }

        return Math.clamp(100 - penalty, 0, 100);
    }

    private static int consonantClusterPenalty(String w) {
        int penalty = 0;
        int run = 0;
        for (int i = 0; i <= w.length(); i++) {
            char ch = i < w.length() ? w.charAt(i) : '0';
            boolean consonant = i < w.length() && isConsonant(ch);
            if (consonant) {
                run++;
                continue;
            }
            if (run >= 3) {
                String cluster = w.substring(i - run, i);
                boolean atEdge = (i - run == 0) || i == w.length();
                if (run >= 4 || !ALLOWED_CLUSTERS.contains(cluster)) {
                    penalty += (run - 2) * (atEdge ? 8 : 12);
                }
            }
            run = 0;
        }
        return penalty;
    }

    private static boolean isConsonant(char ch) {
        if (ch < 'a' || ch > 'z') {
            return false;
        }
        return "aeiouy".indexOf(ch) < 0;
    }
}
