package com.brandsmith.api.launch;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.llm.LlmUnavailableException;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageResult;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class LaunchService {

    public static final String STAGE = "S8";
    public static final String DNA_NOT_READY_MESSAGE =
            "Choose a position and complete identity before generating launch assets.";
    public static final String LOCKED_MESSAGE =
            "Launch assets are locked. Unlock them before regenerating.";

    private static final Logger log = LoggerFactory.getLogger(LaunchService.class);

    private final SessionService sessions;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final String mainModel;

    public LaunchService(SessionService sessions,
                         LlmClient llm,
                         PromptLoader prompts,
                         StageRunner runner,
                         BudgetGuard budget,
                         ObjectMapper mapper,
                         @Value("${brandsmith.llm.main-model}") String mainModel) {
        this.sessions = sessions;
        this.llm = llm;
        this.prompts = prompts;
        this.runner = runner;
        this.budget = budget;
        this.mapper = mapper;
        this.mainModel = mainModel;
    }

    public RunResult run(UUID id, String token, String note) {
        SessionService.StageSnapshot snapshot = sessions.loadStage(id, token);
        Map<String, Object> dna = snapshot.brandDna();
        if (!present(dna.get("position")) || !present(dna.get("identity"))) {
            throw new ResponseStatusException(CONFLICT, DNA_NOT_READY_MESSAGE);
        }
        if (sessions.stageLocked(id, STAGE)) {
            throw new ResponseStatusException(CONFLICT, LOCKED_MESSAGE);
        }

        Generation gen = generate(dna, note, snapshot.spentUsd(), snapshot.capUsd());
        LaunchOutput output = gen.output();

        Map<String, Object> nextDna = new LinkedHashMap<>(dna);
        nextDna.put("assets", output);
        sessions.saveBrandDna(id, nextDna, gen.costUsd());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("position", dna.get("position"));
        input.put("identity", dna.get("identity"));
        if (note != null && !note.isBlank()) {
            input.put("note", note.strip());
        }
        sessions.recordStageRun(new SessionService.StageRunRecord(
                id, STAGE, input, output, gen.rawResponse(), gen.model(), gen.promptVersion(),
                gen.latencyMs(), gen.tokensIn(), gen.tokensOut(), gen.degraded()));

        return new RunResult(output, gen.latencyMs(), gen.degraded(), gen.model(), gen.promptVersion());
    }

    private Generation generate(Map<String, Object> dna, String note,
                                double spentUsd, double capUsd) {
        if (!llm.available()) {
            return templateGeneration();
        }
        budget.ensureWithinCap(spentUsd, capUsd);
        try {
            StageResult<LaunchOutput> result = runner.run(llm, prompts.load("s8-launch"),
                    userMessage(dna, note), mainModel, LaunchOutput.class);
            if (result.value() != null) {
                double cost = budget.cost(result.model(), result.tokensIn(), result.tokensOut());
                return new Generation(result.value(), false, result.model(), result.promptVersion(),
                        result.rawResponse(), result.tokensIn(), result.tokensOut(),
                        result.latencyMs(), cost);
            }
            log.warn("S8 degraded to template: {}", result.error());
            return templateGeneration();
        } catch (LlmUnavailableException e) {
            log.warn("S8 degraded to template: {}", e.getMessage());
            return templateGeneration();
        }
    }

    private String userMessage(Map<String, Object> dna, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("<data brand_dna>\n").append(toJson(dna)).append("\n</data>");
        if (note != null && !note.isBlank()) {
            sb.append("\n<data user_note>\n").append(note.strip()).append("\n</data>");
        }
        return sb.toString();
    }

    private Generation templateGeneration() {
        return new Generation(template(), true, "template", null, null, 0, 0, 0, 0);
    }

    private LaunchOutput template() {
        LaunchOutput.Hero hero = new LaunchOutput.Hero(
                "Never miss the moment again",
                "One clear path from rough idea to launch-ready brand.",
                "Start free");
        return new LaunchOutput(hero,
                "Most founders settle for a generic kit. We build a distinct brand from one sentence "
                        + "and check every asset against locked brand rules before you launch.",
                List.of(
                        "You have one sentence. Most tools answer with the same forgettable draft.",
                        "Three debates, an anti-generic loop and a consistency audit later, the kit holds.",
                        "Run your idea through Brandsmith and ship a brand you can defend."),
                "Brandsmith turns one rough sentence into a launch-ready brand kit.",
                "Brandsmith interviews you, argues about positioning, attacks its own generic output "
                        + "until it improves, and scores every asset against locked Brand DNA before delivery. "
                        + "Export the kit, share it by link, and keep later copy consistent.");
    }

    private static boolean present(Object value) {
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        return value != null;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    public record RunResult(LaunchOutput output,
                            long latencyMs,
                            boolean degraded,
                            String model,
                            String promptVersion) {
    }

    private record Generation(LaunchOutput output,
                              boolean degraded,
                              String model,
                              String promptVersion,
                              String rawResponse,
                              int tokensIn,
                              int tokensOut,
                              long latencyMs,
                              double costUsd) {
    }
}
