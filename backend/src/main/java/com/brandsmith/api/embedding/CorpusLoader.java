package com.brandsmith.api.embedding;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Loads corpus/overused-phrases.txt and optional corpus/corpus-vectors.json (vector i aligns with
 * phrase line i). Missing or misaligned vectors fall back to in-memory HashEmbedder vectors so the
 * app boots without an embedding API key.
 */
public final class CorpusLoader {

    private static final Logger log = LoggerFactory.getLogger(CorpusLoader.class);
    static final String PHRASES_PATH = "corpus/overused-phrases.txt";
    static final String VECTORS_PATH = "corpus/corpus-vectors.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private CorpusLoader() {
    }

    public static EmbeddingIndex load(String openAiApiKey) {
        List<String> phrases = loadPhrases();
        return indexFrom(phrases, loadVectors(phrases.size()), openAiApiKey);
    }

    static EmbeddingIndex indexFrom(List<String> phrases, List<float[]> vectors, String openAiApiKey) {
        HashEmbedder hashEmbedder = new HashEmbedder();
        if (vectors == null) {
            log.info("Corpus vectors missing or misaligned; building {} fallback vectors in memory", phrases.size());
            return new InMemoryEmbeddingIndex(embedAll(phrases, hashEmbedder::embed), hashEmbedder::embed);
        }
        if (vectors.get(0).length == HashEmbedder.DIMENSIONS) {
            return new InMemoryEmbeddingIndex(vectors, hashEmbedder::embed);
        }
        if (openAiApiKey != null && !openAiApiKey.isBlank()) {
            OpenAiEmbedder openAiEmbedder = new OpenAiEmbedder(openAiApiKey.strip());
            return new InMemoryEmbeddingIndex(vectors, openAiEmbedder::embed);
        }
        log.warn("Corpus vectors are {}-dim but no OPENAI_API_KEY configured; rebuilding fallback vectors in memory",
                vectors.get(0).length);
        return new InMemoryEmbeddingIndex(embedAll(phrases, hashEmbedder::embed), hashEmbedder::embed);
    }

    static List<String> loadPhrases() {
        ClassPathResource resource = new ClassPathResource(PHRASES_PATH);
        try (InputStream in = resource.getInputStream()) {
            List<String> phrases = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .toList();
            if (phrases.isEmpty()) {
                throw new IllegalStateException("Corpus phrases file is empty: " + PHRASES_PATH);
            }
            return phrases;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read corpus phrases: " + PHRASES_PATH, e);
        }
    }

    static List<float[]> loadVectors(int expectedCount) {
        ClassPathResource resource = new ClassPathResource(VECTORS_PATH);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream in = resource.getInputStream()) {
            JsonNode root = MAPPER.readTree(in);
            if (!root.isArray() || root.size() != expectedCount) {
                log.warn("Corpus vectors file has {} rows, expected {}; using fallback", root.size(), expectedCount);
                return null;
            }
            List<float[]> vectors = new ArrayList<>(expectedCount);
            for (JsonNode row : root) {
                if (!row.isArray() || row.isEmpty()) {
                    log.warn("Corpus vectors file contains a non-vector row; using fallback");
                    return null;
                }
                float[] vector = new float[row.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) row.get(i).asDouble();
                }
                vectors.add(vector);
            }
            int dimensions = vectors.get(0).length;
            for (float[] vector : vectors) {
                if (vector.length != dimensions) {
                    log.warn("Corpus vectors file has inconsistent dimensions; using fallback");
                    return null;
                }
            }
            return vectors;
        } catch (IOException e) {
            log.warn("Corpus vectors file unreadable ({}); using fallback", e.getMessage());
            return null;
        }
    }

    private static List<float[]> embedAll(List<String> phrases, Function<String, float[]> embedder) {
        List<float[]> vectors = new ArrayList<>(phrases.size());
        for (String phrase : phrases) {
            vectors.add(embedder.apply(phrase));
        }
        return vectors;
    }
}
