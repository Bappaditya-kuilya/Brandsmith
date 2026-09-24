package com.brandsmith.api.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.brandsmith.api.llm.FakeLlmClient;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.prompt.PromptLoader.Prompt;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class StageRunnerTest {

    private static final String VALID = """
            {"clean_idea":"A deadline tool for students","product_type":"student tool","moderated":false,"refusal_reason":null}""";

    private final Prompt prompt = new Prompt("s0-intake", "1", "system");
    private final StageRunner runner = new StageRunner(new ObjectMapper(), defaultValidator());

    private static Validator defaultValidator() {
        return Validation.byDefaultProvider().configure().buildValidatorFactory().getValidator();
    }

    @Test
    void validResponseParsesFirstTry() {
        FakeLlmClient fake = new FakeLlmClient(VALID);
        StageResult<S0Output> result = runner.run(fake, prompt, "user", "small-model", S0Output.class);

        assertFalse(result.degraded());
        assertNotNull(result.value());
        assertEquals("student tool", result.value().productType());
        assertEquals(1, fake.calls());
        assertEquals("1", result.promptVersion());
        assertNotNull(result.rawResponse());
    }

    @Test
    void invalidThenValidRetriesOnceAndSucceeds() {
        FakeLlmClient fake = new FakeLlmClient("{\"foo\": 1}", VALID);
        StageResult<S0Output> result = runner.run(fake, prompt, "user", "small-model", S0Output.class);

        assertFalse(result.degraded());
        assertNotNull(result.value());
        assertEquals(2, fake.calls());
    }

    @Test
    void twoFailuresReturnDegradedPartialWithoutValue() {
        FakeLlmClient fake = new FakeLlmClient("{\"foo\": 1}", "not json at all");
        StageResult<S0Output> result = runner.run(fake, prompt, "user", "small-model", S0Output.class);

        assertTrue(result.degraded());
        assertNull(result.value());
        assertEquals(2, fake.calls());
        assertNotNull(result.error());
        assertNotNull(result.rawResponse());
    }

    @Test
    void markdownFencedJsonIsExtracted() {
        FakeLlmClient fake = new FakeLlmClient("```json\n" + VALID + "\n```");
        StageResult<S0Output> result = runner.run(fake, prompt, "user", "small-model", S0Output.class);

        assertFalse(result.degraded());
        assertEquals("A deadline tool for students", result.value().cleanIdea());
    }
}
