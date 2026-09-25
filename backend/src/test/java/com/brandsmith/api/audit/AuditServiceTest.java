package com.brandsmith.api.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.llm.FakeLlmClient;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageRunner;
import com.brandsmith.api.stage.StageRunRecorder;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;

class AuditServiceTest {

    private static final String JUDGE_LOW_VOICE = """
            {"scores":{"personalityFit":85,"voiceCompliance":40,"audienceFit":85,"positioningAlignment":85,"visualCoherence":90},
             "overall":75,
             "findings":[{"dimension":"voiceCompliance","asset":"tagline","quote":"Group up!!","rule":"tone drift from voice spec","severity":"fail"}],
             "reviseInstructions":[{"asset":"tagline","instruction":"rewrite without shouting"}]}""";

    private static final String GOOD_DNA = """
            {
              "brief": {"audience": "Students cramming deadlines"},
              "position": {"category": "Study tools", "differentiator": "Deadline matching"},
              "personality": {
                "traits": [{"name": "Direct", "whyFits": "brief says clarity", "behavior": "lead with outcome", "neverBecome": "Cruel"}],
                "avoidList": []
              },
              "voice": {"formality": 3, "sentenceWords": [6, 16], "humorLevel": "dry",
                        "bannedWords": ["revolutionize"], "signatureMoves": ["open with the problem"]},
              "identity": {"name": "Deadline Club", "tagline": "Group up before midterms",
                           "pitch": "Students use this app to find a study group before the deadline."},
              "visual": {"fonts": ["display-sans", "body-sans"],
                         "palette": {"bg": "#ffffff", "surface": "#f4f4f5", "accent": "#1d4ed8", "fg": "#111111", "muted": "#6b7280"}},
              "assets": {"hero": "Find your group before the deadline hits."}
            }""";

    private static final String BANNED_TAGLINE_DNA = GOOD_DNA.replace(
            "\"tagline\": \"Group up before midterms\"",
            "\"tagline\": \"We will revolutionize study groups\"");

    private static final TypeReference<java.util.LinkedHashMap<String, Object>> MAP_TYPE =
            new TypeReference<>() {
            };

    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptLoader prompts = new PromptLoader();
    private final StageRunner runner = new StageRunner(mapper,
            Validation.byDefaultProvider().configure().buildValidatorFactory().getValidator());
    private final BudgetGuard budget = new BudgetGuard("main-model", 3.0, 15.0, 1.0, 5.0);
    private final UUID sessionId = UUID.randomUUID();

    private SessionService sessions;
    private AtomicReference<Map<String, Object>> dnaStore;
    private AtomicReference<Map<String, Object>> briefStore;
    private StageRunRecorder recorder;
    private JdbcTemplate jdbc;

    private static final class NoKeyLlm implements LlmClient {
        @Override
        public Response complete(Request request) {
            throw new AssertionError("LLM must not be called without an API key");
        }

        @Override
        public boolean available() {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        recorder = mock(StageRunRecorder.class);
        jdbc = mock(JdbcTemplate.class);
        dnaStore = new AtomicReference<>(readMap(GOOD_DNA));
        briefStore = new AtomicReference<>(mapper.convertValue(Map.of(
                "idea", "A study group app",
                "fields", Map.of("target_user",
                        Map.of("value", "Students cramming deadlines", "confidence", 0.9))),
                MAP_TYPE));
        when(sessions.loadStage(any(), any())).thenAnswer(inv ->
                new SessionService.StageSnapshot(briefStore.get(), dnaStore.get(), 0.0, 0.40));
        when(sessions.loadStageForUpdate(any(), any())).thenAnswer(inv ->
                new SessionService.StageSnapshot(briefStore.get(), dnaStore.get(), 0.0, 0.40));
        when(sessions.stageLocked(any(), any())).thenReturn(false);
        when(recorder.start(any(), any(), any())).thenAnswer(inv ->
                new StageRunRecorder.Run(UUID.randomUUID(), inv.getArgument(0), inv.getArgument(1)));
        doAnswer(inv -> {
            dnaStore.set(inv.getArgument(1));
            return null;
        }).when(sessions).saveBrandDna(any(), any(), anyDouble());
    }

    private AuditService service(LlmClient llm) {
        return new AuditService(sessions, llm, prompts, runner, budget, mapper, recorder, jdbc, "main-model");
    }

