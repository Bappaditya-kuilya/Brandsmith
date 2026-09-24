package com.brandsmith.api.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CosineSimilarityTest {

    @Test
    void identicalVectorsScoreOne() {
        float[] vector = {1f, 2f, 3f};
        assertEquals(1.0, CosineSimilarity.of(vector, vector), 1e-9);
    }

    @Test
    void orthogonalVectorsScoreZero() {
        assertEquals(0.0, CosineSimilarity.of(new float[] {1f, 0f}, new float[] {0f, 1f}), 1e-9);
        assertEquals(0.0, CosineSimilarity.of(new float[] {1f, 5f, 0f}, new float[] {0f, 0f, 7f}), 1e-9);
    }

    @Test
    void oppositeVectorsScoreMinusOne() {
        assertEquals(-1.0, CosineSimilarity.of(new float[] {1f, 2f}, new float[] {-1f, -2f}), 1e-9);
    }

    @Test
    void zeroVectorScoresZero() {
        assertEquals(0.0, CosineSimilarity.of(new float[] {0f, 0f}, new float[] {3f, 4f}), 1e-9);
        assertEquals(0.0, CosineSimilarity.of(new float[] {3f, 4f}, new float[] {0f, 0f}), 1e-9);
    }

    @Test
    void scaledSameDirectionScoresOne() {
        assertEquals(1.0, CosineSimilarity.of(new float[] {2f, 2f}, new float[] {5f, 5f}), 1e-9);
    }

    @Test
    void lengthMismatchThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> CosineSimilarity.of(new float[] {1f}, new float[] {1f, 2f}));
    }
}
