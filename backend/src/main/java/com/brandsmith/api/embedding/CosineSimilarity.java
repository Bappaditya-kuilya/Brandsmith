package com.brandsmith.api.embedding;

public final class CosineSimilarity {

    private CosineSimilarity() {
    }

    public static double of(float[] a, float[] b) {
        if (a.length != b.length) {
            throw new IllegalArgumentException("Vector length mismatch: " + a.length + " vs " + b.length);
        }
        double dot = 0;
        double normA = 0;
        double normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            normA += (double) a[i] * a[i];
            normB += (double) b[i] * b[i];
        }
        if (normA == 0 || normB == 0) {
            return 0;
        }
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}
