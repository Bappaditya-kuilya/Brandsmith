package com.brandsmith.api.det;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ReadingGradeTest {

    @Test
    void emptyTextIsZero() {
        ReadingStats s = ReadingGrade.analyze("   ");
        assertEquals(0, s.wordCount());
        assertEquals(0, s.sentenceCount());
        assertEquals(0, s.exclamationCount());
        assertEquals(0.0, s.fleschKincaidGrade(), 1e-9);
    }

    @Test
    void simpleSentencesAreLowGrade() {
        ReadingStats s = ReadingGrade.analyze("The cat sat on the mat. The dog ran fast.");
        assertTrue(s.fleschKincaidGrade() < 6.0, "grade=" + s.fleschKincaidGrade());
        assertEquals(2, s.sentenceCount());
        assertEquals(10, s.wordCount());
        assertEquals(0, s.exclamationCount());
    }

    @Test
    void longSentencesRaiseAvgWords() {
        ReadingStats s = ReadingGrade.analyze(
                "This sentence has many many words and clauses that keep going and going for a while.");
        assertTrue(s.avgWordsPerSentence() > 14, "avg=" + s.avgWordsPerSentence());
        assertEquals(1, s.sentenceCount());
    }

    @Test
    void exclamationCount() {
        ReadingStats s = ReadingGrade.analyze("Wow! Amazing!! Yes!");
        assertEquals(4, s.exclamationCount());
        assertEquals(3, s.sentenceCount());
    }

    @Test
    void complexWordsRaiseGrade() {
        ReadingStats easy = ReadingGrade.analyze("We make simple tools for teams.");
        ReadingStats hard = ReadingGrade.analyze(
                "The organization's incomprehensible documentation systematically complicates implementation.");
        assertTrue(hard.fleschKincaidGrade() > easy.fleschKincaidGrade());
    }

    @Test
    void sentenceLengthRangeCheck() {
        ReadingStats s = ReadingGrade.analyze("Short line here. Another short one is fine.");
        assertTrue(s.sentenceLengthInRange(3, 8));
        assertTrue(!s.sentenceLengthInRange(20, 30));
    }

    @Test
    void syllableEstimates() {
        assertEquals(1, ReadingGrade.syllables("cat"));
        assertEquals(3, ReadingGrade.syllables("beautiful"));
        assertEquals(1, ReadingGrade.syllables("the"));
        assertTrue(ReadingGrade.syllables("syllables") >= 3);
    }
}
