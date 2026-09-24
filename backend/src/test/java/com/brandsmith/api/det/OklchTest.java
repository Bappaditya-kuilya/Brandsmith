package com.brandsmith.api.det;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OklchTest {

    @Test
    void blackIsNearZeroLightness() {
        String hex = new Oklch(0.0, 0.0, 0.0).toHex();
        assertEquals("#000000", hex);
    }

    @Test
    void whiteIsFullLightness() {
        String hex = new Oklch(1.0, 0.0, 0.0).toHex();
        assertEquals("#ffffff", hex);
    }

    @Test
    void midLightnessIsMidGrayish() {
        // OKLab L=0.5 is not sRGB 0.5; just require a mid gray between black and white.
        String hex = new Oklch(0.5, 0.0, 0.0).toHex();
        int r = Wcag.parseHex(hex)[0];
        assertTrue(r > 60 && r < 160, "got " + hex);
        assertEquals(hex, new Oklch(0.5, 0.0, 120).toHex(), "zero chroma ignores hue");
    }

    @Test
    void outOfGamutChromaIsClipped() {
        String hex = new Oklch(0.5, 0.5, 30).toHex();
        assertEquals(7, hex.length());
        assertTrue(hex.matches("#[0-9a-f]{6}"));
    }

    @Test
    void hueChangesOutput() {
        assertNotEquals(new Oklch(0.7, 0.15, 20).toHex(), new Oklch(0.7, 0.15, 200).toHex());
    }
}
