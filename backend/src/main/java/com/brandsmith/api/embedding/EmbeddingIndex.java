package com.brandsmith.api.embedding;

public interface EmbeddingIndex {

    float[] embed(String text);

    double maxCosineSimilarity(String text);

    int corpusSize();
}
