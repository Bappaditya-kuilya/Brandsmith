package com.brandsmith.api.messages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
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
import com.brandsmith.api.embedding.EmbeddingIndex;
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

class MessagesServiceTest {

    private static final String DRAFT_JSON = """
            {
              "taglines": ["Skip the scramble tonight", "Form the group before midterms", "Match on the shared deadline"],
              "pitchOneLine": "Loopform helps students form study groups before midterms, not after.",
              "pitchParagraph": "Loopform matches students by shared assignment deadlines so groups form early.",
              "hierarchy": {
                "primary": "Form study groups before midterms",
                "secondary": ["Match on shared deadlines", "Skip the group scramble"],
                "tertiary": ["Works with the classes you already have"]
              }
            }""";

    private static final String CRITIC_TEMPLATE = """
            {"specificity":5,"ownability":5,"surprise":5,"audienceFit":5,"critic_score":%d,"quotes":[]}""";

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

    /** cosine 0 => embedding score 100; use for paths that should clear 70 immediately. */
    private static final EmbeddingIndex EMB_HIGH = indexWithCosine(0.0);

    /** cosine 1 => embedding score 0; leaves critic in control of crossing 70. */
    private static final EmbeddingIndex EMB_ZERO = indexWithCosine(1.0);

    private static EmbeddingIndex indexWithCosine(double cosine) {
        return new EmbeddingIndex() {
            @Override
            public float[] embed(String text) {
                return new float[] {1f};
            }

            @Override
            public double maxCosineSimilarity(String text) {
                return cosine;
            }

            @Override
            public int corpusSize() {
                return 1;
            }
        };
    }

    private static final class NoKeyLlm extends FakeLlmClient {
        @Override
        public boolean available() {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        briefStore = new AtomicReference<>(Map.of(
                "idea", "A study group app for students"));
        dnaStore = new AtomicReference<>(Map.of(
                "identity", Map.of("name", "Loopform")));
        stageRun = new AtomicReference<>();
        when(sessions.loadStage(any(), any())).thenAnswer(inv ->
                new SessionService.StageSnapshot(briefStore.get(), dnaStore.get(), 0.0, 0.40));
        when(sessions.loadStageForUpdate(any(), any())).thenAnswer(inv ->
                new SessionService.StageSnapshot(briefStore.get(), dnaStore.get(), 0.0, 0.40));
        when(sessions.loadBrandDna(any(), any())).thenAnswer(inv -> dnaStore.get());
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

    private MessagesService service(LlmClient llm) {
        return service(llm, EMB_HIGH);
    }

    private MessagesService service(LlmClient llm, EmbeddingIndex embeddings) {
        return new MessagesService(sessions, llm, prompts, runner, budget, mapper, validator,
                embeddings, "main-model", "small-model");
    }

    @Test
    void noKeyPathBuildsAtLeastThreeTaglinesPitchHierarchyAndPersists() {
        MessagesService.RunResult result = service(new NoKeyLlm()).run(sessionId, "tok", null);

        MessagesOutput output = result.output();
        assertTrue(result.degraded());
        assertTrue(output.taglines().size() >= 3,
                "expected >=3 taglines, got " + output.taglines().size());
        for (TaglineOption option : output.taglines()) {
            assertFalse(option.text().isBlank());
            assertTrue(option.score() >= 0 && option.score() <= 100);
            assertFalse(option.attempts().isEmpty());
        }
        assertFalse(output.pitch().isBlank());
        assertFalse(output.hierarchy().primary().isBlank());
        assertFalse(output.hierarchy().secondary().isEmpty());
        assertFalse(output.hierarchy().proof().isEmpty());

        Map<String, Object> saved = dnaStore.get();
        assertNotNull(saved.get("messages"));
        assertNotNull(stageRun.get());
        assertEquals("S5", stageRun.get().stage());
        verify(sessions).saveBrandDna(eq(sessionId), any(), anyDouble());
    }

    @Test
    void degradedTaglinesDeriveAudienceFromBriefInsteadOfHardcodedCopy() {
        briefStore.set(Map.of(
                "idea", "A study group app for students",
                "fields", Map.of("target_user",
                        Map.of("value", "night-shift nursing students", "confidence", 0.9))));

        MessagesService.RunResult result = service(new NoKeyLlm()).run(sessionId, "tok", null);

        List<String> texts = result.output().taglines().stream().map(TaglineOption::text).toList();
        assertTrue(result.degraded());
        assertTrue(texts.size() >= 3, "expected >=3 taglines, got " + texts.size());
        assertTrue(texts.stream().anyMatch(t -> t.contains("night-shift nursing students")),
                "taglines should carry the brief audience: " + texts);
        assertFalse(texts.stream().anyMatch(t -> t.contains("midterms")),
                "hardcoded midterms copy must be gone: " + texts);
    }

    @Test
    void runWithoutNameReturns409AndSkipsGeneration() {
        dnaStore.set(Map.of());
        FakeLlmClient fake = new FakeLlmClient(DRAFT_JSON);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(fake).run(sessionId, "tok", null));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(MessagesService.NO_NAME_MESSAGE, e.getReason());
        assertEquals(0, fake.calls());
        verify(sessions, never()).saveBrandDna(any(), any(), anyDouble());
        verify(sessions, never()).recordStageRun(any());
    }

