package com.brandsmith.api.det;

/**
 * Composes the anti-generic distinctiveness score (PRD §7.4):
 * score = 0.30 * lexicon + 0.30 * embedding + 0.40 * critic. Inputs are 0-100.
 */
public final class AntiGenericScore {

    public static final double W_LEXICON = 0.30;
    public static final double W_EMBEDDING = 0.30;
    public static final double W_CRITIC = 0.40;

    private AntiGenericScore() {
    }

    public static double combine(double lexiconScore, double embeddingScore, double criticScore) {
        requireUnitRange(lexiconScore, "lexiconScore");
        requireUnitRange(embeddingScore, "embeddingScore");
        requireUnitRange(criticScore, "criticScore");
        double score = W_LEXICON * lexiconScore + W_EMBEDDING * embeddingScore + W_CRITIC * criticScore;
        return Math.round(score * 10.0) / 10.0;
    }

    private static void requireUnitRange(double v, String name) {
        if (Double.isNaN(v) || v < 0 || v > 100) {
            throw new IllegalArgumentException(name + " must be in [0, 100], got " + v);
        }
    }
}
