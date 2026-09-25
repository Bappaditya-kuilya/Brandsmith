package com.brandsmith.api.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.interview.BriefFieldId;
import com.brandsmith.api.interview.BriefState;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;

import static org.springframework.http.HttpStatus.CONFLICT;

class BattleServiceTest {

    private static final Pattern MANDATE_TAG = Pattern.compile("<mandate>(\\w+)</mandate>");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private static final String JUDGE_JSON = """
            {"scores":[
              {"mandate":"native","audienceFit":5,"distinctiveness":5,"credibility":5,"memorability":5,"feasibility":5,"explanation":"Strong on every criterion."},
              {"mandate":"contrarian","audienceFit":1,"distinctiveness":1,"credibility":1,"memorability":1,"feasibility":1,"explanation":"Weak on every criterion."},
              {"mandate":"emotional","audienceFit":3,"distinctiveness":3,"credibility":3,"memorability":3,"feasibility":3,"explanation":"Middle of the road."}]}""";

    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptLoader prompts = new PromptLoader();
    private final StageRunner runner = new StageRunner(mapper,
            Validation.byDefaultProvider().configure().buildValidatorFactory().getValidator());
    private final BudgetGuard budget = new BudgetGuard("main-model", 3.0, 15.0, 1.0, 5.0);
    private final UUID sessionId = UUID.randomUUID();

