package com.brandsmith.api.embedding;

import java.util.Locale;

/**
 * Deterministic hash-based bag-of-words embedder. Dev/test fallback when no embedding API key is
 * configured: not semantically meaningful, but cosine still ranks shared vocabulary higher.
 */
public final class HashEmbedder {

    public static final int DIMENSIONS = 64;

    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) {
            return vector;
        }
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (!token.isEmpty()) {
                vector[Math.floorMod(token.hashCode(), DIMENSIONS)] += 1f;
            }
        }
        return vector;
    }
}