    @Test
    void llmPathScoresTaglinesAndPersistsMessages() {
        FakeLlmClient fake = new FakeLlmClient(
                DRAFT_JSON,
                critic(95), critic(95), critic(95));

        MessagesService.RunResult result = service(fake).run(sessionId, "tok", null);

        assertFalse(result.degraded());
        assertEquals(3, result.output().taglines().size());
        assertEquals(4, fake.calls());
        for (TaglineOption option : result.output().taglines()) {
            assertTrue(option.score() >= 70, "score should be >=70: " + option.score());
            assertEquals(1, option.attempts().size());
        }
        assertEquals("Loopform helps students form study groups before midterms, not after.",
                result.output().pitch());
        assertEquals("Form study groups before midterms", result.output().hierarchy().primary());
        assertEquals(List.of("Works with the classes you already have"),
                result.output().hierarchy().proof());
        assertNotNull(dnaStore.get().get("messages"));
    }

    @Test
    void rewriteLoopStopsAfterMaxThreeRewrites() {
        // emb score 0 (cosine 1) + clean lexicon 100 => score = 30 + 0.4*critic
        // critic 50,60,70,80 => scores 50,54,58,62 all <70, deltas 4 >=3 each time
        FakeLlmClient fake = new FakeLlmClient(
                DRAFT_JSON,
                critic(50),
                rewrite("Alpha bravo one"), critic(60),
                rewrite("Alpha bravo two"), critic(70),
                rewrite("Alpha bravo three"), critic(80),
                critic(100),
                critic(100));

        MessagesService.RunResult result = service(fake, EMB_ZERO).run(sessionId, "tok", null);

        TaglineOption first = result.output().taglines().get(0);
        assertEquals(4, first.attempts().size(),
                "initial + max 3 rewrites, got " + first.attempts().size());
        assertEquals(0, first.attempts().get(0).delta());
        for (int i = 1; i < first.attempts().size(); i++) {
            assertTrue(first.attempts().get(i).delta() >= 3);
        }
        // 1 draft + tagline1(4 critic + 3 rewrite) + tagline2 critic + tagline3 critic = 10
        assertEquals(10, fake.calls());
        assertTrue(first.score() < 70 || first.attempts().size() <= 4);
    }

    @Test
    void rewriteLoopStopsWhenDeltaUnderThree() {
        FakeLlmClient fake = new FakeLlmClient(
                DRAFT_JSON,
                critic(50),
                rewrite("Delta candidate one"), critic(52),
                critic(100),
                critic(100));

        MessagesService.RunResult result = service(fake, EMB_ZERO).run(sessionId, "tok", null);

        TaglineOption first = result.output().taglines().get(0);
        assertEquals(2, first.attempts().size());
        assertTrue(first.attempts().get(1).delta() < 3);
        // 1 draft + tagline1(2 critic + 1 rewrite) + 2 critics for later taglines = 6
        assertEquals(6, fake.calls());
    }

    @Test
    void selectPersistsTaglinePitchAndMessagesToIdentity() {
        service(new NoKeyLlm()).run(sessionId, "tok", null);

        Map<String, Object> response = service(new NoKeyLlm())
                .select(sessionId, "tok", new SelectRequest(1));

        assertEquals(1, response.get("selected"));
        Map<String, Object> dna = dnaStore.get();
        Map<?, ?> identity = (Map<?, ?>) dna.get("identity");
        Map<?, ?> messages = (Map<?, ?>) dna.get("messages");
        assertEquals(response.get("tagline"), identity.get("tagline"));
        assertNotNull(identity.get("pitch"));
        assertEquals(messages.get("pitch"), identity.get("pitch"));
        assertEquals(1, messages.get("selected"));
        assertEquals("Loopform", identity.get("name"));
        verify(sessions, org.mockito.Mockito.atLeastOnce())
                .saveBrandDna(eq(sessionId), any(), anyDouble());
    }

    @Test
    void selectWithoutMessagesReturns409() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(new NoKeyLlm()).select(sessionId, "tok", new SelectRequest(0)));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(MessagesService.NO_MESSAGES_MESSAGE, e.getReason());
    }

    @Test
    void lockedStageRejectsRegenerateWith409() {
        when(sessions.stageLocked(any(), any())).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(new FakeLlmClient(DRAFT_JSON)).run(sessionId, "tok", "punchier"));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(MessagesService.LOCKED_MESSAGE, e.getReason());
    }

    @Test
    void regenerateNoteIsIncludedInLlmInput() {
        FakeLlmClient fake = new FakeLlmClient(
                DRAFT_JSON, critic(95), critic(95), critic(95));
        RecordingLlm recording = new RecordingLlm(fake);

        service(recording).run(sessionId, "tok", "punchier, less cute");

        String draftUser = recording.users().get(0);
        assertTrue(draftUser.contains("regenerate_note"), draftUser);
        assertTrue(draftUser.contains("punchier, less cute"), draftUser);
    }

    private static String critic(int score) {
        return CRITIC_TEMPLATE.formatted(score);
    }

    private static String rewrite(String candidate) {
        return "{\"candidates\":[\"" + candidate + "\",\"Other cand two\",\"Other cand three\"]}";
    }

    private static final class RecordingLlm implements LlmClient {
        private final LlmClient delegate;
        private final java.util.List<String> users = new java.util.ArrayList<>();

        private RecordingLlm(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response complete(Request request) {
            users.add(request.user());
            return delegate.complete(request);
        }

        @Override
        public boolean available() {
            return delegate.available();
        }

        java.util.List<String> users() {
            return users;
        }
    }
}
