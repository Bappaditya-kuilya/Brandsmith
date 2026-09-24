package com.brandsmith.api.det;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PaletteGeneratorTest {

    private static final String[][] TEXT_ON_SURFACE = {{"fg", "bg"}, {"fg", "surface"},
            {"muted", "bg"}, {"muted", "surface"}, {"accent", "bg"}, {"accent", "surface"}};

    @Test
    void allTextPairsMeetAaForLightMood() {
        assertAa(PaletteGenerator.generate(210, 0.6, 0.7));
    }

    @Test
    void allTextPairsMeetAaForDarkMood() {
        assertAa(PaletteGenerator.generate(30, 0.5, -0.7));
    }

    @Test
    void allTextPairsMeetAaForNeutralMoodAcrossHues() {
        for (double hue : new double[] {0, 45, 90, 160, 220, 300, 359}) {
            assertAa(PaletteGenerator.generate(hue, 0.4, 0.2));
            assertAa(PaletteGenerator.generate(hue, 0.8, -0.2));
        }
    }

    @Test
    void fiveDistinctTokens() {
        Palette p = PaletteGenerator.generate(120, 0.5, 0.5);
        assertEquals(5, 5);
        assertNotEquals(p.bg(), p.surface());
        assertNotEquals(p.bg(), p.fg());
        assertNotEquals(p.fg(), p.accent());
        assertNotEquals(p.fg(), p.muted());
    }

    @Test
    void differentSeedsProduceDifferentAccents() {
        assertNotEquals(
                PaletteGenerator.generate(20, 0.7, 0.3).accent(),
                PaletteGenerator.generate(200, 0.7, 0.3).accent());
    }

    @Test
    void saturationClampedAndHueWrapped() {
        assertAa(PaletteGenerator.generate(370, 1.5, 2.0));
        assertAa(PaletteGenerator.generate(-10, -1, -2));
    }

    private void assertAa(Palette p) {
        for (String[] pair : TEXT_ON_SURFACE) {
            String text = "fg".equals(pair[0]) ? p.fg() : "muted".equals(pair[0]) ? p.muted() : p.accent();
            String bg = "bg".equals(pair[1]) ? p.bg() : p.surface();
            double ratio = Wcag.ratio(text, bg);
            assertTrue(ratio >= Wcag.AA_TEXT,
                    () -> pair[0] + " on " + pair[1] + " = " + ratio + " (" + text + " / " + bg + ")");
        }
        assertFalse(p.bg().equals(p.fg()));
    }
}
