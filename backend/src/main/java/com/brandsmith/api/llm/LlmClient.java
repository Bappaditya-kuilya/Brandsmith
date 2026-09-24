package com.brandsmith.api.llm;

public interface LlmClient {

    Response complete(Request request);

    boolean available();

    record Request(String system, String user, String model, int maxTokens, double temperature) {
    }

    record Response(String text, String model, int tokensIn, int tokensOut, long latencyMs) {
    }
}
