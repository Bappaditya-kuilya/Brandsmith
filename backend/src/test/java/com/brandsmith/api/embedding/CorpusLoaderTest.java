package com.brandsmith.api.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class CorpusLoaderTest {

    @Test
    void loadsRealCorpusFromResourcesWithoutApiKey() {
        List<String> phrases = CorpusLoader.loadPhrases();
        assertTrue(phrases.size() >= 380 && phrases.size() <= 420, "expected ~400 phrases, got " + phrases.size());

        EmbeddingIndex index = CorpusLoader.load("");
        assertEquals(phrases.size(), index.corpusSize());

        double generic = index.maxCosineSimilarity("empower every team to unlock your potential");
        double obscure = index.maxCosineSimilarity("quantum wombat telemetry for alpine cheese");
        assertTrue(generic > 0.2, "generic phrasing should hit the corpus, got " + generic);
        assertTrue(generic > obscure, generic + " should exceed " + obscure);
        assertEquals(64, index.embed("anything").length);
    }

    @Test
    void identicalCorpusPhraseScoresOne() {
        List<String> phrases = CorpusLoader.loadPhrases();
        EmbeddingIndex index = CorpusLoader.load("");
        assertEquals(1.0, index.maxCosineSimilarity(phrases.get(0)), 1e-6);
    }

    @Test
    void missingVectorsFallBackToInMemoryHashVectors() {
        List<String> phrases = CorpusLoader.loadPhrases();
        EmbeddingIndex index = CorpusLoader.indexFrom(phrases, null, "");

        assertEquals(phrases.size(), index.corpusSize());
        assertEquals(HashEmbedder.DIMENSIONS, index.embed("fallback check").length);
        assertEquals(1.0, index.maxCosineSimilarity(phrases.get(0)), 1e-6);
    }

    @Test
    void apiSizedVectorsWithoutKeyFallBackToHashVectors() {
        List<String> phrases = List.of("empower every team", "unlock your potential");
        List<float[]> apiSized = List.of(new float[1536], new float[1536]);

        EmbeddingIndex index = CorpusLoader.indexFrom(phrases, apiSized, "");

        assertEquals(HashEmbedder.DIMENSIONS, index.embed("x").length);
        assertEquals(1.0, index.maxCosineSimilarity("empower every team"), 1e-6);
    }

    @Test
    void phraseCountMatchesCommittedCorpus() {
        assertEquals(400, CorpusLoader.loadPhrases().size());
    }
}
