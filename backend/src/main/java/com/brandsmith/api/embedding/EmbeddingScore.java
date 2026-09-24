package com.brandsmith.api.embedding;

/**
 * embedding_score = 100 - scaled max cosine (PRD 7.4): similarity 0 maps to 100, similarity 1 maps
 * to 0, linear in between, clamped at both ends.
 */
public final class EmbeddingScore {

    private EmbeddingScore() {
    }

    public static int fromMaxCosine(double maxCosineSimilarity) {
        double clamped = Math.max(0, Math.min(1, maxCosineSimilarity));
        return (int) Math.round(100 * (1 - clamped));
    }

    public static int forText(EmbeddingIndex index, String text) {
        return fromMaxCosine(index.maxCosineSimilarity(text));
    }
}
