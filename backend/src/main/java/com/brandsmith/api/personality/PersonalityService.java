package com.brandsmith.api.personality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

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
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class PersonalityService {

    public static final String STAGE = "S3";
    public static final String NO_POSITION_MESSAGE =
            "Choose a position before running personality.";
    public static final String LOCKED_MESSAGE =
            "Personality is locked. Unlock it before regenerating.";

    private static final Logger log = LoggerFactory.getLogger(PersonalityService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SessionService sessions;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final String mainModel;

    public PersonalityService(SessionService sessions,
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

    public RunResult run(UUID id, String token, String note) {
        SessionService.StageSnapshot snapshot = sessions.loadStage(id, token);
        Map<String, Object> dna = snapshot.brandDna();
        if (!hasPosition(dna)) {
            throw new ResponseStatusException(CONFLICT, NO_POSITION_MESSAGE);
        }
        if (sessions.stageLocked(id, STAGE)) {
            throw new ResponseStatusException(CONFLICT, LOCKED_MESSAGE);
        }

        BriefState brief = mapper.convertValue(snapshot.brief(), BriefState.class);
        brief.ensureFields();
        @SuppressWarnings("unchecked")
        Map<String, Object> position = dna.get("position") instanceof Map
                ? (Map<String, Object>) dna.get("position")
                : Map.of();

        Generation gen = generate(brief, position, note, snapshot.spentUsd(), snapshot.capUsd());
        PersonalityOutput output = gen.output();
        validate(output);

        Map<String, Object> nextDna = new LinkedHashMap<>(dna);
        nextDna.put("personality", Map.of(
                "traits", output.traits(),
                "avoidList", output.avoidList() == null ? List.of() : output.avoidList()));
        nextDna.put("voice", output.voice());
        sessions.saveBrandDna(id, nextDna, gen.costUsd());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("brief", snapshot.brief());
        input.put("position", position);
        if (note != null && !note.isBlank()) {
            input.put("note", note.strip());
        }
        sessions.recordStageRun(new SessionService.StageRunRecord(
                id, STAGE, input, output, gen.rawResponse(), gen.model(), gen.promptVersion(),
                gen.latencyMs(), gen.tokensIn(), gen.tokensOut(), gen.degraded()));

        return new RunResult(output, gen.latencyMs(), gen.degraded(), gen.model(), gen.promptVersion());
    }

    private Generation generate(BriefState brief, Map<String, Object> position, String note,
                                double spentUsd, double capUsd) {
        if (!llm.available()) {
            return templateGeneration(brief);
        }
        budget.ensureWithinCap(spentUsd, capUsd);
        try {
            StageResult<PersonalityOutput> result = runner.run(llm, prompts.load("s3-personality"),
                    userMessage(brief, position, note), mainModel, PersonalityOutput.class);
            double cost = budget.cost(result.model(), result.tokensIn(), result.tokensOut());
            if (result.value() != null) {
                return new Generation(withAvoid(result.value()), false, result.model(),
                        result.promptVersion(), result.rawResponse(), result.tokensIn(),
                        result.tokensOut(), result.latencyMs(), cost);
            }
            log.warn("S3 degraded to template: {}", result.error());
            return templateGeneration(brief, cost, result.model(), result.latencyMs());
        } catch (LlmUnavailableException e) {
            log.warn("S3 degraded to template: {}", e.getMessage());
            return templateGeneration(brief);
        }
    }

    private String userMessage(BriefState brief, Map<String, Object> position, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("<data brief>\n");
        sb.append("idea: ").append(brief.getIdea() == null ? "" : brief.getIdea()).append('\n');
        for (BriefFieldId id : BriefFieldId.values()) {
            BriefField field = brief.field(id);
            if (field.value() != null && !field.value().isBlank()) {
                sb.append(id.id()).append(": ").append(field.value()).append('\n');
            }
        }
        sb.append("</data>\n<data position>\n");
        position.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        sb.append("</data>");
        if (note != null && !note.isBlank()) {
            sb.append("\n<data regenerate_note>\n").append(note.strip()).append("\n</data>");
        }
        return sb.toString();
    }

    private Generation templateGeneration(BriefState brief) {
        return templateGeneration(brief, 0, "template", 0);
    }

    private Generation templateGeneration(BriefState brief, double cost, String model, long latencyMs) {
        return new Generation(template(brief), true, model, null, null, 0, 0, latencyMs, cost);
    }

    private PersonalityOutput template(BriefState brief) {
        String audience = quote(brief, BriefFieldId.TARGET_USER, "people who need this");
        String problem = quote(brief, BriefFieldId.PROBLEM_ALTERNATIVE, "the problem in the brief");
        String tone = quote(brief, BriefFieldId.TONE_HINTS, "the brief");
        List<Trait> traits = List.of(
                new Trait("Direct",
                        "Brief says " + problem + " — clarity beats flourish for this audience.",
                        "Lead with the concrete outcome; no throat-clearing openers.",
                        "Cruel"),
                new Trait("Useful",
                        "Brief targets " + audience + " who are stuck in a real moment.",
                        "Give the next step before asking for anything.",
                        "Preachy"),
                new Trait("Human",
                        "Brief notes " + tone + " — stiff brand voice loses them.",
                        "Write like one person talking to another.",
                        "Try-hard"));
        VoiceSpec voice = new VoiceSpec(3, new int[] {6, 16}, "dry",
                List.of("revolutionize", "game-changer", "synergy", "leverage", "seamless"),
                List.of("Open with the problem, not the brand",
                        "One concrete number or detail per claim",
                        "Close with a single clear ask"));
        return withAvoid(new PersonalityOutput(traits, voice, List.of()));
    }

    private String quote(BriefState brief, BriefFieldId id, String fallback) {
        String value = brief.field(id).value();
        if (value == null || value.isBlank()) {
            value = brief.getIdea() != null && !brief.getIdea().isBlank() ? brief.getIdea() : fallback;
        }
        return "\"" + value.strip() + "\"";
    }

    private boolean hasPosition(Map<String, Object> dna) {
        Object position = dna.get("position");
        if (position == null) {
            return false;
        }
        if (position instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        if (position instanceof String text) {
            return !text.isBlank();
        }
        return true;
    }

    private PersonalityOutput withAvoid(PersonalityOutput output) {
        if (output.avoidList() != null && !output.avoidList().isEmpty()) {
            return output;
        }
        List<String> avoid = output.traits().stream()
                .map(Trait::neverBecome)
                .filter(v -> v != null && !v.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
        return new PersonalityOutput(output.traits(), output.voice(), avoid);
    }

    private void validate(PersonalityOutput output) {
        Set<ConstraintViolation<PersonalityOutput>> violations = validator.validate(output);
        if (!violations.isEmpty()) {
            String error = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("invalid personality");
            throw new IllegalStateException("Personality output failed validation: " + error);
        }
        List<String> avoid = output.avoidList();
        if (avoid == null || avoid.isEmpty()) {
            throw new IllegalStateException("Personality output failed validation: avoidList empty");
        }
        int[] range = output.voice().sentenceWords();
        if (range[0] < 1 || range[0] > range[1]) {
            throw new IllegalStateException("Personality output failed validation: sentenceWords order");
        }
    }

    public record RunResult(PersonalityOutput output,
                            long latencyMs,
                            boolean degraded,
                            String model,
                            String promptVersion) {
    }

    private record Generation(PersonalityOutput output,
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
