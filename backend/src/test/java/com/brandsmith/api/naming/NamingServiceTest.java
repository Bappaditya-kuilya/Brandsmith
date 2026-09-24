package com.brandsmith.api.naming;

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
import com.brandsmith.api.llm.FakeLlmClient;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class NamingServiceTest {

    private static final String NAMING_JSON = """
            {
              "territories": [
                {"type": "descriptive-evocative", "whyFits": "Fits a direct personality",
                 "names": [
                   {"name": "StudyGroup", "rationale": "Literal match for the brief.", "specificity": "deadline matching"},
                   {"name": "DeadlineDesk", "rationale": "Names the pain surface.", "specificity": "shared deadlines"},
                   {"name": "CohortBench", "rationale": "Audience word on a seat.", "specificity": "cohort seating"}
                 ]},
                {"type": "invented", "whyFits": "Ownable for a bold personality",
                 "names": [
                   {"name": "Studora", "rationale": "Coinage from study.", "specificity": "study + ora"},
                   {"name": "Groupolio", "rationale": "Coinage from group.", "specificity": "group + lio"},
                   {"name": "Pactumi", "rationale": "Soft coinage.", "specificity": "pact + umi"}
                 ]},
                {"type": "metaphor", "whyFits": "Warm image for a human personality",
                 "names": [
                   {"name": "HarborStudy", "rationale": "Safe harbor for studying.", "specificity": "harbor study"},
                   {"name": "NorthDesk", "rationale": "Direction without north star cliche.", "specificity": "north desk"},
                   {"name": "EmberCohort", "rationale": "Small heat that keeps cohorts going.", "specificity": "ember cohort"}
                 ]}
              ]
            }""";

    private static final String CRITIC_HIGH = """
            {"specificity":9,"ownability":9,"surprise":9,"audienceFit":9,"critic_score":100,"quotes":[]}""";
    private static final String CRITIC_LOW = """
            {"specificity":3,"ownability":3,"surprise":2,"audienceFit":3,"critic_score":40,
             "quotes":[{"phrase":"StudyGroup","issue":"too literal, anyone could claim it"}]}""";
    private static final String CRITIC_CAND_BEST = """
            {"specificity":9,"ownability":9,"surprise":9,"audienceFit":9,"critic_score":100,"quotes":[]}""";
    private static final String CRITIC_CAND_WEAK = """
            {"specificity":4,"ownability":4,"surprise":3,"audienceFit":4,"critic_score":30,"quotes":[]}""";
    private static final String CRITIC_CAND_MID = """
            {"specificity":7,"ownability":7,"surprise":6,"audienceFit":7,"critic_score":90,"quotes":[]}""";
    private static final String REWRITE_JSON = """
            {"candidates":["QuorumDesk","Pactora","MidtermHarbor"]}""";

    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptLoader prompts = new PromptLoader();
    private final Validator validator = Validation.byDefaultProvider().configure()
            .buildValidatorFactory().getValidator();
    private final StageRunner runner = new StageRunner(mapper, validator);
    private final BudgetGuard budget = new BudgetGuard("main-model", 3.0, 15.0, 1.0, 5.0);
    private final EmbeddingIndex fixedEmbeddings = new EmbeddingIndex() {
        @Override
        public float[] embed(String text) {
            return new float[64];
        }

        @Override
        public double maxCosineSimilarity(String text) {
            return 0.5;
        }

        @Override
        public int corpusSize() {
            return 1;
        }
    };
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
        briefStore = new AtomicReference<>(Map.of(
                "idea", "A study group app for students",
                "clean_idea", "A study group app that matches students by shared assignment deadlines"));
        dnaStore = new AtomicReference<>(Map.of(
                "position", Map.of("category", "Study tools"),
                "personality", Map.of("traits", List.of(Map.of("name", "Direct")))));
        stageRun = new AtomicReference<>();
        when(sessions.loadStage(any(), any())).thenAnswer(inv ->
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

    private NamingService service(LlmClient llm) {
        return new NamingService(sessions, llm, prompts, runner, budget, mapper, validator,
                fixedEmbeddings, "main-model", "small-model");
    }

    @Test
    void noKeyBuildsThreeTerritoriesAndNineScoredNames() {
        NamingService.RunResult result = service(new NoKeyLlm()).run(sessionId, "tok", null);

        assertTrue(result.degraded());
        NamingOutput output = result.output();
        assertEquals(3, output.territories().size());
        assertEquals(9, output.names().size());
        assertEquals(List.of("descriptive-evocative", "invented", "metaphor"),
                output.territories().stream().map(Territory::name).toList());
        assertNotNull(output.domainDisclaimer());

        for (Territory territory : output.territories()) {
            assertFalse(territory.rationale().isBlank());
            assertFalse(territory.whyFitsPersonality().isBlank());
        }
        for (BrandName name : output.names()) {
            assertTrue(name.length() > 0);
            assertTrue(name.pronounceability() >= 0 && name.pronounceability() <= 100);
            assertTrue(name.lexiconScore() >= 0 && name.lexiconScore() <= 100);
            assertTrue(name.embeddingScore() >= 0 && name.embeddingScore() <= 100);
            assertTrue(name.criticScore() >= 0 && name.criticScore() <= 100);
            assertTrue(name.antiGenericScore() >= 0 && name.antiGenericScore() <= 100);
            assertNull(name.domainSignal());
            assertFalse(name.attempts().isEmpty());
            assertEquals(name.name(), name.attempts().get(0).name());
        }

        Map<String, Object> saved = dnaStore.get();
        assertNotNull(saved.get("naming"));
        assertNotNull(stageRun.get());
        assertEquals("S4", stageRun.get().stage());
        verify(sessions).saveBrandDna(eq(sessionId), any(), eq(0.0));
    }

    @Test
    void llmPathScoresNineNamesAndSkipsLoopWhenAllAboveThreshold() {
        FakeLlmClient fake = new FakeLlmClient(NAMING_JSON,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH);

        NamingService.RunResult result = service(fake).run(sessionId, "tok", null);

        assertFalse(result.degraded());
        assertEquals(9, result.output().names().size());
        assertEquals(10, fake.calls());
        for (BrandName name : result.output().names()) {
            assertTrue(name.antiGenericScore() >= 70, name.name() + " -> " + name.antiGenericScore());
            assertEquals(1, name.attempts().size(), "loop must not run when score >= 70");
        }
    }

    @Test
    void loopRewritesLowScoreAndStopsWhenCriticTurnsHigh() {
        FakeLlmClient fake = new FakeLlmClient(NAMING_JSON,
                CRITIC_LOW,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH,
                REWRITE_JSON,
                CRITIC_CAND_WEAK, CRITIC_CAND_BEST, CRITIC_CAND_MID);

        NamingService.RunResult result = service(fake).run(sessionId, "tok", null);

        BrandName weak = result.output().names().get(0);
        assertEquals("StudyGroup", weak.attempts().get(0).name());
        assertTrue(weak.attempts().get(0).antiGenericScore() < 70);
        assertEquals(2, weak.attempts().size());
        assertEquals("Pactora", weak.name());
        assertTrue(weak.antiGenericScore() >= 70);
        double delta = weak.antiGenericScore() - weak.attempts().get(0).antiGenericScore();
        assertTrue(delta > 3, "before/after delta should be visible: " + delta);

        for (int i = 1; i < 9; i++) {
            BrandName other = result.output().names().get(i);
            assertEquals(1, other.attempts().size());
            assertTrue(other.antiGenericScore() >= 70);
        }
        assertEquals(14, fake.calls());
    }

    @Test
    void loopStopsWhenImprovementUnderThreePoints() {
        FakeLlmClient fake = new FakeLlmClient(NAMING_JSON,
                CRITIC_LOW,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH,
                REWRITE_JSON,
                critic(45), CRITIC_CAND_WEAK, critic(40));

        NamingService.RunResult result = service(fake).run(sessionId, "tok", null);

        BrandName weak = result.output().names().get(0);
        assertEquals(2, weak.attempts().size());
        double improvement = weak.antiGenericScore() - weak.attempts().get(0).antiGenericScore();
        assertTrue(improvement > 0 && improvement < 3, "improvement=" + improvement);
        assertTrue(weak.antiGenericScore() < 70);
        assertEquals(14, fake.calls(), "must not run a second rewrite round");
    }

    @Test
    void loopRunsAtMostThreeRounds() {
        FakeLlmClient fake = new FakeLlmClient(NAMING_JSON,
                CRITIC_LOW,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH,
                REWRITE_JSON,
                CRITIC_CAND_WEAK, critic(48), CRITIC_CAND_WEAK,
                REWRITE_JSON,
                CRITIC_CAND_WEAK, critic(56), CRITIC_CAND_WEAK,
                REWRITE_JSON,
                CRITIC_CAND_WEAK, critic(63), CRITIC_CAND_WEAK);

        NamingService.RunResult result = service(fake).run(sessionId, "tok", null);

        BrandName weak = result.output().names().get(0);
        assertEquals(4, weak.attempts().size(), "initial + 3 rewrite rounds");
        assertTrue(weak.antiGenericScore() >= 70);
        assertEquals(22, fake.calls());
    }

    @Test
    void missingPersonalityReturns409AndSkipsGeneration() {
        dnaStore.set(Map.of("position", Map.of("category", "Study tools")));
        FakeLlmClient fake = new FakeLlmClient(NAMING_JSON);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(fake).run(sessionId, "tok", null));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(NamingService.NO_PERSONALITY_MESSAGE, e.getReason());
        assertEquals(0, fake.calls());
        verify(sessions, never()).saveBrandDna(any(), any(), anyDouble());
        verify(sessions, never()).recordStageRun(any());
    }

    @Test
    void lockedStageRejectsRunWith409() {
        when(sessions.stageLocked(any(), any())).thenReturn(true);
        FakeLlmClient fake = new FakeLlmClient(NAMING_JSON);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(fake).run(sessionId, "tok", null));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(NamingService.LOCKED_MESSAGE, e.getReason());
        assertEquals(0, fake.calls());
    }

    @Test
    void selectPersistsChosenNameIntoIdentity() {
        service(new NoKeyLlm()).run(sessionId, "tok", null);
        String chosen = dnaStoreValue().names().get(4).name();

        Map<String, Object> response = service(new NoKeyLlm())
                .select(sessionId, "tok", new SelectRequest(4, null));

        assertEquals(chosen, response.get("name"));
        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) dnaStore.get().get("identity");
        assertEquals(chosen, identity.get("name"));
        assertNotNull(stageRun.get());
    }

    @Test
    void selectByNameMatchesIgnoreCase() {
        service(new NoKeyLlm()).run(sessionId, "tok", null);
        String chosen = dnaStoreValue().names().get(0).name();

        Map<String, Object> response = service(new NoKeyLlm())
                .select(sessionId, "tok", new SelectRequest(null, chosen.toUpperCase()));

        assertEquals(chosen, response.get("name"));
        assertEquals(0, response.get("nameIndex"));
    }

    @Test
    void selectWithoutNamingReturns409() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(new NoKeyLlm()).select(sessionId, "tok", new SelectRequest(0, null)));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(NamingService.NO_NAMES_MESSAGE, e.getReason());
    }

    @Test
    void regenerateNoteIsIncludedInLlmInput() {
        FakeLlmClient fake = new FakeLlmClient(NAMING_JSON,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH,
                CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH, CRITIC_HIGH);
        RecordingLlm recording = new RecordingLlm(fake);

        service(recording).run(sessionId, "tok", "more technical, less playful");

        assertTrue(recording.lastUser().contains("regenerate_note"));
        assertTrue(recording.lastUser().contains("more technical"));
    }

    private NamingOutput dnaStoreValue() {
        return mapper.convertValue(dnaStore.get().get("naming"), NamingOutput.class);
    }

    private static String critic(int score) {
        return "{\"specificity\":5,\"ownability\":5,\"surprise\":5,\"audienceFit\":5,"
                + "\"critic_score\":" + score + ",\"quotes\":[]}";
    }

    private static final class RecordingLlm implements LlmClient {
        private final LlmClient delegate;
        private String firstUser;

        private RecordingLlm(LlmClient delegate) {
            this.delegate = delegate;
        }

        @Override
        public Response complete(Request request) {
            if (firstUser == null) {
                firstUser = request.user();
            }
            return delegate.complete(request);
        }

        @Override
        public boolean available() {
            return delegate.available();
        }

        String lastUser() {
            return firstUser;
        }
    }
}
