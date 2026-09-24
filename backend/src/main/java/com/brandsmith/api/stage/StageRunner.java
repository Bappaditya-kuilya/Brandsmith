package com.brandsmith.api.stage;

import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.prompt.PromptLoader.Prompt;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

@Component
public class StageRunner {

    private static final Logger log = LoggerFactory.getLogger(StageRunner.class);
    private static final int MAX_TOKENS = 1024;

    private final ObjectMapper mapper;
    private final Validator validator;

    public StageRunner(ObjectMapper mapper, Validator validator) {
        this.mapper = mapper;
        this.validator = validator;
    }

    public <T> StageResult<T> run(LlmClient llm, Prompt prompt, String userMessage, String model, Class<T> type) {
        long latencyMs = 0;
        int tokensIn = 0;
        int tokensOut = 0;

        LlmClient.Response first = llm.complete(new LlmClient.Request(prompt.system(), userMessage, model, MAX_TOKENS, 0));
        latencyMs += first.latencyMs();
        tokensIn += first.tokensIn();
        tokensOut += first.tokensOut();

        Attempt<T> firstAttempt = attempt(first.text(), type);
        if (firstAttempt.error() == null) {
            return new StageResult<>(firstAttempt.value(), first.text(), false, model, prompt.version(),
                    tokensIn, tokensOut, latencyMs, null);
        }

        String retryUser = userMessage
                + "\n\nYour previous response failed validation:\n" + firstAttempt.error()
                + "\nFix it and return corrected JSON only.";
        LlmClient.Response second = llm.complete(new LlmClient.Request(prompt.system(), retryUser, model, MAX_TOKENS, 0));
        latencyMs += second.latencyMs();
        tokensIn += second.tokensIn();
        tokensOut += second.tokensOut();

        Attempt<T> secondAttempt = attempt(second.text(), type);
        if (secondAttempt.error() == null) {
            return new StageResult<>(secondAttempt.value(), second.text(), false, model, prompt.version(),
                    tokensIn, tokensOut, latencyMs, null);
        }

        log.warn("stage {} degraded after schema retry: {}", prompt.name(), secondAttempt.error());
        return new StageResult<>(null, second.text(), true, model, prompt.version(),
                tokensIn, tokensOut, latencyMs, secondAttempt.error());
    }

    private <T> Attempt<T> attempt(String raw, Class<T> type) {
        try {
            T value = mapper.readValue(extractJson(raw), type);
            Set<ConstraintViolation<T>> violations = validator.validate(value);
            if (violations.isEmpty()) {
                return new Attempt<>(value, null);
            }
            String error = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            return new Attempt<>(null, error);
        } catch (JsonProcessingException e) {
            return new Attempt<>(null, "invalid JSON: " + e.getOriginalMessage());
        }
    }

    private String extractJson(String raw) {
        String t = raw == null ? "" : raw.strip();
        if (t.startsWith("```")) {
            int nl = t.indexOf('\n');
            if (nl > 0) {
                t = t.substring(nl + 1);
            }
            int fence = t.lastIndexOf("```");
            if (fence >= 0) {
                t = t.substring(0, fence);
            }
            t = t.strip();
        }
        int open = t.indexOf('{');
        int close = t.lastIndexOf('}');
        if (open >= 0 && close > open) {
            t = t.substring(open, close + 1);
        }
        return t;
    }

    private record Attempt<T>(T value, String error) {
    }
}
