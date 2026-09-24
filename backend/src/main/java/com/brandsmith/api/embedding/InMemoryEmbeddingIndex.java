package com.brandsmith.api.embedding;

import java.util.List;
import java.util.function.Function;

public final class InMemoryEmbeddingIndex implements EmbeddingIndex {

    private final List<float[]> corpus;
    private final Function<String, float[]> embedder;

    public InMemoryEmbeddingIndex(List<float[]> corpusVectors, Function<String, float[]> embedder) {
        if (corpusVectors == null || corpusVectors.isEmpty()) {
            throw new IllegalArgumentException("Corpus must not be empty");
        }
        int dimensions = corpusVectors.get(0).length;
        if (dimensions == 0) {
            throw new IllegalArgumentException("Corpus vectors must not be empty");
        }
        for (float[] vector : corpusVectors) {
            if (vector.length != dimensions) {
                throw new IllegalArgumentException("Corpus vectors must share one dimension");
            }
        }
        int embedderDimensions = embedder.apply("").length;
        if (embedderDimensions != dimensions) {
            throw new IllegalArgumentException(
                    "Embedder produces " + embedderDimensions + "-dim vectors, corpus is " + dimensions + "-dim");
        }
        this.corpus = List.copyOf(corpusVectors);
        this.embedder = embedder;
    }

    @Override
    public float[] embed(String text) {
        return embedder.apply(text);
    }

    @Override
    public double maxCosineSimilarity(String text) {
        float[] query = embed(text);
        double max = 0;
        for (float[] vector : corpus) {
            double similarity = CosineSimilarity.of(query, vector);
            if (similarity > max) {
                max = similarity;
            }
        }
        return max;
    }

    public int corpusSize() {
        return corpus.size();
    }
}