    private Map<String, Object> readMap(String json) {
        try {
            return mapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void weightsMatchSpecAndSumToHundred() {
        assertEquals(List.of("personalityFit", "voiceCompliance", "audienceFit",
                "positioningAlignment", "visualCoherence"), AuditService.DIMENSIONS);
        assertEquals(25, AuditService.weightOf("personalityFit"));
        assertEquals(25, AuditService.weightOf("voiceCompliance"));
        assertEquals(20, AuditService.weightOf("audienceFit"));
        assertEquals(20, AuditService.weightOf("positioningAlignment"));
        assertEquals(10, AuditService.weightOf("visualCoherence"));
        int sum = AuditService.DIMENSIONS.stream().mapToInt(AuditService::weightOf).sum();
        assertEquals(100, sum);
    }

    @Test
    void overallIsWeightedAverageRoundedDown() {
        List<DimensionScore> dimensions = List.of(
                new DimensionScore("personalityFit", 25, 80, List.of(), List.of()),
                new DimensionScore("voiceCompliance", 25, 90, List.of(), List.of()),
                new DimensionScore("audienceFit", 20, 70, List.of(), List.of()),
                new DimensionScore("positioningAlignment", 20, 60, List.of(), List.of()),
                new DimensionScore("visualCoherence", 10, 50, List.of(), List.of()));

        assertEquals(73, AuditService.overallOf(dimensions));

        List<DimensionScore> perfect = AuditService.DIMENSIONS.stream()
                .map(d -> new DimensionScore(d, AuditService.weightOf(d), 100, List.of(), List.of()))
                .toList();
        assertEquals(100, AuditService.overallOf(perfect));
    }

    @Test
    void noKeyAuditIsGreenWithoutLlmCalls() {
        AuditResponse response = service(new NoKeyLlm()).run(sessionId, "tok", event -> {
        });

        assertEquals(5, response.result().dimensions().size());
        assertTrue(response.degraded());
        assertEquals(0, response.reviseRounds());
        assertTrue(response.diffs().isEmpty());
        assertEquals(AuditService.overallOf(response.result().dimensions()), response.result().overall());
        assertTrue(response.result().pass(), "clean seeded kit should pass, got overall "
                + response.result().overall());
        assertTrue(response.result().overall() >= 80, "clean seeded kit should score >= 80, got "
                + response.result().overall());
        assertEquals(25, response.result().dimension("personalityFit").weight());
        assertEquals(25, response.result().dimension("voiceCompliance").weight());
        assertEquals(20, response.result().dimension("audienceFit").weight());
        assertEquals(20, response.result().dimension("positioningAlignment").weight());
        assertEquals(10, response.result().dimension("visualCoherence").weight());
        assertTrue(response.result().dimension("audienceFit").evidence().stream()
                        .anyMatch(e -> e.contains("Students")),
                "audience evidence should quote the audience");
        assertFalse(response.result().dimension("visualCoherence").evidence().isEmpty(),
                "visual evidence should record contrast/font checks");
    }

    @Test
    void autoReviseStopsAfterTwoRounds() {
        FakeLlmClient fake = new FakeLlmClient(
                JUDGE_LOW_VOICE,
                "{\"text\":\"Group up before midterms.\"}",
                JUDGE_LOW_VOICE,
                "{\"text\":\"Meet before the deadline.\"}",
                JUDGE_LOW_VOICE);

        AuditResponse response = service(fake).run(sessionId, "tok", event -> {
        });

        assertEquals(2, response.reviseRounds());
        assertEquals(2, response.diffs().size());
        assertEquals(5, fake.calls());
        assertEquals("tagline", response.diffs().get(0).asset());
        assertEquals("voiceCompliance", response.diffs().get(0).dimension());
        assertEquals("Group up before midterms", response.diffs().get(0).before());
        assertEquals("Group up before midterms.", response.diffs().get(0).after());
        assertNotEquals(response.diffs().get(1).before(), response.diffs().get(1).after());
        assertFalse(response.degraded());
        assertTrue(response.result().dimension("voiceCompliance").failing());
        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) dnaStore.get().get("identity");
        assertEquals("Meet before the deadline.", identity.get("tagline"));
    }

    @Test
    void bannedTaglineFailsVoiceAndTemplateReviseFixesItWithoutKey() {
        dnaStore.set(readMap(BANNED_TAGLINE_DNA));

        AuditResponse response = service(new NoKeyLlm()).run(sessionId, "tok", event -> {
        });

        assertEquals(1, response.reviseRounds());
        assertEquals(1, response.diffs().size());
        Diff diff = response.diffs().get(0);
        assertEquals("tagline", diff.asset());
        assertEquals("voiceCompliance", diff.dimension());
        assertTrue(diff.before().contains("revolutionize"));
        assertFalse(diff.after().contains("revolutionize"));

        DimensionScore voice = response.result().dimension("voiceCompliance");
        assertTrue(voice.score() >= 70, "voice should recover after revise, got " + voice.score());
        assertTrue(voice.deterministicFindings().isEmpty());
        assertTrue(response.result().conflicts().isEmpty());
        assertTrue(response.result().overall() >= 80);

        @SuppressWarnings("unchecked")
        Map<String, Object> identity = (Map<String, Object>) dnaStore.get().get("identity");
        assertFalse(String.valueOf(identity.get("tagline")).contains("revolutionize"));
    }

    @Test
    void missingIdentityFailsPositioningButCannotAutoReviseAbsentText() {
        dnaStore.get().remove("identity");

        AuditResponse response = service(new NoKeyLlm()).run(sessionId, "tok", event -> {
        });

        assertTrue(response.result().dimension("positioningAlignment").failing());
        assertTrue(response.result().conflicts().stream()
                .anyMatch(f -> f.rule().contains("identity.name")));
        assertEquals(0, response.reviseRounds());
        assertTrue(response.diffs().isEmpty());
    }
}
