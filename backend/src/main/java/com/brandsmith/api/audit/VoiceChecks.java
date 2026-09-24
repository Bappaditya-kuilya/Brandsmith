package com.brandsmith.api.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.brandsmith.api.det.ReadingGrade;
import com.brandsmith.api.det.ReadingStats;

public final class VoiceChecks {

    public static final int MAX_GRADE = 9;
    static final int PENALTY_BANNED = 40;
    static final int PENALTY_SENTENCE = 20;
    static final int PENALTY_GRADE = 15;
    static final int PENALTY_EXCLAMATION = 10;
    static final int MIN_PROSE_WORDS = 8;

    public record Result(int score, List<Finding> findings) {

        public Result {
            score = Math.clamp(score, 0, 100);
            findings = findings == null ? List.of() : List.copyOf(findings);
        }
    }

    private VoiceChecks() {
    }

    public static Result checkAll(Map<String, String> assets, List<String> banned, int[] sentenceWords) {
        if (assets == null || assets.isEmpty()) {
            return new Result(100, List.of());
        }
        int worst = 100;
        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, String> entry : assets.entrySet()) {
            Result one = check(entry.getKey(), entry.getValue(), banned, sentenceWords);
            worst = Math.min(worst, one.score());
            findings.addAll(one.findings());
        }
        return new Result(worst, findings);
    }

    public static Result check(String asset, String text, List<String> banned, int[] sentenceWords) {
        List<Finding> findings = new ArrayList<>();
        int score = 100;
        String value = text == null ? "" : text;

        if (banned != null) {
            for (String word : banned) {
                if (word == null || word.isBlank()) {
                    continue;
                }
                Matcher matcher = wordPattern(word).matcher(value);
                while (matcher.find()) {
                    score -= PENALTY_BANNED;
                    findings.add(Finding.fail("voiceCompliance", asset, matcher.group(),
                            "banned word '" + word.strip() + "' in voice.banned"));
                }
            }
        }

        ReadingStats stats = ReadingGrade.analyze(value);
        if (stats.wordCount() >= MIN_PROSE_WORDS) {
            if (sentenceWords != null && sentenceWords.length == 2
                    && !stats.sentenceLengthInRange(sentenceWords[0], sentenceWords[1])) {
                score -= PENALTY_SENTENCE;
                findings.add(Finding.fail("voiceCompliance", asset,
                        String.format(Locale.ROOT, "avg %.1f words/sentence", stats.avgWordsPerSentence()),
                        "average sentence length outside voice.sentenceWords ["
                                + sentenceWords[0] + ", " + sentenceWords[1] + "]"));
            }
            if (stats.fleschKincaidGrade() > MAX_GRADE) {
                score -= PENALTY_GRADE;
                findings.add(Finding.warn("voiceCompliance", asset,
                        String.format(Locale.ROOT, "grade %.1f", stats.fleschKincaidGrade()),
                        "reading grade above " + MAX_GRADE));
            }
        }

        if (stats.exclamationCount() > 1) {
            score -= PENALTY_EXCLAMATION;
            findings.add(Finding.warn("voiceCompliance", asset,
                    "!".repeat(Math.min(stats.exclamationCount(), 5)),
                    "exclamation count " + stats.exclamationCount() + " exceeds 1"));
        }

        return new Result(score, findings);
    }

    public static boolean containsWord(String text, String word) {
        if (text == null || word == null || word.isBlank()) {
            return false;
        }
        return wordPattern(word).matcher(text).find();
    }

    static String templateRevise(String text, List<String> forbidden) {
        String out = text == null ? "" : text;
        if (forbidden != null) {
            for (String word : forbidden) {
                if (word == null || word.isBlank() || word.startsWith("-")) {
                    continue;
                }
                out = wordPattern(word).matcher(out).replaceAll(" ");
            }
        }
        out = out.replaceAll("!{2,}", "!");
        out = out.replaceAll("\\s{2,}", " ");
        out = out.replaceAll("\\s+([.,;:!?])", "$1");
        return out.strip();
    }

    private static Pattern wordPattern(String word) {
        return Pattern.compile("(?<![\\p{L}\\p{N}])" + Pattern.quote(word.strip()) + "(?![\\p{L}\\p{N}])",
                Pattern.CASE_INSENSITIVE);
    }
}
