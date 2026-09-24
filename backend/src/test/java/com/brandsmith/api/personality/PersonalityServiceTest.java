package com.brandsmith.api.personality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.interview.BriefFieldId;
import com.brandsmith.api.interview.BriefState;
import com.brandsmith.api.llm.FakeLlmClient;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class PersonalityServiceTest {

    private static final String VALID_LLM_JSON = """
            {
              "traits": [
                {"name": "Blunt", "evidence": "hates wasting time", "behavior": "Puts the price in the first line", "neverBecome": "rude"},
                {"name": "Warm", "evidence": "students under deadline stress", "behavior": "Celebrates small wins in copy", "neverBecome": "patronizing"},
                {"name": "Practical", "evidence": "needs a match before the deadline", "behavior": "Shows the next step first", "neverBecome": "preachy"}
              ],
              "voice": {
                "formality": 2,
                "sentenceWords": [6, 16],
                "humorLevel": "dry",
                "banned": ["revolutionize", "game-changer", "synergy", "leverage", "seamless"],
                "moves": ["Lead with the problem", "One number per claim", "End on a single ask"]
              }
            }""";

    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptLoader prompts = new PromptLoader();
    private final Validator validator = Validation.byDefaultProvider().configure()
            .buildValidatorFactory().getValidator();
    private final StageRunner runner = new StageRunner(mapper, validator);
    private final BudgetGuard budget = new BudgetGuard("main-model", 3.0, 15.0, 1.0, 5.0);
    private final UUID sessionId = UUID.randomUUID();

    private SessionService sessions;
    private AtomicReference<Map<String, Object>> briefStore;
    private AtomicReference<Map<String, Object>> dnaStore;
    private AtomicReference<SessionService.StageRunRecord> stageRun;

    private static final class NoKeyLlm extends FakeLlmClient {
        @Override
        public boolean available() {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        briefStore = new AtomicReference<>(Map.of("idea", "A study group app for students"));
        dnaStore = new AtomicReference<>(Map.of(
                "position", Map.of("category", "Study tools", "differentiator", "Deadline matching")));
        stageRun = new AtomicReference<>();
        when(sessions.loadStage(any(), any())).thenAnswer(inv ->
                new SessionService.StageSnapshot(briefStore.get(), dnaStore.get(), 0.0, 0.40));
        when(sessions.stageLocked(any(), any())).thenReturn(false);
        doAnswer(inv -> {
            stageRun.set(inv.getArgument(0));
            return null;
        }).when(sessions).recordStageRun(any());
        doAnswer(inv -> {
            dnaStore.set(inv.getArgument(1));
            return null;
        }).when(sessions).saveBrandDna(any(), any(), anyDouble());
    }

    private PersonalityService service(LlmClient llm) {
        return new PersonalityService(sessions, llm, prompts, runner, budget, mapper, validator, "main-model");
    }

    @Test
    void noKeyPathBuildsThreeTraitsQuotingBriefAndPersistsDna() {
        BriefState brief = new BriefState();
        brief.setIdea("A study group app for students");
        brief.applyUserEdit(BriefFieldId.TARGET_USER.id(), "students who miss team formation deadlines");
        briefStore.set(mapper.convertValue(brief, Map.class));

        PersonalityService.RunResult result = service(new NoKeyLlm()).run(sessionId, "tok", null);

        PersonalityOutput output = result.output();
        assertTrue(result.degraded());
        assertTrue(output.traits().size() >= 3 && output.traits().size() <= 5);
        for (Trait trait : output.traits()) {
            assertFalse(trait.whyFits().isBlank());
            assertTrue(trait.whyFits().contains("\""), "whyFits must quote the brief: " + trait.whyFits());
            assertFalse(trait.behavior().isBlank());
            assertFalse(trait.neverBecome().isBlank());
        }
        assertTrue(output.voice().formality() >= 1 && output.voice().formality() <= 5);
        assertEquals(3, output.voice().signatureMoves().size());
        assertNotNull(output.avoidList());
        assertFalse(output.avoidList().isEmpty());

        Map<String, Object> saved = dnaStore.get();
        assertNotNull(saved.get("personality"));
        assertNotNull(saved.get("voice"));
        assertNotNull(stageRun.get());
        assertEquals("S3", stageRun.get().stage());
        verify(sessions).saveBrandDna(eq(sessionId), any(), eq(0.0));
    }

    @Test
    void llmPathParsesEvidenceAliasAndPersists() {
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);

        PersonalityService.RunResult result = service(fake).run(sessionId, "tok", null);

        assertFalse(result.degraded());
        assertEquals(1, fake.calls());
        assertEquals(3, result.output().traits().size());
        assertEquals("hates wasting time", result.output().traits().get(0).whyFits());
        assertEquals(2, result.output().voice().formality());
        assertEquals(List.of("revolutionize", "game-changer", "synergy", "leverage", "seamless"),
                result.output().voice().bannedWords());
        assertEquals(3, result.output().voice().signatureMoves().size());
        assertTrue(result.output().avoidList().contains("rude"));
        verify(sessions).saveBrandDna(eq(sessionId), any(), anyDouble());
    }

    @Test
    void missingPositionReturns409AndSkipsGeneration() {
        dnaStore.set(Map.of());
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(fake).run(sessionId, "tok", null));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(PersonalityService.NO_POSITION_MESSAGE, e.getReason());
        assertEquals(0, fake.calls());
        verify(sessions, never()).saveBrandDna(any(), any(), anyDouble());
        verify(sessions, never()).recordStageRun(any());
    }

    @Test
    void lockedStageRejectsRegenerateWith409() {
        when(sessions.stageLocked(any(), any())).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(new FakeLlmClient(VALID_LLM_JSON)).run(sessionId, "tok", "less playful"));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(PersonalityService.LOCKED_MESSAGE, e.getReason());
    }

    @Test
    void invalidTraitCountFallsBackToTemplateAfterRetry() {
        String tooFew = """
                {"traits":[{"name":"Only","evidence":"x","behavior":"y","neverBecome":"z"}],
                 "voice":{"formality":3,"sentenceWords":[6,16],"humorLevel":"dry",
                 "banned":["a","b","c","d","e"],"moves":["1","2","3"]}}""";
        FakeLlmClient fake = new FakeLlmClient(tooFew, tooFew);

        PersonalityService.RunResult result = service(fake).run(sessionId, "tok", null);

        assertTrue(result.degraded());
        assertEquals(2, fake.calls());
        assertTrue(result.output().traits().size() >= 3);
    }

    @Test
    void formalityOutOfRangeIsRejectedByValidation() {
        String badFormality = VALID_LLM_JSON.replace("\"formality\": 2", "\"formality\": 9");
        FakeLlmClient fake = new FakeLlmClient(badFormality, badFormality);

        PersonalityService.RunResult result = service(fake).run(sessionId, "tok", null);

        assertTrue(result.degraded());
        assertEquals(2, fake.calls());
        assertTrue(result.output().voice().formality() >= 1
                && result.output().voice().formality() <= 5);
    }

    @Test
    void blankWhyFitsIsRejectedAndRetried() {
        String blank = VALID_LLM_JSON.replace("\"hates wasting time\"", "\"   \"");
        FakeLlmClient fake = new FakeLlmClient(blank, blank);

        PersonalityService.RunResult result = service(fake).run(sessionId, "tok", null);

        assertTrue(result.degraded());
        assertEquals(2, fake.calls());
        for (Trait trait : result.output().traits()) {
            assertFalse(trait.whyFits().isBlank());
        }
    }

    @Test
    void regenerateNoteIsIncludedInLlmInput() {
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);
        RecordingLlm recording = new RecordingLlm(fake);

        service(recording).run(sessionId, "tok", "less playful, more technical");

        assertTrue(recording.lastUser().contains("regenerate_note"));
        assertTrue(recording.lastUser().contains("less playful, more technical"));
    }

    private static final class RecordingLlm implements LlmClient {
        private final LlmClient delegate;
        private String lastUser;

        private RecordingLlm(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response complete(Request request) {
            lastUser = request.user();
            return delegate.complete(request);
        }

        @Override
        public boolean available() {
            return delegate.available();
        }

        String lastUser() {
            return lastUser;
        }
    }
}
