package com.brandsmith.api.det;

/**
 * OKLCH color with conversion to sRGB hex via standard OKLab matrices (Björn Ottosson).
 * Chroma is reduced if the color is out of sRGB gamut.
 */
public record Oklch(double l, double c, double hDeg) {

    public Oklch {
        if (l < 0 || l > 1 || Double.isNaN(l) || Double.isNaN(c) || c < 0 || Double.isNaN(hDeg)) {
            throw new IllegalArgumentException("invalid OKLCH: L=" + l + " C=" + c + " h=" + hDeg);
        }
    }

    public String toHex() {
        double chroma = c;
        double[] lin = linearRgb(l, chroma, hDeg);
        for (int i = 0; i < 40 && !inGamut(lin); i++) {
            chroma *= 0.92;
            lin = linearRgb(l, chroma, hDeg);
        }
        int r = gamma(lin[0]);
        int g = gamma(lin[1]);
        int b = gamma(lin[2]);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    public Oklch withL(double newL) {
        return new Oklch(newL, c, hDeg);
    }

    private static double[] linearRgb(double l, double c, double hDeg) {
        double h = Math.toRadians(hDeg);
        double a = c * Math.cos(h);
        double b = c * Math.sin(h);

        double l_ = l + 0.3963377774 * a + 0.2158037573 * b;
        double m_ = l - 0.1055613458 * a - 0.0638541728 * b;
        double s_ = l - 0.0894841775 * a - 1.2914855480 * b;

        double l3 = l_ * l_ * l_;
        double m3 = m_ * m_ * m_;
        double s3 = s_ * s_ * s_;

        double r = 4.0767416621 * l3 - 3.3077115913 * m3 + 0.2309699292 * s3;
        double g = -1.2684380046 * l3 + 2.6097574011 * m3 - 0.3413193965 * s3;
        double bb = -0.0041960863 * l3 - 0.7034186147 * m3 + 1.7076147010 * s3;
        return new double[] {r, g, bb};
    }

    private static boolean inGamut(double[] lin) {
        for (double v : lin) {
            if (v < -1e-4 || v > 1.0 + 1e-4) {
                return false;
            }
        }
        return true;
    }

    private static int gamma(double linear) {
        double c = linear <= 0.0031308 ? 12.92 * linear : 1.055 * Math.pow(linear, 1.0 / 2.4) - 0.055;
        return (int) Math.round(Math.clamp(c, 0.0, 1.0) * 255.0);
    }
}
