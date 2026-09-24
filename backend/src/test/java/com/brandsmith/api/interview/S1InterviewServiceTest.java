package com.brandsmith.api.interview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.brandsmith.api.budget.BudgetExceededException;
import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.interview.S1InterviewService.InterviewResponse;
import com.brandsmith.api.llm.FakeLlmClient;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageRunRecorder;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;

class S1InterviewServiceTest {

    private static final String LLM_QUESTION_JSON =
            "{\"question\":\"Think of the last student who missed a team formation deadline. What did they do instead?\"}";
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptLoader prompts = new PromptLoader();
    private final StageRunner runner = new StageRunner(mapper,
            Validation.byDefaultProvider().configure().buildValidatorFactory().getValidator());
    private final BudgetGuard budget = new BudgetGuard("main-model", 3.0, 15.0, 1.0, 5.0);
    private final UUID sessionId = UUID.randomUUID();

    private SessionService sessions;
    private AtomicReference<Map<String, Object>> store;

    private static final class NoKeyLlm extends FakeLlmClient {
        @Override
        public boolean available() {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        store = new AtomicReference<>(toMap(new BriefState()));
        when(sessions.loadBrief(any(), any()))
                .thenAnswer(inv -> new SessionService.BriefSnapshot(store.get(), 0.0, 0.40));
        doAnswer(inv -> {
            store.set(inv.getArgument(1));
            return null;
        }).when(sessions).saveBrief(any(), any(), anyDouble());
    }

    private S1InterviewService service(LlmClient llm) {
        return new S1InterviewService(sessions, llm, prompts, runner, budget, mapper,
                mock(StageRunRecorder.class), "main-model");
    }

    private Map<String, Object> toMap(BriefState brief) {
        return mapper.convertValue(brief, MAP_TYPE);
    }

    private InterviewResponse start(S1InterviewService service) {
        return service.submit(sessionId, "tok", new AnswerRequest(null, null));
    }

    private InterviewResponse answer(S1InterviewService service, String text) {
        return service.submit(sessionId, "tok", new AnswerRequest(text, null));
    }

    @Test
    void templatePathUsedWhenNoApiKey() {
        InterviewResponse response = start(service(new NoKeyLlm()));

        assertNotNull(response.nextQuestion());
        assertEquals(BriefFieldId.TARGET_USER.id(), response.fieldId());
        assertEquals(1, response.questionCount());
        assertFalse(response.done());
        assertEquals(0.0, response.overallConfidence(), 1e-9);
        verify(sessions).saveBrief(eq(sessionId), any(), eq(0.0));
    }

    @Test
    void llmQuestionUsedWhenAvailable() {
        FakeLlmClient fake = new FakeLlmClient(LLM_QUESTION_JSON);
        InterviewResponse response = start(service(fake));

        assertEquals("Think of the last student who missed a team formation deadline. What did they do instead?",
                response.nextQuestion());
        assertEquals(BriefFieldId.TARGET_USER.id(), response.fieldId());
        assertEquals(1, fake.calls());
        assertFalse(response.done());
        verify(sessions).saveBrief(eq(sessionId), any(), org.mockito.ArgumentMatchers.doubleThat(c -> c > 0));
    }

    @Test
    void invalidLlmOutputFallsBackToTemplate() {
        FakeLlmClient fake = new FakeLlmClient("not json at all", "still not json");
        InterviewResponse response = start(service(fake));

        assertEquals(2, fake.calls());
        assertNotNull(response.nextQuestion());
        assertFalse(response.nextQuestion().contains("Think of the last student"));
        assertTrue(response.nextQuestion().startsWith("Think of the last person"));
    }

    @Test
    void vagueFlowGetsAtLeastFourQuestions() {
        S1InterviewService service = service(new NoKeyLlm());
        int questions = 0;
        InterviewResponse response = start(service);
        while (!response.done() && questions < 12) {
            assertNotNull(response.nextQuestion());
            questions++;
            response = answer(service, "They searched Reddit at 1am and gave up.");
        }

        assertTrue(questions >= 4, "expected >= 4 questions, got " + questions);
        assertTrue(response.done());
        assertNull(response.nextQuestion());
        assertEquals(questions, response.questionCount());
    }

    @Test
    void detailedSeedStopsEarlyWithZeroQuestions() {
        BriefState seeded = new BriefState();
        for (BriefFieldId id : BriefFieldId.values()) {
            seeded.applyUserEdit(id.id(), "Detailed seed for " + id.id());
        }
        store.set(toMap(seeded));

        InterviewResponse response = start(service(new NoKeyLlm()));

        assertTrue(response.done());
        assertNull(response.nextQuestion());
        assertNull(response.fieldId());
        assertEquals(0, response.questionCount());
        assertTrue(response.overallConfidence() >= 0.75);
    }

    @Test
    void skipSetsLowConfidenceAndAssumption() {
        S1InterviewService service = service(new NoKeyLlm());
        start(service);
        InterviewResponse response = service.submit(sessionId, "tok", new AnswerRequest(null, true));

        @SuppressWarnings("unchecked")
        Map<String, Object> fields = (Map<String, Object>) response.briefState().get("fields");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) fields.get(BriefFieldId.TARGET_USER.id());
        assertEquals(Boolean.TRUE, target.get("assumption"));
        assertEquals(BriefState.SKIP_CONFIDENCE, ((Number) target.get("confidence")).doubleValue(), 1e-9);
        assertNull(target.get("value"));
        assertEquals(BriefFieldId.PROBLEM_ALTERNATIVE.id(), response.fieldId());
        assertFalse(response.done());
    }

    @Test
    void budgetExceededStillSavesAppliedAnswer() {
        BriefState pending = new BriefState();
        pending.startQuestion(BriefFieldId.TARGET_USER.id());
        store.set(toMap(pending));
        when(sessions.loadBrief(any(), any()))
                .thenReturn(new SessionService.BriefSnapshot(store.get(), 0.40, 0.40));

        S1InterviewService service = service(new FakeLlmClient(LLM_QUESTION_JSON));

        assertThrows(BudgetExceededException.class,
                () -> answer(service, "Night-shift nurses on a broken handoff"));
        verify(sessions).saveBrief(eq(sessionId), any(), eq(0.0));

        @SuppressWarnings("unchecked")
        Map<String, Object> saved = (Map<String, Object>) store.get().get("fields");
        @SuppressWarnings("unchecked")
        Map<String, Object> target = (Map<String, Object>) saved.get(BriefFieldId.TARGET_USER.id());
        assertEquals("Night-shift nurses on a broken handoff", target.get("value"));
    }
}
