package com.brandsmith.api.det;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class WcagTest {

    @Test
    void whiteVsBlackIs21() {
        assertEquals(21.0, Wcag.ratio("#ffffff", "#000000"), 0.01);
    }

    @Test
    void knownAaPassPair() {
        // #767676 on white is the classic AA-passing gray (about 4.54:1)
        assertTrue(Wcag.meetsAa("#767676", "#ffffff"));
        assertTrue(Wcag.ratio("#767676", "#ffffff") >= 4.5);
    }

    @Test
    void knownAaFailPair() {
        // #777777 on white is just under 4.5:1
        assertFalse(Wcag.meetsAa("#777777", "#ffffff"));
        assertTrue(Wcag.ratio("#777777", "#ffffff") < 4.5);
    }

    @Test
    void blackOnWhiteMeetsAa() {
        assertTrue(Wcag.meetsAa("#000000", "#ffffff"));
    }

    @Test
    void shortHexExpanded() {
        assertEquals(Wcag.relativeLuminance("#ffffff"), Wcag.relativeLuminance("#fff"), 1e-9);
        assertEquals(Wcag.ratio("#000", "#fff"), 21.0, 0.01);
    }

    @Test
    void ratioIsSymmetric() {
        assertEquals(Wcag.ratio("#123456", "#abcdef"), Wcag.ratio("#abcdef", "#123456"), 1e-12);
    }

    @Test
    void invalidHexRejected() {
        assertThrows(IllegalArgumentException.class, () -> Wcag.ratio("zzz", "#ffffff"));
        assertThrows(IllegalArgumentException.class, () -> Wcag.relativeLuminance((String) null));
    }
}
