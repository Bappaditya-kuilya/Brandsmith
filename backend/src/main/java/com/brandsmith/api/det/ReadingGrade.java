package com.brandsmith.api.det;

import java.util.Locale;

/**
 * Deterministic readability metrics: Flesch-Kincaid grade, average sentence length, exclamation count.
 */
public final class ReadingGrade {

    private ReadingGrade() {
    }

    public static ReadingStats analyze(String text) {
        if (text == null || text.isBlank()) {
            return new ReadingStats(0, 0, 0, 0, 0);
        }

        int exclamations = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '!') {
                exclamations++;
            }
        }

        String[] sentences = text.split("(?<=[.!?])\\s+|\\n+");
        int sentenceCount = 0;
        int wordCount = 0;
        int syllableCount = 0;
        for (String sentence : sentences) {
            String[] words = words(sentence);
            if (words.length == 0) {
                continue;
            }
            sentenceCount++;
            wordCount += words.length;
            for (String w : words) {
                syllableCount += syllables(w);
            }
        }

        if (sentenceCount == 0 || wordCount == 0) {
            return new ReadingStats(0, 0, exclamations, 0, 0);
        }

        double wordsPerSentence = (double) wordCount / sentenceCount;
        double syllablesPerWord = (double) syllableCount / wordCount;
        double grade = 0.39 * wordsPerSentence + 11.8 * syllablesPerWord - 15.59;

        return new ReadingStats(grade, wordsPerSentence, exclamations, wordCount, sentenceCount);
    }

    static String[] words(String sentence) {
        String normalized = sentence.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9'\\-]", " ");
        String trimmed = normalized.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("\\s+");
    }

    /** Vowel-group syllable estimate; minimum 1 for a non-empty alphabetic word. */
    static int syllables(String word) {
        String w = word.toLowerCase(Locale.ROOT).replaceAll("[^a-z]", "");
        if (w.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean prevVowel = false;
        for (int i = 0; i < w.length(); i++) {
            boolean vowel = "aeiouy".indexOf(w.charAt(i)) >= 0;
            if (vowel && !prevVowel) {
                count++;
            }
            prevVowel = vowel;
        }
        if (w.length() > 2 && w.endsWith("e") && !"le".equals(w.substring(w.length() - 2)) && count > 1) {
            count--;
        }
        return Math.max(1, count);
    }
}
