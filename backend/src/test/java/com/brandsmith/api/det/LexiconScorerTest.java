package com.brandsmith.api.det;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class LexiconScorerTest {

    private LexiconScorer scorer;

    @BeforeEach
    void setUp() {
        scorer = new LexiconScorer();
    }

    @Test
    void resourceHasAboutEightyEntries() {
        assertTrue(scorer.entryCount() >= 80, "entries=" + scorer.entryCount());
    }

    @Test
    void cleanUniqueTextScoresOneHundred() {
        assertEquals(100, scorer.score("Quixotil Fernwave Zorp"));
    }

    @Test
    void eachHitCostsTenPoints() {
        List<String> hits = scorer.hits("Empower your team with seamless rocket growth");
        assertTrue(hits.contains("empower") && hits.contains("seamless") && hits.contains("rocket"),
                "hits=" + hits);
        assertEquals(100 - 10 * hits.size(), scorer.score("Empower your team with seamless rocket growth"));
    }

    @Test
    void suffixifyMatchesWordEnding() {
        List<String> hits = scorer.hits("amplify certified");
        assertTrue(hits.contains("-ify"), "hits=" + hits);
    }

    @Test
    void suffixLyMatchesWordEnding() {
        List<String> hits = scorer.hits("quickly crafted");
        assertTrue(hits.contains("-ly"), "hits=" + hits);
    }

    @Test
    void genericTaglineScoresLow() {
        int score = scorer.score("Empower your team with a seamless, cutting-edge rocket launch!");
        assertTrue(score <= 50, "score=" + score);
    }

    @Test
    void caseInsensitiveMatching() {
        assertEquals(scorer.score("SEAMLESS"), scorer.score("seamless"));
    }

    @Test
    void emptyAndNullTextAreMaxScore() {
        assertEquals(100, scorer.score(""));
        assertEquals(100, scorer.score(null));
    }

    @Test
    void scoreClampedAtZero() {
        LexiconScorer many = new LexiconScorer(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k"));
        assertEquals(0, many.score("a b c d e f g h i j k"));
    }
}
