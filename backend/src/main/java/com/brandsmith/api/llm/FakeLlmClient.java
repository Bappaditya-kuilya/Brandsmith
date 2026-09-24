package com.brandsmith.api.llm;

import java.util.ArrayDeque;
import java.util.Deque;

public class FakeLlmClient implements LlmClient {

    private final Deque<String> responses = new ArrayDeque<>();
    private String lastResponse = "";
    private int calls;

    public FakeLlmClient(String... canned) {
        if (canned.length > 0) {
            lastResponse = canned[canned.length - 1];
            for (String r : canned) {
                responses.add(r);
            }
        }
    }

    @Override
    public Response complete(Request request) {
        calls++;
        String text = responses.isEmpty() ? lastResponse : responses.poll();
        return new Response(text, request.model(), 10, 20, 1);
    }

    @Override
    public boolean available() {
        return true;
    }

    public int calls() {
        return calls;
    }
}
