package com.brandsmith.api.llm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpRequest;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.brandsmith.api.llm.HttpLlmClient.Provider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class HttpLlmClientTest {

    private static final String GROQ_JSON = """
            {"id":"chatcmpl-1","model":"llama-3.3-70b-versatile",
             "choices":[{"index":0,"message":{"role":"assistant","content":"Hello name"},"finish_reason":"stop"}],
             "usage":{"prompt_tokens":11,"completion_tokens":7,"total_tokens":18}}
            """;

    private static final String GEMINI_JSON = """
            {"candidates":[{"content":{"role":"model","parts":[{"text":"Hi "},{"text":"there"}]},
             "finishReason":"STOP"}],
             "usageMetadata":{"promptTokenCount":9,"candidatesTokenCount":4,"totalTokenCount":13}}
            """;

    private static final String ANTHROPIC_JSON = """
            {"id":"msg_1","model":"claude-sonnet-5",
             "content":[{"type":"text","text":"Answer"}],
             "usage":{"input_tokens":12,"output_tokens":6}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();
    private final LlmClient.Request request =
            new LlmClient.Request("system prompt", "user prompt", "some-model", 256, 0.4);

    @Test
    void autoPicksGroqWhenGroqKeySet() {
        assertEquals(Provider.GROQ, HttpLlmClient.resolve("auto", "gsk_x", "", ""));
        assertEquals(Provider.GROQ, HttpLlmClient.resolve("auto", "gsk_x", "AIza", "sk-ant"),
                "groq outranks gemini and anthropic in auto order");
        assertEquals(Provider.GEMINI, HttpLlmClient.resolve("auto", "", "AIza", ""));
        assertEquals(Provider.ANTHROPIC, HttpLlmClient.resolve("auto", "", "", "sk-ant"));
        assertEquals(Provider.NONE, HttpLlmClient.resolve("auto", "", "", ""));
        assertEquals(Provider.NONE, HttpLlmClient.resolve("none", "gsk_x", "", ""),
                "explicit none wins even with keys present");
        assertEquals(Provider.GROQ, HttpLlmClient.resolve("groq", "", "", ""),
                "explicit provider wins without sniffing keys");
    }

    @Test
    void parsesGroqResponseAndBuildsRequest() throws Exception {
        HttpLlmClient client = new HttpLlmClient(mapper, Provider.GROQ, "gsk_test", 5000);
        assertTrue(client.available());

        JsonNode body = mapper.readTree(client.bodyBytes(request));
        assertEquals("some-model", body.get("model").asText());
        assertEquals(256, body.get("max_tokens").asInt());
        assertEquals(0.4, body.get("temperature").asDouble(), 1e-9);
        assertEquals("system prompt", body.get("messages").get(0).get("content").asText());
        assertEquals("system", body.get("messages").get(0).get("role").asText());
        assertEquals("user prompt", body.get("messages").get(1).get("content").asText());

        HttpRequest http = client.buildRequest(request, client.bodyBytes(request));
        assertEquals("https://api.groq.com/openai/v1/chat/completions", http.uri().toString());
        assertEquals(List.of("Bearer gsk_test"), http.headers().allValues("authorization"));

        LlmClient.Response response = client.parse(GROQ_JSON, "fallback", 42);
        assertEquals("Hello name", response.text());
        assertEquals("llama-3.3-70b-versatile", response.model());
        assertEquals(11, response.tokensIn());
        assertEquals(7, response.tokensOut());
        assertEquals(42, response.latencyMs());
    }

    @Test
    void parsesGeminiResponseAndBuildsRequest() throws Exception {
        HttpLlmClient client = new HttpLlmClient(mapper, Provider.GEMINI, "AIza_test", 5000);
        assertTrue(client.available());

        JsonNode body = mapper.readTree(client.bodyBytes(request));
        assertEquals("system prompt", body.get("systemInstruction").get("parts").get(0).get("text").asText());
        assertEquals("user", body.get("contents").get(0).get("role").asText());
        assertEquals("user prompt", body.get("contents").get(0).get("parts").get(0).get("text").asText());
        assertEquals(256, body.get("generationConfig").get("maxOutputTokens").asInt());
        assertEquals(0.4, body.get("generationConfig").get("temperature").asDouble(), 1e-9);

        HttpRequest http = client.buildRequest(request, client.bodyBytes(request));
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/"
                + "some-model:generateContent", http.uri().toString());
        assertEquals("AIza_test", http.headers().firstValue("x-goog-api-key").orElse(""));
        assertFalse(http.uri().toString().contains("AIza_test"));

        LlmClient.Response response = client.parse(GEMINI_JSON, "some-model", 7);
        assertEquals("Hi there", response.text());
        assertEquals("some-model", response.model());
        assertEquals(9, response.tokensIn());
        assertEquals(4, response.tokensOut());
        assertEquals(7, response.latencyMs());
    }

    @Test
    void parsesAnthropicResponseKeepsExistingPath() throws Exception {
        HttpLlmClient client = new HttpLlmClient(mapper, Provider.ANTHROPIC, "sk-ant_test", 5000);
        assertTrue(client.available());

        JsonNode body = mapper.readTree(client.bodyBytes(request));
        assertEquals("system prompt", body.get("system").asText());
        assertEquals("user prompt", body.get("messages").get(0).get("content").asText());

        HttpRequest http = client.buildRequest(request, client.bodyBytes(request));
        assertEquals("https://api.anthropic.com/v1/messages", http.uri().toString());
        assertEquals(List.of("sk-ant_test"), http.headers().allValues("x-api-key"));
        assertEquals(List.of("2023-06-01"), http.headers().allValues("anthropic-version"));

        LlmClient.Response response = client.parse(ANTHROPIC_JSON, "fallback", 3);
        assertEquals("Answer", response.text());
        assertEquals("claude-sonnet-5", response.model());
        assertEquals(12, response.tokensIn());
        assertEquals(6, response.tokensOut());
    }

    @Test
    void noneProviderUnavailable() {
        assertEquals(Provider.NONE, HttpLlmClient.resolve("auto", "", "", ""));

        HttpLlmClient none = new HttpLlmClient(mapper, Provider.NONE, "", 1000);
        assertFalse(none.available());
        assertThrows(LlmUnavailableException.class, () -> none.complete(request));

        HttpLlmClient missingKey = new HttpLlmClient(mapper, Provider.GROQ, "  ", 1000);
        assertFalse(missingKey.available());
        assertThrows(LlmUnavailableException.class, () -> missingKey.complete(request));
    }
}
