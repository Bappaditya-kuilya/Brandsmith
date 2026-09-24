package com.brandsmith.api.det;

/**
 * Deterministic 5-token palette from a seed hue, saturation band, and mood lightness bias.
 * Adjusts lightness of text-like tokens until fg/muted/accent meet WCAG AA against bg and surface.
 */
public final class PaletteGenerator {

    private static final double STEP = 0.01;
    private static final int MAX_STEPS = 100;

    private PaletteGenerator() {
    }

    /**
     * @param seedHueDeg      seed hue in degrees (wrapped to [0, 360))
     * @param saturation      0..1 band; maps to OKLCH chroma
     * @param lightnessBias   mood bias -1 (darkest) .. +1 (lightest); negative yields dark bg
     */
    public static Palette generate(double seedHueDeg, double saturation, double lightnessBias) {
        double hue = normalizeHue(seedHueDeg);
        double sat = Math.clamp(saturation, 0.0, 1.0);
        double bias = Math.clamp(lightnessBias, -1.0, 1.0);

        // Keep bg/surface firmly on one side of mid-gray so one text color can clear AA on both.
        boolean lightBg = bias >= 0;
        double bgL = lightBg
                ? Math.clamp(0.80 + bias * 0.14, 0.80, 0.94)
                : Math.clamp(0.20 + bias * 0.14, 0.10, 0.34);

        double bgC = 0.015 + sat * 0.02;
        String bg = new Oklch(bgL, bgC, hue).toHex();

        double surfaceL = Math.clamp(lightBg ? bgL - 0.07 : bgL + 0.08, 0.08, 0.96);
        String surface = new Oklch(surfaceL, bgC * 1.5, hue).toHex();

        double accentC = 0.10 + sat * 0.14;
        double accentL = adjustL(lightBg ? 0.55 : 0.48, accentC, hue, bg, surface, lightBg);

        double fgC = 0.02 + sat * 0.03;
        double fgL = adjustL(lightBg ? 0.30 : 0.85, fgC, hue, bg, surface, lightBg);

        double mutedC = 0.03 + sat * 0.04;
        double mutedL = adjustL(lightBg ? 0.55 : 0.50, mutedC, hue, bg, surface, lightBg);

        return new Palette(
                bg,
                surface,
                new Oklch(accentL, accentC, hue).toHex(),
                new Oklch(fgL, fgC, hue).toHex(),
                new Oklch(mutedL, mutedC, hue).toHex());
    }

    /** Walk lightness away from the bg until AA is met against both bg and surface. */
    private static double adjustL(double startL, double c, double hue, String bg, String surface, boolean darken) {
        double l = startL;
        for (int i = 0; i < MAX_STEPS; i++) {
            String hex = new Oklch(Math.clamp(l, 0.0, 1.0), c, hue).toHex();
            if (Wcag.meetsAa(hex, bg) && Wcag.meetsAa(hex, surface)) {
                return Math.clamp(l, 0.0, 1.0);
            }
            l += darken ? -STEP : STEP;
            if (l < 0.0 || l > 1.0) {
                break;
            }
        }
        // Black or white always clears AA against our non-extreme backgrounds.
        return darken ? 0.0 : 1.0;
    }

    private static double normalizeHue(double hue) {
        if (Double.isNaN(hue)) {
            throw new IllegalArgumentException("hue is NaN");
        }
        double h = hue % 360.0;
        return h < 0 ? h + 360.0 : h;
    }
}
