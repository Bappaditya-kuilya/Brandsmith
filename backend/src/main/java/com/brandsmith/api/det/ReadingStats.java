package com.brandsmith.api.det;

/**
 * Flesch-Kincaid grade level plus sentence length and exclamation counts for voice checks.
 */
public record ReadingStats(
        double fleschKincaidGrade,
        double avgWordsPerSentence,
        int exclamationCount,
        int wordCount,
        int sentenceCount) {

    public boolean sentenceLengthInRange(int minWords, int maxWords) {
        if (sentenceCount == 0) {
            return wordCount == 0;
        }
        return avgWordsPerSentence >= minWords && avgWordsPerSentence <= maxWords;
    }
}
