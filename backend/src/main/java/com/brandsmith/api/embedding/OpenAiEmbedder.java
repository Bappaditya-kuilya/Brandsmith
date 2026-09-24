package com.brandsmith.api.embedding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Query-time embedder for precomputed OpenAI corpus vectors. Only constructed when an
 * OPENAI_API_KEY is present; tests never build this class.
 */
public final class OpenAiEmbedder {

    private static final String ENDPOINT = "https://api.openai.com/v1/embeddings";
    private static final String MODEL = "text-embedding-3-small";

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final String apiKey;

    public OpenAiEmbedder(String apiKey) {
        this.apiKey = apiKey;
    }

    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    public List<float[]> embedAll(List<String> texts) {
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("model", MODEL);
            ArrayNode input = body.putArray("input");
            texts.forEach(input::add);
            HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                    .timeout(Duration.ofSeconds(60))
                    .header("content-type", "application/json")
                    .header("authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("OpenAI embeddings HTTP " + response.statusCode());
            }
            List<JsonNode> rows = new ArrayList<>();
            mapper.readTree(response.body()).path("data").forEach(rows::add);
            rows.sort(Comparator.comparingInt(row -> row.path("index").asInt()));
            List<float[]> vectors = new ArrayList<>(rows.size());
            for (JsonNode row : rows) {
                JsonNode embedding = row.path("embedding");
                float[] vector = new float[embedding.size()];
                for (int i = 0; i < vector.length; i++) {
                    vector[i] = (float) embedding.get(i).asDouble();
                }
                vectors.add(vector);
            }
            if (vectors.size() != texts.size()) {
                throw new IllegalStateException(
                        "OpenAI returned " + vectors.size() + " vectors for " + texts.size() + " inputs");
            }
            return vectors;
        } catch (IOException e) {
            throw new IllegalStateException("OpenAI embeddings call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OpenAI embeddings call interrupted", e);
        }
    }
}
