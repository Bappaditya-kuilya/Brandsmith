package com.brandsmith.api.battle;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.battle.JudgeResult.Regenerated;
import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.interview.BriefField;
import com.brandsmith.api.interview.BriefFieldId;
import com.brandsmith.api.interview.BriefState;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.llm.LlmUnavailableException;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageResult;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class BattleService {

    public static final String STAGE = "S2";
    public static final String BRIEF_NOT_DONE_MESSAGE =
            "Interview is not finished. Complete the brief before running the Positioning Battle.";
    public static final String NO_POSITIONS_MESSAGE = "Run the Positioning Battle before selecting a position.";
    public static final String LOCKED_MESSAGE =
            "Positioning Battle is locked. Unlock it before regenerating.";

    static final List<String> MANDATES = List.of("native", "contrarian", "emotional");
    static final String DIVERGE_INSTRUCTION =
            "Your previous attempt shared a category frame or differentiator with another position. "
                    + "Diverge: use a clearly different category frame and a clearly different differentiator. "
                    + "Do not reuse wording from the other positions.";

    private static final Set<String> EDITABLE_FIELDS = Set.of(
            "category", "frameOfReference", "target", "insight", "differentiator",
            "valueProposition", "proofPoints", "competitiveAngle", "biggestRisk");
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Logger log = LoggerFactory.getLogger(BattleService.class);

    private final SessionService sessions;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final String mainModel;

    public BattleService(SessionService sessions,
                         LlmClient llm,
                         PromptLoader prompts,
                         StageRunner runner,
                         BudgetGuard budget,
                         ObjectMapper mapper,
                         Validator validator,
                         @Value("${brandsmith.llm.main-model}") String mainModel) {
        this.sessions = sessions;
        this.llm = llm;
        this.prompts = prompts;
        this.runner = runner;
        this.budget = budget;
        this.mapper = mapper;
        this.validator = validator;
        this.mainModel = mainModel;
    }

    public void precheck(UUID id, String token, String note) {
        ready(id, token, note);
    }

    public BattleResult run(UUID id, String token, String note, Consumer<SseEvent> sink) {
        Ready ready = ready(id, token, note);
        BriefState brief = ready.brief();

        sink.accept(new SseEvent("stage_started", Map.of(
                "stage", STAGE,
                "sessionId", id.toString(),
                "mandates", MANDATES)));

        List<AgentRun> runs = generateAll(id, brief, note, null, sink);
        double cost = costOf(runs);

        sink.accept(new SseEvent("progress", progress("Judge is scoring 3 positions", 3, 3, null)));
        JudgeOutcome judge = judge(id, brief, positionsOf(runs),
                ready.snapshot().spentUsd() + cost, ready.snapshot().capUsd());
        cost += costOf(judge);
        JudgeResult judgeResult = judge.result();

        Collision collision = findCollision(positionsOf(runs));
        if (collision != null) {
            int weaker = weakerIndex(positionsOf(runs), judgeResult, collision);
            int other = collision.i() == weaker ? collision.j() : collision.i();
            String weakerMandate = runs.get(weaker).position().mandate();
            String otherMandate = runs.get(other).position().mandate();
            log.warn("S2 difference check: {} collides with {} on frame and differentiator",
                    weakerMandate, otherMandate);
            sink.accept(new SseEvent("progress",
                    progress("Regenerating " + weakerMandate + " position with diverge instruction",
                            3, 3, weakerMandate)));

            List<Position> others = new ArrayList<>();
            for (int i = 0; i < runs.size(); i++) {
                if (i != weaker) {
                    others.add(runs.get(i).position());
                }
            }
            AgentRun regenerated = generateOne(id, brief, weakerMandate, note, others);
            runs.set(weaker, regenerated);
            cost += budget.cost(regenerated.model(), regenerated.tokensIn(), regenerated.tokensOut());

            sink.accept(new SseEvent("progress", progress("Judge is rescoring positions", 3, 3, null)));
            JudgeOutcome second = judge(id, brief, positionsOf(runs),
                    ready.snapshot().spentUsd() + cost, ready.snapshot().capUsd());
            cost += costOf(second);
            judgeResult = new JudgeResult(second.result().scores(),
                    List.of(new Regenerated(weakerMandate, otherMandate,
                            "shared category frame and similar differentiator")));
            if (findCollision(positionsOf(runs)) != null) {
                log.warn("S2 difference check still collides after one diverge regeneration");
            }
        }

        BattleResult result = new BattleResult(positionsOf(runs), judgeResult, null);
        persist(id, ready.snapshot().brandDna(), result, cost);
        sink.accept(new SseEvent("stage_completed", result));
        return result;
    }

    private Ready ready(UUID id, String token, String note) {
        if (note != null && note.length() > 500) {
            throw new ResponseStatusException(BAD_REQUEST, "note must be 500 characters or fewer");
        }
        SessionService.StageSnapshot snapshot = sessions.loadStage(id, token);
        BriefState brief = mapper.convertValue(snapshot.brief(), BriefState.class);
        brief.ensureFields();
        if (!brief.isDone()) {
            throw new ResponseStatusException(CONFLICT, BRIEF_NOT_DONE_MESSAGE);
        }
        if (sessions.stageLocked(id, STAGE)) {
            throw new ResponseStatusException(CONFLICT, LOCKED_MESSAGE);
        }
        budget.ensureWithinCap(snapshot.spentUsd(), snapshot.capUsd());
        return new Ready(snapshot, brief);
    }

    private record Ready(SessionService.StageSnapshot snapshot, BriefState brief) {
    }

    public Map<String, Object> select(UUID id, String token, SelectRequest request) {
        if (request == null || request.index() == null || request.index() < 0 || request.index() > 2) {
            throw new ResponseStatusException(BAD_REQUEST, "index must be 0, 1 or 2");
        }
        Map<String, Object> dna = sessions.loadBrandDna(id, token);
        Map<String, Object> battle = battleOf(dna);
        List<Object> positions = battle == null ? null : listOf(battle.get("positions"));
        if (positions == null || positions.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, NO_POSITIONS_MESSAGE);
        }
        int index = request.index();
        if (index >= positions.size()) {
            throw new ResponseStatusException(BAD_REQUEST, "index must be 0, 1 or 2");
        }

        Position selected;
        try {
            selected = mapper.convertValue(positions.get(index), Position.class);
            if (request.edits() != null && !request.edits().isEmpty()) {
                for (String key : request.edits().keySet()) {
                    if (!EDITABLE_FIELDS.contains(key)) {
                        throw new ResponseStatusException(BAD_REQUEST, "Unknown position field: " + key);
                    }
                }
                Map<String, Object> merged = new LinkedHashMap<>(mapper.convertValue(selected, MAP_TYPE));
                merged.putAll(request.edits());
                selected = mapper.convertValue(merged, Position.class);
            }
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid position edit");
        }
        Set<ConstraintViolation<Position>> violations = validator.validate(selected);
        if (!violations.isEmpty()) {
            ConstraintViolation<Position> first = violations.iterator().next();
            throw new ResponseStatusException(BAD_REQUEST,
                    first.getPropertyPath() + " " + first.getMessage());
        }

        battle.put("selected", index);
        dna.put("position", selected);
        sessions.saveBrandDna(id, dna, 0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("selected", index);
        response.put("position", selected);
        return response;
    }

    private List<AgentRun> generateAll(UUID id, BriefState brief, String note, List<Position> divergeAgainst,
                                       Consumer<SseEvent> sink) {
        AtomicInteger completed = new AtomicInteger();
        List<AgentRun> runs = new ArrayList<>(MANDATES.size());
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<AgentRun>> futures = new ArrayList<>(MANDATES.size());
            for (String mandate : MANDATES) {
                futures.add(pool.submit(() -> {
                    AgentRun run = generateOne(id, brief, mandate, note, divergeAgainst);
                    sink.accept(new SseEvent("progress",
                            progress(mandate + " position ready",
                                    completed.incrementAndGet(), MANDATES.size(), mandate)));
                    return run;
                }));
            }
            for (Future<AgentRun> future : futures) {
                runs.add(future.get());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Positioning Battle interrupted", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Positioning Battle failed", e.getCause());
        }
        return runs;
    }

    private AgentRun generateOne(UUID id, BriefState brief, String mandate, String note,
                                 List<Position> divergeAgainst) {
        PromptLoader.Prompt prompt = prompts.load("s2-positioning");
        boolean diverge = divergeAgainst != null;
        if (!llm.available()) {
            Position template = templatePosition(brief, mandate);
            recordRun(id, agentInput(mandate, diverge), template, null, "template", prompt.version(),
                    0, 0, 0, false);
            return new AgentRun(template, "template", 0, 0);
        }
        String user = agentUserMessage(brief, mandate, note, divergeAgainst);
        try {
            StageResult<Position> result = runner.run(llm, prompt, user, mainModel, Position.class);
            Position position = result.value() == null ? null : result.value().withMandate(mandate);
            if (position == null) {
                log.warn("S2 {} degraded to template position", mandate);
                position = templatePosition(brief, mandate);
            }
            boolean degraded = result.degraded() || result.value() == null;
            recordRun(id, agentInput(mandate, diverge), position, result.rawResponse(), result.model(),
                    result.promptVersion(), result.tokensIn(), result.tokensOut(), result.latencyMs(), degraded);
            return new AgentRun(position, result.model(), result.tokensIn(), result.tokensOut());
        } catch (LlmUnavailableException e) {
            log.warn("S2 {} degraded to template position: {}", mandate, e.getMessage());
            Position template = templatePosition(brief, mandate);
            recordRun(id, agentInput(mandate, diverge), template, null, "template", prompt.version(),
                    0, 0, 0, true);
            return new AgentRun(template, "template", 0, 0);
        }
    }

    private JudgeOutcome judge(UUID id, BriefState brief, List<Position> positions,
                               double spentUsd, double capUsd) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("role", "judge");
        input.put("positions", positions);
        PromptLoader.Prompt prompt = prompts.load("s2-judge");
        if (!llm.available()) {
            JudgeResult fallback = deterministicJudge();
            recordRun(id, input, fallback, null, "template", prompt.version(), 0, 0, 0, false);
            return new JudgeOutcome(fallback, "template", 0, 0);
        }
        budget.ensureWithinCap(spentUsd, capUsd);
        try {
            StageResult<JudgeResult> result = runner.run(llm, prompt, judgeUserMessage(brief, positions),
                    mainModel, JudgeResult.class);
            JudgeResult parsed = usable(result.value());
            boolean degraded = parsed == null || result.degraded();
            if (parsed == null) {
                log.warn("S2 judge degraded to deterministic scores");
                parsed = deterministicJudge();
            }
            recordRun(id, input, parsed, result.rawResponse(), result.model(), result.promptVersion(),
                    result.tokensIn(), result.tokensOut(), result.latencyMs(), degraded);
            return new JudgeOutcome(parsed, result.model(), result.tokensIn(), result.tokensOut());
        } catch (LlmUnavailableException e) {
            log.warn("S2 judge degraded to deterministic scores: {}", e.getMessage());
            JudgeResult fallback = deterministicJudge();
            recordRun(id, input, fallback, null, "template", prompt.version(), 0, 0, 0, true);
            return new JudgeOutcome(fallback, "template", 0, 0);
        }
    }

    private JudgeResult usable(JudgeResult result) {
        if (result == null || result.scores().size() != MANDATES.size()) {
            return null;
        }
        Set<String> seen = new HashSet<>();
        for (JudgeScore score : result.scores()) {
            if (!MANDATES.contains(score.mandate()) || !seen.add(score.mandate())) {
                return null;
            }
        }
        List<JudgeScore> ordered = new ArrayList<>();
        for (String mandate : MANDATES) {
            for (JudgeScore score : result.scores()) {
                if (mandate.equals(score.mandate())) {
                    ordered.add(score);
                }
            }
        }
        return new JudgeResult(ordered, result.regenerated());
    }

    private JudgeResult deterministicJudge() {
        return new JudgeResult(List.of(
                new JudgeScore("native", 4, 3, 5, 3, 4,
                        "Offline score without API key: credible inside the category, average distinctiveness."),
                new JudgeScore("contrarian", 3, 5, 3, 4, 2,
                        "Offline score without API key: ownable frame, feasibility carries the risk."),
                new JudgeScore("emotional", 4, 4, 3, 5, 3,
                        "Offline score without API key: memorable felt outcome, moderate proof.")), List.of());
    }

    private String judgeUserMessage(BriefState brief, List<Position> positions) {
        return "<data brief>\n" + briefLines(brief) + "</data>\n"
                + "<data positions>\n" + toJson(positions) + "\n</data>";
    }

    private String agentUserMessage(BriefState brief, String mandate, String note, List<Position> divergeAgainst) {
        StringBuilder sb = new StringBuilder();
        sb.append("<data brief>\n").append(briefLines(brief)).append("</data>\n");
        sb.append("<mandate>").append(mandate).append("</mandate>\n");
        if (note != null && !note.isBlank()) {
            sb.append("<data user_note>\n").append(note.strip()).append("\n</data>\n");
        }
        if (divergeAgainst != null) {
            sb.append("<data other_positions>\n").append(toJson(divergeAgainst)).append("\n</data>\n");
            sb.append("<instruction>\n").append(DIVERGE_INSTRUCTION).append("\n</instruction>");
        }
        return sb.toString();
    }

    private String briefLines(BriefState brief) {
        StringBuilder sb = new StringBuilder();
        sb.append("idea: ").append(nullSafe(brief.getIdea())).append('\n');
        sb.append("clean_idea: ").append(nullSafe(brief.getCleanIdea())).append('\n');
        sb.append("product_type: ").append(nullSafe(brief.getProductType())).append('\n');
        for (BriefFieldId id : BriefFieldId.values()) {
            BriefField field = brief.field(id);
            sb.append(id.id()).append(": ").append(field.value() == null ? "" : field.value()).append('\n');
        }
        return sb.toString();
    }

    private Position templatePosition(BriefState brief, String mandate) {
        String product = blankTo(brief.getProductType(), "product");
        String target = blankTo(value(brief, BriefFieldId.TARGET_USER), "the people who need it most");
        String problem = blankTo(value(brief, BriefFieldId.PROBLEM_ALTERNATIVE),
                "the problem they work around today");
        String proof = value(brief, BriefFieldId.PROOF_ADVANTAGE);
        List<String> proofs = proof == null || proof.isBlank()
                ? List.of("Early users reported the fix within a week",
                        "Founder built it from a real refused workaround")
                : List.of(proof.strip(), "Founder built it from a real refused workaround");

        return switch (mandate) {
            case "contrarian" -> new Position(
                    "Beyond " + product,
                    "a new frame: the outcome service, not another " + product,
                    target,
                    problem,
                    "defines its own category instead of competing on " + product + " features",
                    "Not another " + product + ". The service that makes the outcome inevitable.",
                    proofs,
                    "changes the comparison set from " + product + " alternatives to outcome guarantees",
                    "buyers may not yet look for a new category",
                    "contrarian");
            case "emotional" -> new Position(
                    "The feeling after " + product,
                    "the felt moment the worry stops",
                    target,
                    problem,
                    "leads with the relief of the outcome, not the feature list",
                    "The quiet confidence that it is handled.",
                    proofs,
                    "competes on how it feels, not on feature matrices",
                    "emotional claims read as fluff without proof",
                    "emotional");
            default -> new Position(
                    product + " category",
                    "the existing " + product + " alternatives people already pay for",
                    target,
                    problem,
                    "wins the same job with sharper execution and fewer dead ends",
                    "The " + product + " that just works when it matters.",
                    proofs,
                    "out-executes current alternatives on the core job",
                    "looks like a me-too entrant",
                    "native");
        };
    }

    private record Collision(int i, int j) {
    }

    static Collision findCollision(List<Position> positions) {
        for (int i = 0; i < positions.size(); i++) {
            for (int j = i + 1; j < positions.size(); j++) {
                if (similar(positions.get(i).frameOfReference(), positions.get(j).frameOfReference())
                        && similar(positions.get(i).differentiator(), positions.get(j).differentiator())) {
                    return new Collision(i, j);
                }
            }
        }
        return null;
    }

    static boolean similar(String a, String b) {
        String left = normalize(a);
        String right = normalize(b);
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        Set<String> leftTokens = tokens(left);
        Set<String> rightTokens = tokens(right);
        int intersection = 0;
        for (String token : leftTokens) {
            if (rightTokens.contains(token)) {
                intersection++;
            }
        }
        int min = Math.min(leftTokens.size(), rightTokens.size());
        return intersection >= Math.ceil(0.6 * min);
    }

    static String normalize(String value) {
        if (value == null) {
            return "";
        }
        return value.toLowerCase().replaceAll("[^a-z0-9]+", " ").strip();
    }

    private static Set<String> tokens(String normalized) {
        return new HashSet<>(Arrays.asList(normalized.split(" ")));
    }

    private static int weakerIndex(List<Position> positions, JudgeResult judge, Collision collision) {
        int left = totalFor(judge, positions.get(collision.i()).mandate());
        int right = totalFor(judge, positions.get(collision.j()).mandate());
        return left == right ? collision.j() : (left < right ? collision.i() : collision.j());
    }

    private static int totalFor(JudgeResult judge, String mandate) {
        for (JudgeScore score : judge.scores()) {
            if (mandate.equals(score.mandate())) {
                return score.total();
            }
        }
        return 0;
    }

    private void persist(UUID id, Map<String, Object> currentDna, BattleResult result, double costUsd) {
        Map<String, Object> dna = new LinkedHashMap<>(currentDna);
        Map<String, Object> battle = new LinkedHashMap<>();
        battle.put("positions", result.positions());
        battle.put("judge", result.judge());
        battle.put("selected", null);
        dna.put("battle", battle);
        dna.remove("position");
        sessions.saveBrandDna(id, dna, costUsd);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> battleOf(Map<String, Object> dna) {
        Object battle = dna.get("battle");
        return battle instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> listOf(Object value) {
        return value instanceof List<?> list ? (List<Object>) list : null;
    }

    private List<Position> positionsOf(List<AgentRun> runs) {
        List<Position> positions = new ArrayList<>(runs.size());
        for (AgentRun run : runs) {
            positions.add(run.position());
        }
        return positions;
    }

    private double costOf(List<AgentRun> runs) {
        double cost = 0;
        for (AgentRun run : runs) {
            cost += budget.cost(run.model(), run.tokensIn(), run.tokensOut());
        }
        return cost;
    }

    private double costOf(JudgeOutcome judge) {
        return budget.cost(judge.model(), judge.tokensIn(), judge.tokensOut());
    }

    private void recordRun(UUID sessionId, Map<String, Object> input, Object output, String raw,
                           String model, String promptVersion, int tokensIn, int tokensOut,
                           long latencyMs, boolean degraded) {
        sessions.recordStageRun(new SessionService.StageRunRecord(
                sessionId, STAGE, input, output, raw, model, promptVersion,
                latencyMs, tokensIn, tokensOut, degraded));
    }

    private Map<String, Object> agentInput(String mandate, boolean diverge) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("mandate", mandate);
        if (diverge) {
            input.put("diverge", true);
        }
        return input;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    private static Map<String, Object> progress(String message, int completed, int total, String mandate) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", STAGE);
        payload.put("message", message);
        payload.put("completed", completed);
        payload.put("total", total);
        if (mandate != null) {
            payload.put("mandate", mandate);
        }
        return payload;
    }

    private static String value(BriefState brief, BriefFieldId id) {
        return brief.field(id).value();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private record AgentRun(Position position, String model, int tokensIn, int tokensOut) {
    }

    private record JudgeOutcome(JudgeResult result, String model, int tokensIn, int tokensOut) {
    }

    public record SseEvent(String name, Object data) {
    }
}
