package com.brandsmith.api.det;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PronounceabilityTest {

    @Test
    void emptyScoresZero() {
        assertEquals(0, Pronounceability.score(""));
        assertEquals(0, Pronounceability.score(null));
        assertEquals(0, Pronounceability.score("   "));
    }

    @Test
    void easyNamesScoreHigh() {
        assertTrue(Pronounceability.score("Luma") >= 85, "Luma=" + Pronounceability.score("Luma"));
        assertTrue(Pronounceability.score("Brivo") >= 85, "Brivo=" + Pronounceability.score("Brivo"));
        assertTrue(Pronounceability.score("Nexa") >= 85, "Nexa=" + Pronounceability.score("Nexa"));
    }

    @Test
    void hardClusteredNamesScoreLow() {
        assertTrue(Pronounceability.score("Xrztq") <= 50, "Xrztq=" + Pronounceability.score("Xrztq"));
        assertTrue(Pronounceability.score("Bstrgllmn") < Pronounceability.score("Brivo"));
        assertTrue(Pronounceability.score("Rhytm") <= Pronounceability.score("Luma"));
    }

    @Test
    void overlongNamePenalized() {
        int score = Pronounceability.score("Supercalifragilisticexpialidocious");
        assertTrue(score < Pronounceability.score("Brivo"), "score=" + score);
    }

    @Test
    void allowedEnglishClusterNotOverPenalized() {
        // "str" is an allowed cluster; a short name with it should still be speakable
        assertTrue(Pronounceability.score("Strilo") >= 70, "Strilo=" + Pronounceability.score("Strilo"));
    }

    @Test
    void scoreAlwaysInUnitRange() {
        for (String name : new String[] {"", "a", "Xrztq", "Luma", "Pneumonoultramicroscopicsilicovolcanoconiosis!"}) {
            int s = Pronounceability.score(name);
            assertTrue(s >= 0 && s <= 100, name + " -> " + s);
        }
    }
}
