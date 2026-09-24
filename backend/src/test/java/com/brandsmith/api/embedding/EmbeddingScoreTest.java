package com.brandsmith.api.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class EmbeddingScoreTest {

    @Test
    void zeroSimilarityScoresHundred() {
        assertEquals(100, EmbeddingScore.fromMaxCosine(0.0));
    }

    @Test
    void fullSimilarityScoresZero() {
        assertEquals(0, EmbeddingScore.fromMaxCosine(1.0));
    }

    @Test
    void halfSimilarityScoresFifty() {
        assertEquals(50, EmbeddingScore.fromMaxCosine(0.5));
    }

    @Test
    void clampsOutsideUnitInterval() {
        assertEquals(100, EmbeddingScore.fromMaxCosine(-0.4));
        assertEquals(0, EmbeddingScore.fromMaxCosine(1.7));
    }

    @Test
    void decreasesMonotonicallyAsSimilarityGrows() {
        int previous = EmbeddingScore.fromMaxCosine(0.0);
        for (double similarity = 0.1; similarity <= 1.0; similarity += 0.1) {
            int score = EmbeddingScore.fromMaxCosine(similarity);
            assertTrue(score <= previous, "score " + score + " at similarity " + similarity + " exceeded " + previous);
            previous = score;
        }
    }

    @Test
    void forTextScoresThroughIndex() {
        HashEmbedder embedder = new HashEmbedder();
        List<float[]> corpus = List.of(embedder.embed("empower every team"));
        EmbeddingIndex index = new InMemoryEmbeddingIndex(corpus, embedder::embed);

        assertEquals(0, EmbeddingScore.forText(index, "empower every team"));
        assertEquals(100, EmbeddingScore.forText(index, "zzz qqq unrelated wording"));
    }
}
