package com.brandsmith.api.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class HttpLlmClient implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(HttpLlmClient.class);
    private static final String ANTHROPIC_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String GROQ_ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String GEMINI_ENDPOINT_PREFIX =
            "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final String API_VERSION = "2023-06-01";

    public enum Provider {
        GROQ, GEMINI, ANTHROPIC, NONE
    }

    static Provider resolve(String provider, String groqKey, String geminiKey, String anthropicKey) {
        if (provider != null) {
            switch (provider.strip().toLowerCase()) {
                case "groq":
                    return Provider.GROQ;
                case "gemini":
                    return Provider.GEMINI;
                case "anthropic":
                    return Provider.ANTHROPIC;
                case "none":
                    return Provider.NONE;
                default:
                    break; // auto or unknown: sniff keys in priority order
            }
        }
        if (!isBlank(groqKey)) {
            return Provider.GROQ;
        }
        if (!isBlank(geminiKey)) {
            return Provider.GEMINI;
        }
        if (!isBlank(anthropicKey)) {
            return Provider.ANTHROPIC;
        }
        return Provider.NONE;
    }

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final Provider provider;
    private final String apiKey;
    private final long timeoutMs;

    public HttpLlmClient(ObjectMapper mapper, Provider provider, String apiKey, long timeoutMs) {
        this.mapper = mapper;
        this.provider = provider;
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        this.timeoutMs = timeoutMs;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean available() {
        return provider != Provider.NONE && !apiKey.isBlank();
    }

    @Override
    public Response complete(Request request) {
        if (!available()) {
            throw new LlmUnavailableException(provider == Provider.NONE
                    ? "No LLM provider configured"
                    : provider + " API key not configured");
        }
        byte[] body = bodyBytes(request);
        long start = System.nanoTime();
        int lastStatus = 0;
        IOException lastIo = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                HttpResponse<String> resp = http.send(buildRequest(request, body), HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    long latencyMs = (System.nanoTime() - start) / 1_000_000;
                    Response response = parse(resp.body(), request.model(), latencyMs);
                    log.info("llm call ok provider={} model={} latency_ms={} tokens_in={} tokens_out={}",
                            provider.name().toLowerCase(), response.model(), response.latencyMs(),
                            response.tokensIn(), response.tokensOut());
                    return response;
                }
                lastStatus = resp.statusCode();
                if (resp.statusCode() != 429 && resp.statusCode() < 500) {
                    break;
                }
            } catch (IOException e) {
                lastIo = e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new LlmUnavailableException("LLM call interrupted", e);
            }
            if (attempt == 0) {
                sleep();
            }
        }
        if (lastIo != null) {
            throw new LlmUnavailableException("LLM call failed after retry", lastIo);
        }
        throw new LlmUnavailableException("LLM HTTP " + lastStatus + " after retry");
    }

    String endpoint(Request request) {
        return switch (provider) {
            case GROQ -> GROQ_ENDPOINT;
            case GEMINI -> GEMINI_ENDPOINT_PREFIX + request.model() + ":generateContent?key=" + apiKey;
            case ANTHROPIC -> ANTHROPIC_ENDPOINT;
            case NONE -> throw new IllegalStateException("no LLM provider");
        };
    }

    HttpRequest buildRequest(Request request, byte[] body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(endpoint(request)))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        switch (provider) {
            case GROQ -> builder.header("authorization", "Bearer " + apiKey);
            case ANTHROPIC -> builder.header("x-api-key", apiKey).header("anthropic-version", API_VERSION);
            case GEMINI -> { } // key travels in the URL query, per Gemini API
            case NONE -> { }
        }
        return builder.build();
    }

    byte[] bodyBytes(Request request) {
        try {
            ObjectNode body = mapper.createObjectNode();
            switch (provider) {
                case GROQ -> groqBody(request, body);
                case GEMINI -> geminiBody(request, body);
                case ANTHROPIC -> anthropicBody(request, body);
                case NONE -> throw new IllegalStateException("no LLM provider");
            }
            return mapper.writeValueAsBytes(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to build LLM request", e);
        }
    }

    private void groqBody(Request request, ObjectNode body) {
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        ArrayNode messages = body.putArray("messages");
        ObjectNode system = messages.addObject();
        system.put("role", "system");
        system.put("content", request.system());
        ObjectNode user = messages.addObject();
        user.put("role", "user");
        user.put("content", request.user());
    }

    private void geminiBody(Request request, ObjectNode body) {
        ObjectNode system = body.putObject("systemInstruction");
        system.putArray("parts").addObject().put("text", request.system());
        ObjectNode contents = body.putArray("contents").addObject();
        contents.put("role", "user");
        contents.putArray("parts").addObject().put("text", request.user());
        ObjectNode config = body.putObject("generationConfig");
        config.put("maxOutputTokens", request.maxTokens());
        config.put("temperature", request.temperature());
        // Free-tier flash spends tokens on hidden thoughts; we need the JSON body.
        config.putObject("thinkingConfig").put("thinkingBudget", 0);
    }

    private void anthropicBody(Request request, ObjectNode body) {
        body.put("model", request.model());
        body.put("max_tokens", request.maxTokens());
        body.put("temperature", request.temperature());
        body.put("system", request.system());
        ObjectNode message = body.putArray("messages").addObject();
        message.put("role", "user");
        message.put("content", request.user());
    }

    Response parse(String body, String fallbackModel, long latencyMs) {
        try {
            JsonNode root = mapper.readTree(body);
            return switch (provider) {
                case GROQ -> parseGroq(root, fallbackModel, latencyMs);
                case GEMINI -> parseGemini(root, fallbackModel, latencyMs);
                case ANTHROPIC -> parseAnthropic(root, fallbackModel, latencyMs);
                case NONE -> throw new IllegalStateException("no LLM provider");
            };
        } catch (JsonProcessingException e) {
            throw new LlmUnavailableException("LLM response was not valid JSON", e);
        }
    }

    private Response parseGroq(JsonNode root, String fallbackModel, long latencyMs) {
        String text = root.path("choices").path(0).path("message").path("content").asText("");
        int tokensIn = root.path("usage").path("prompt_tokens").asInt(0);
        int tokensOut = root.path("usage").path("completion_tokens").asInt(0);
        String model = root.path("model").asText(fallbackModel);
        return new Response(text, model, tokensIn, tokensOut, latencyMs);
    }

    private Response parseGemini(JsonNode root, String fallbackModel, long latencyMs) {
        StringBuilder text = new StringBuilder();
        for (JsonNode part : root.path("candidates").path(0).path("content").path("parts")) {
            text.append(part.path("text").asText());
        }
        int tokensIn = root.path("usageMetadata").path("promptTokenCount").asInt(0);
        int tokensOut = root.path("usageMetadata").path("candidatesTokenCount").asInt(0);
        return new Response(text.toString(), fallbackModel, tokensIn, tokensOut, latencyMs);
    }

    private Response parseAnthropic(JsonNode root, String fallbackModel, long latencyMs) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : root.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        int tokensIn = root.path("usage").path("input_tokens").asInt(0);
        int tokensOut = root.path("usage").path("output_tokens").asInt(0);
        String model = root.path("model").asText(fallbackModel);
        return new Response(text.toString(), model, tokensIn, tokensOut, latencyMs);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void sleep() {
        try {
            Thread.sleep(400);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmUnavailableException("LLM retry interrupted", e);
        }
    }
}
