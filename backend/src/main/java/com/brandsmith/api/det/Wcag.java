package com.brandsmith.api.det;

/**
 * WCAG 2.x relative luminance and contrast ratio.
 */
public final class Wcag {

    public static final double AA_TEXT = 4.5;

    private Wcag() {
    }

    public static double relativeLuminance(int r, int g, int b) {
        return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
    }

    public static double relativeLuminance(String hex) {
        int[] rgb = parseHex(hex);
        return relativeLuminance(rgb[0], rgb[1], rgb[2]);
    }

    /** Contrast ratio in [1, 21]. Order of arguments does not matter. */
    public static double ratio(String hexA, String hexB) {
        double la = relativeLuminance(hexA);
        double lb = relativeLuminance(hexB);
        double lighter = Math.max(la, lb);
        double darker = Math.min(la, lb);
        return (lighter + 0.05) / (darker + 0.05);
    }

    public static boolean meetsAa(String fg, String bg) {
        return ratio(fg, bg) >= AA_TEXT;
    }

    static int[] parseHex(String hex) {
        if (hex == null) {
            throw new IllegalArgumentException("hex color is null");
        }
        String s = hex.startsWith("#") ? hex.substring(1) : hex;
        if (s.length() == 3) {
            s = "" + s.charAt(0) + s.charAt(0) + s.charAt(1) + s.charAt(1) + s.charAt(2) + s.charAt(2);
        }
        if (s.length() != 6) {
            throw new IllegalArgumentException("invalid hex color: " + hex);
        }
        try {
            int v = Integer.parseInt(s, 16);
            return new int[] {(v >> 16) & 0xFF, (v >> 8) & 0xFF, v & 0xFF};
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("invalid hex color: " + hex, e);
        }
    }

    private static double channel(int value) {
        double c = Math.clamp(value, 0, 255) / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
