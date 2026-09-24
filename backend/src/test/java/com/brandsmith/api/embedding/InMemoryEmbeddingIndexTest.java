package com.brandsmith.api.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class InMemoryEmbeddingIndexTest {

    private final HashEmbedder embedder = new HashEmbedder();

    @Test
    void hashEmbedderIsDeterministicAndCaseInsensitive() {
        float[] a = embedder.embed("Empower Every Team");
        float[] b = embedder.embed("empower every team");
        assertEquals(HashEmbedder.DIMENSIONS, a.length);
        assertEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(b));
        assertEquals(java.util.Arrays.toString(a), java.util.Arrays.toString(embedder.embed("empower every team")));
    }

    @Test
    void identicalTextScoresOne() {
        EmbeddingIndex index = indexOf("empower every team", "unrelated corpus line about whales");
        assertEquals(1.0, index.maxCosineSimilarity("empower every team"), 1e-6);
    }

    @Test
    void disjointTokensScoreZero() {
        EmbeddingIndex index = indexOf("empower every team");
        assertEquals(0.0, index.maxCosineSimilarity("zzz qqq wibble"), 1e-9);
    }

    @Test
    void sharedVocabularyOutscoresDisjoint() {
        EmbeddingIndex index = indexOf("empower every team to ship faster", "completely different words here");
        double shared = index.maxCosineSimilarity("empower every team now");
        double disjoint = index.maxCosineSimilarity("quokka kaleidoscope taxonomist");
        assertTrue(shared > disjoint, shared + " should exceed " + disjoint);
        assertTrue(shared > 0.5);
    }

    @Test
    void embedDelegatesToConfiguredEmbedder() {
        EmbeddingIndex index = indexOf("empower every team");
        assertEquals(java.util.Arrays.toString(embedder.embed("hello")),
                java.util.Arrays.toString(index.embed("hello")));
    }

    @Test
    void corpusSizeReportsPhraseCount() {
        assertEquals(2, indexOf("a b", "c d").corpusSize());
    }

    @Test
    void rejectsEmptyCorpus() {
        assertThrows(IllegalArgumentException.class, () -> new InMemoryEmbeddingIndex(List.of(), embedder::embed));
    }

    @Test
    void rejectsRaggedCorpus() {
        List<float[]> ragged = List.of(new float[] {1f, 2f}, new float[] {1f});
        assertThrows(IllegalArgumentException.class, () -> new InMemoryEmbeddingIndex(ragged, embedder::embed));
    }

    @Test
    void rejectsEmbedderDimensionMismatch() {
        List<float[]> corpus = List.of(new float[8]);
        assertThrows(IllegalArgumentException.class, () -> new InMemoryEmbeddingIndex(corpus, embedder::embed));
    }

    private EmbeddingIndex indexOf(String... phrases) {
        List<float[]> corpus = new java.util.ArrayList<>();
        for (String phrase : phrases) {
            corpus.add(embedder.embed(phrase));
        }
        return new InMemoryEmbeddingIndex(corpus, embedder::embed);
    }
}
