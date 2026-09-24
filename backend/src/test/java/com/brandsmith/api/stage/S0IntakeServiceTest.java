package com.brandsmith.api.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.llm.FakeLlmClient;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.prompt.PromptLoader;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;

class S0IntakeServiceTest {

    private static final String HARMFUL = "I want a brand that sells meth and counterfeit money to students";
    private static final String LEGIT = "A study group app that matches students by shared deadlines";

    private final PromptLoader prompts = new PromptLoader();
    private final BudgetGuard guard = new BudgetGuard("claude-sonnet-5", 3.0, 15.0, 1.0, 5.0);
    private final StageRunner runner =
            new StageRunner(new ObjectMapper(), Validation.byDefaultProvider().configure().buildValidatorFactory().getValidator());

    private S0IntakeService service(LlmClient llm) {
        return new S0IntakeService(llm, prompts, runner, guard, "small-model", 0.40);
    }

    private static final class NoKeyLlm extends FakeLlmClient {
        @Override
        public boolean available() {
            return false;
        }
    }

    @Test
    void rulesRejectHarmfulIdeaWithoutCallingLlm() {
        FakeLlmClient fake = new FakeLlmClient("{}");
        S0IntakeService.IntakeResult result = service(fake).run(HARMFUL, 0, 0.40);

        assertTrue(result.output().moderated());
        assertEquals(0, fake.calls());
    }

    @Test
    void rulesOnlyDegradedWhenNoApiKey() {
        S0IntakeService.IntakeResult result = service(new NoKeyLlm()).run(LEGIT, 0, 0.40);

        assertFalse(result.output().moderated());
        assertTrue(result.degraded());
        assertEquals("rules", result.model());
        assertEquals(0.0, result.costUsd());
        assertEquals(LEGIT, result.output().cleanIdea());
    }

    @Test
    void llmPathCleansAndRecordsUsage() {
        FakeLlmClient fake = new FakeLlmClient(
                "{\"clean_idea\":\"A study group app for students\",\"product_type\":\"student tool\",\"moderated\":false,\"refusal_reason\":null}");
        S0IntakeService.IntakeResult result = service(fake).run(LEGIT, 0, 0.40);

        assertFalse(result.output().moderated());
        assertFalse(result.degraded());
        assertEquals("student tool", result.output().productType());
        assertEquals(1, fake.calls());
        assertTrue(result.costUsd() > 0);
        assertEquals("1", result.promptVersion());
    }

    @Test
    void llmFlaggedIdeaComesBackModerated() {
        FakeLlmClient fake = new FakeLlmClient(
                "{\"clean_idea\":\"x\",\"product_type\":\"other\",\"moderated\":true,\"refusal_reason\":\"deceptive\"}");
        S0IntakeService.IntakeResult result = service(fake).run(LEGIT, 0, 0.40);

        assertTrue(result.output().moderated());
    }

    @Test
    void budgetCapBlocksLlmCall() {
        FakeLlmClient fake = new FakeLlmClient("{}");
        org.junit.jupiter.api.Assertions.assertThrows(com.brandsmith.api.budget.BudgetExceededException.class,
                () -> service(fake).run(LEGIT, 0.40, 0.40));
        assertEquals(0, fake.calls());
    }
}