    private SessionService sessions;
    private AtomicReference<Map<String, Object>> briefStore;
    private AtomicReference<Map<String, Object>> dnaStore;

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
        BriefState done = new BriefState();
        done.setIdea("A study group app that matches students by shared assignment deadlines");
        done.setCleanIdea("A study group app that matches students by shared assignment deadlines");
        done.setProductType("student tool");
        for (BriefFieldId id : BriefFieldId.values()) {
            done.applyUserEdit(id.id(), "Seed answer for " + id.id());
        }
        briefStore = new AtomicReference<>(toMap(done));
        dnaStore = new AtomicReference<>(new java.util.LinkedHashMap<>());
        when(sessions.loadStage(any(), any()))
                .thenAnswer(inv -> new SessionService.StageSnapshot(briefStore.get(), dnaStore.get(), 0.0, 0.40));
        when(sessions.loadBrandDna(any(), any())).thenAnswer(inv -> dnaStore.get());
        doAnswer(inv -> {
            dnaStore.set(inv.getArgument(1));
            return null;
        }).when(sessions).saveBrandDna(any(), any(), anyDouble());
    }

    private BattleService service(LlmClient llm) {
        return new BattleService(sessions, llm, prompts, runner, budget, mapper,
                Validation.byDefaultProvider().configure().buildValidatorFactory().getValidator(),
                "main-model");
    }

    private Map<String, Object> toMap(BriefState brief) {
        return mapper.convertValue(brief, MAP_TYPE);
    }

    private static String positionJson(String mandate, String frame, String differentiator) {
        return """
                {"category":"category for %s","frameOfReference":"%s","target":"Students on deadlines",
                "insight":"They panic the night before.","differentiator":"%s",
                "valueProposition":"Value for %s","proofPoints":["proof one","proof two"],
                "competitiveAngle":"Angle for %s","biggestRisk":"Risk for %s"}"""
                .formatted(mandate, frame, differentiator, mandate, mandate, mandate);
    }

    private static String mandateOf(String user) {
        Matcher matcher = MANDATE_TAG.matcher(user);
        if (!matcher.find()) {
            return null;
        }
        return matcher.group(1);
    }

    private static LlmClient.Response llm(String text, String model) {
        return new LlmClient.Response(text, model, 10, 20, 1);
    }

    @Test
    void threeMandatesRunInParallel() {
        CyclicBarrier barrier = new CyclicBarrier(3);
        LlmClient llm = new LlmClient() {
            @Override
            public Response complete(Request request) {
                String mandate = mandateOf(request.user());
                if (mandate == null) {
                    return llm(JUDGE_JSON, request.model());
                }
                try {
                    barrier.await(5, TimeUnit.SECONDS);
                } catch (Exception e) {
                    throw new RuntimeException("mandates did not run concurrently", e);
                }
                return llm(positionJson(mandate, mandate + " lane", mandate + " edge"),
                        request.model());
            }

            @Override
            public boolean available() {
                return true;
            }
        };

        BattleResult result = service(llm).run(sessionId, "tok", null, event -> {
        });

        assertEquals(3, result.positions().size());
        Set<String> mandates = new HashSet<>();
        for (Position position : result.positions()) {
            mandates.add(position.mandate());
        }
        assertEquals(Set.of("native", "contrarian", "emotional"), mandates);
        for (Position position : result.positions()) {
            assertEquals(position.mandate() + " lane", position.frameOfReference());
        }
        assertEquals(3, result.judge().scores().size());
        assertNull(result.selected());
    }

    @Test
    void identicalFramesForceDivergeRegenerationOfWeakerPosition() {
        String sharedFrame = "the student productivity app category";
        String sharedDifferentiator = "smart deadline reminders";
        AtomicReference<String> divergedMandate = new AtomicReference<>();
        LlmClient llm = new LlmClient() {
            @Override
            public Response complete(Request request) {
                String mandate = mandateOf(request.user());
                if (mandate == null) {
                    return llm(JUDGE_JSON, request.model());
                }
                if (request.user().contains("Diverge")) {
                    divergedMandate.set(mandate);
                    return llm(positionJson(mandate, "fresh frame for " + mandate, "fresh diff for " + mandate),
                            request.model());
                }
                if ("native".equals(mandate) || "contrarian".equals(mandate)) {
                    return llm(positionJson(mandate, sharedFrame, sharedDifferentiator), request.model());
                }
                return llm(positionJson(mandate, "emotional frame", "emotional diff"), request.model());
            }

            @Override
            public boolean available() {
                return true;
            }
        };

        BattleResult result = service(llm).run(sessionId, "tok", null, event -> {
        });

        assertEquals("contrarian", divergedMandate.get());
        assertEquals(1, result.judge().regenerated().size());
        assertEquals("contrarian", result.judge().regenerated().get(0).mandate());
        assertEquals("native", result.judge().regenerated().get(0).against());
        assertTrue(result.judge().differenceCheck().ok());
        assertNull(BattleService.findCollision(result.positions()));
        Position contrarian = result.positions().stream()
                .filter(p -> "contrarian".equals(p.mandate()))
                .findFirst()
                .orElseThrow();
        assertNotEquals(sharedFrame, contrarian.frameOfReference());
    }

    @Test
    void noKeyTemplatesDifferInCategoryFrame() {
        BattleResult result = service(new NoKeyLlm()).run(sessionId, "tok", null, event -> {
        });

        assertEquals(3, result.positions().size());
        List<Position> positions = result.positions();
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                assertNotEquals(
                        BattleService.normalize(positions.get(i).frameOfReference()),
                        BattleService.normalize(positions.get(j).frameOfReference()),
                        "template positions must differ in category frame");
            }
        }
        assertEquals(3, result.judge().scores().size());
        for (JudgeScore score : result.judge().scores()) {
            assertTrue(score.total() >= 5 && score.total() <= 25);
            assertNotNull(score.explanation());
        }
        assertNull(result.selected());
        assertNull(BattleService.findCollision(positions));
        assertNull(result.judge().differenceCheck());
    }

    @Test
    void persistentFrameCollisionReportsFailedDifferenceCheck() throws Exception {
        String sharedFrame = "the student productivity app category";
        String sharedDifferentiator = "smart deadline reminders";
        LlmClient llm = new LlmClient() {
            @Override
            public Response complete(Request request) {
                String mandate = mandateOf(request.user());
                if (mandate == null) {
                    return llm(JUDGE_JSON, request.model());
                }
                if ("native".equals(mandate) || "contrarian".equals(mandate)) {
                    return llm(positionJson(mandate, sharedFrame, sharedDifferentiator), request.model());
                }
                return llm(positionJson(mandate, "emotional frame", "emotional diff"), request.model());
            }

            @Override
            public boolean available() {
                return true;
            }
        };

        BattleResult result = service(llm).run(sessionId, "tok", null, event -> {
        });

        JudgeResult.DifferenceCheck diff = result.judge().differenceCheck();
        assertFalse(diff.ok());
        assertTrue(diff.note().contains("contrarian"));
        assertTrue(diff.note().contains("native"));
        assertTrue(mapper.writeValueAsString(result.judge()).contains("differenceCheck"));
        assertFalse(mapper.writeValueAsString(
                new JudgeResult(result.judge().scores(), List.of())).contains("differenceCheck"));
    }

    @Test
    void selectAppliesEditsAndPersists() {
        service(new NoKeyLlm()).run(sessionId, "tok", null, event -> {
        });

        Map<String, Object> response = service(new NoKeyLlm())
                .select(sessionId, "tok", new SelectRequest(1, Map.of(
                        "valueProposition", "Edited claim.",
                        "proofPoints", List.of("single proof"))));

        assertEquals(1, response.get("selected"));
        Position savedPosition = (Position) dnaStore.get().get("position");
        assertEquals("Edited claim.", savedPosition.valueProposition());
        assertEquals("contrarian", savedPosition.mandate());
        @SuppressWarnings("unchecked")
        Map<String, Object> battle = (Map<String, Object>) dnaStore.get().get("battle");
        assertEquals(1, battle.get("selected"));
        assertEquals(3, ((List<?>) battle.get("positions")).size());
    }

    @Test
    void briefNotDoneRejectsWithConflict() {
        briefStore.set(toMap(new BriefState()));

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service(new NoKeyLlm()).run(sessionId, "tok", null, event -> {
                }));

        assertEquals(CONFLICT, error.getStatusCode());
        assertEquals(BattleService.BRIEF_NOT_DONE_MESSAGE, error.getReason());
    }

    @Test
    void selectWithoutBattleRejectsWithConflict() {
        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service(new NoKeyLlm()).select(sessionId, "tok", new SelectRequest(0, null)));

        assertEquals(CONFLICT, error.getStatusCode());
        assertEquals(BattleService.NO_POSITIONS_MESSAGE, error.getReason());
    }

    @Test
    void selectRejectsUnknownEditField() {
        service(new NoKeyLlm()).run(sessionId, "tok", null, event -> {
        });

        ResponseStatusException error = assertThrows(ResponseStatusException.class,
                () -> service(new NoKeyLlm())
                        .select(sessionId, "tok", new SelectRequest(0, Map.of("mandate", "native"))));

        assertEquals(org.springframework.http.HttpStatus.BAD_REQUEST, error.getStatusCode());
        assertTrue(String.valueOf(error.getReason()).contains("Unknown position field"));
    }

    @Test
    void similarComparesNormalizedOverlap() {
        assertTrue(BattleService.similar("The Student App category!", "the student app category"));
        assertTrue(BattleService.similar("smart deadline reminders", "Smart deadline reminders."));
        assertTrue(!BattleService.similar("smart deadline reminders", "emotional relief rituals"));
    }

    @Test
    void stageRunsRecordedForEveryAgentAndJudge() {
        AtomicInteger recorded = new AtomicInteger();
        doAnswer(inv -> {
            recorded.incrementAndGet();
            return null;
        }).when(sessions).recordStageRun(any());

        service(new NoKeyLlm()).run(sessionId, "tok", null, event -> {
        });

        assertEquals(4, recorded.get(), "3 agent rows + 1 judge row");
    }
}
