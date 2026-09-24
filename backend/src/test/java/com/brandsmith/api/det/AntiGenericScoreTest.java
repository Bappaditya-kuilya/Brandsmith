package com.brandsmith.api.det;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AntiGenericScoreTest {

    @Test
    void weightsSumToOne() {
        assertEquals(1.0, AntiGenericScore.W_LEXICON + AntiGenericScore.W_EMBEDDING + AntiGenericScore.W_CRITIC, 1e-12);
    }

    @Test
    void knownWeightedMean() {
        // 0.30*100 + 0.30*50 + 0.40*0 = 45.0
        assertEquals(45.0, AntiGenericScore.combine(100, 50, 0), 1e-9);
        // 0.30*80 + 0.30*70 + 0.40*90 = 24 + 21 + 36 = 81.0
        assertEquals(81.0, AntiGenericScore.combine(80, 70, 90), 1e-9);
    }

    @Test
    void allHundredIsHundred() {
        assertEquals(100.0, AntiGenericScore.combine(100, 100, 100), 1e-9);
    }

    @Test
    void allZeroIsZero() {
        assertEquals(0.0, AntiGenericScore.combine(0, 0, 0), 1e-9);
    }

    @Test
    void criticDominatesSlightlyOverLexicon() {
        double withHighCritic = AntiGenericScore.combine(0, 0, 100);
        double withHighLexicon = AntiGenericScore.combine(100, 0, 0);
        assertTrue(withHighCritic > withHighLexicon);
        assertEquals(40.0, withHighCritic, 1e-9);
        assertEquals(30.0, withHighLexicon, 1e-9);
    }

    @Test
    void rejectsOutOfRangeInputs() {
        assertThrows(IllegalArgumentException.class, () -> AntiGenericScore.combine(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> AntiGenericScore.combine(0, 101, 0));
        assertThrows(IllegalArgumentException.class, () -> AntiGenericScore.combine(0, 0, Double.NaN));
    }

    @Test
    void roundsToOneDecimal() {
        double s = AntiGenericScore.combine(33.3, 66.6, 99.9);
        assertEquals(s, Math.round(s * 10.0) / 10.0, 1e-12);
    }
}
