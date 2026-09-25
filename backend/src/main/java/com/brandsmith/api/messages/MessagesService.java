package com.brandsmith.api.messages;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.det.AntiGenericScore;
import com.brandsmith.api.det.LexiconScorer;
import com.brandsmith.api.embedding.EmbeddingIndex;
import com.brandsmith.api.embedding.EmbeddingScore;
import com.brandsmith.api.interview.BriefField;
import com.brandsmith.api.interview.BriefFieldId;
import com.brandsmith.api.interview.BriefState;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.llm.LlmUnavailableException;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageResult;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class MessagesService {

    public static final String STAGE = "S5";
    public static final String NO_NAME_MESSAGE = "Choose a name before running messages.";
    public static final String NO_MESSAGES_MESSAGE = "Run messages before selecting a tagline.";
    public static final String LOCKED_MESSAGE =
            "Messages are locked. Unlock it before regenerating.";

    static final double ACCEPT_SCORE = 70;
    static final int MAX_REWRITES = 3;
    static final double MIN_DELTA = 3;

    private static final Logger log = LoggerFactory.getLogger(MessagesService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final SessionService sessions;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final EmbeddingIndex embeddings;
    private final LexiconScorer lexicon;
    private final String mainModel;
    private final String smallModel;

    public MessagesService(SessionService sessions,
                           LlmClient llm,
                           PromptLoader prompts,
                           StageRunner runner,
                           BudgetGuard budget,
                           ObjectMapper mapper,
                           Validator validator,
                           EmbeddingIndex embeddings,
                           @Value("${brandsmith.llm.main-model}") String mainModel,
                           @Value("${brandsmith.llm.small-model}") String smallModel) {
        this.sessions = sessions;
        this.llm = llm;
        this.prompts = prompts;
        this.runner = runner;
        this.budget = budget;
        this.mapper = mapper;
        this.validator = validator;
        this.embeddings = embeddings;
        this.lexicon = new LexiconScorer();
        this.mainModel = mainModel;
        this.smallModel = smallModel;
    }

    public RunResult run(UUID id, String token, String note) {
        return run(id, token, note, event -> {
        });
    }

    public RunResult run(UUID id, String token, String note, Consumer<SseEvent> sink) {
        SessionService.StageSnapshot snapshot = sessions.loadStage(id, token);
        Map<String, Object> dna = snapshot.brandDna();
        String name = selectedName(dna);
        if (name == null) {
            throw new ResponseStatusException(CONFLICT, NO_NAME_MESSAGE);
        }
        if (sessions.stageLocked(id, STAGE)) {
            throw new ResponseStatusException(CONFLICT, LOCKED_MESSAGE);
        }
        if (note != null && note.length() > 300) {
            throw new ResponseStatusException(BAD_REQUEST, "note must be 300 characters or fewer");
        }

        BriefState brief = mapper.convertValue(snapshot.brief(), BriefState.class);
        brief.ensureFields();

        Generation gen = generate(brief, dna, name, note, snapshot.spentUsd(), snapshot.capUsd(), sink);
        MessagesOutput output = gen.output();
        validate(output);

        Map<String, Object> nextDna = new LinkedHashMap<>(dna);
        Map<String, Object> messages = new LinkedHashMap<>();
        messages.put("taglines", output.taglines());
        messages.put("pitch", output.pitch());
        messages.put("hierarchy", output.hierarchy());
        messages.put("selected", null);
        nextDna.put("messages", messages);
        sessions.saveBrandDna(id, nextDna, gen.costUsd());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("brief", snapshot.brief());
        input.put("name", name);
        if (note != null && !note.isBlank()) {
            input.put("note", note.strip());
        }
        sessions.recordStageRun(new SessionService.StageRunRecord(
                id, STAGE, input, output, gen.rawResponse(), gen.model(), gen.promptVersion(),
                gen.latencyMs(), gen.tokensIn(), gen.tokensOut(), gen.degraded()));

        return new RunResult(output, gen.latencyMs(), gen.degraded(), gen.model(), gen.promptVersion());
    }

    public Map<String, Object> select(UUID id, String token, SelectRequest request) {
        if (request == null || request.index() == null || request.index() < 0) {
            throw new ResponseStatusException(BAD_REQUEST, "index must be a non-negative integer");
        }
        Map<String, Object> dna = sessions.loadBrandDna(id, token);
        Map<String, Object> messages = asMap(dna.get("messages"));
        List<Object> taglines = asList(messages.get("taglines"));
        if (taglines == null || taglines.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, NO_MESSAGES_MESSAGE);
        }
        int index = request.index();
        if (index >= taglines.size()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "index must be 0.." + (taglines.size() - 1));
        }
        Map<String, Object> chosen = mapper.convertValue(taglines.get(index), MAP_TYPE);
        String text = chosen.get("text") instanceof String s ? s.strip() : null;
        if (text == null || text.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid tagline at index " + index);
        }
        String pitch = messages.get("pitch") instanceof String p ? p : "";

        Map<String, Object> identity = new LinkedHashMap<>(asMap(dna.get("identity")));
        identity.put("tagline", text);
        identity.put("pitch", pitch);
        messages.put("selected", index);
        dna.put("identity", identity);
        dna.put("messages", messages);
        sessions.saveBrandDna(id, dna, 0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("selected", index);
        response.put("tagline", text);
        response.put("pitch", pitch);
        response.put("messages", messages);
        return response;
    }

    private Generation generate(BriefState brief, Map<String, Object> dna, String name, String note,
                                double spentUsd, double capUsd, Consumer<SseEvent> sink) {
        sink.accept(new SseEvent("progress",
                Map.of("message", "Scoring taglines and building hierarchy...")));
        if (!llm.available()) {
            return templateGeneration(brief, name);
        }
        budget.ensureWithinCap(spentUsd, capUsd);
        Cost cost = new Cost(budget);
        try {
            StageResult<Draft> draftResult = runner.run(llm, prompts.load("s5-messages"),
                    draftUserMessage(brief, dna, name, note), mainModel, Draft.class);
            cost.add(draftResult.model(), draftResult);
            if (draftResult.value() == null) {
                log.warn("S5 degraded to template: {}", draftResult.error());
                return templateGeneration(brief, name, cost, draftResult.model(),
                        draftResult.latencyMs());
            }
            Draft draft = draftResult.value();
            List<String> banned = bannedWords(dna);
            List<TaglineOption> taglines = new ArrayList<>(draft.taglines().size());
            for (String raw : draft.taglines()) {
                taglines.add(polish(raw, brief, name, banned, cost));
            }
            MessagesOutput output = new MessagesOutput(taglines, draft.pitchOneLine(),
                    fromDraftHierarchy(draft.hierarchy()));
            validate(output);
            return new Generation(output, false, draftResult.model(), draftResult.promptVersion(),
                    draftResult.rawResponse(), cost.tokensIn, cost.tokensOut,
                    draftResult.latencyMs() + cost.extraLatencyMs, cost.usd());
        } catch (LlmUnavailableException e) {
            log.warn("S5 degraded to template: {}", e.getMessage());
            return templateGeneration(brief, name, cost, smallModel, 0);
        }
    }

    private TaglineOption polish(String raw, BriefState brief, String name, List<String> banned,
                                 Cost cost) {
        String text = raw.strip();
        List<TaglineAttempt> attempts = new ArrayList<>();
        double score = scoreFull(text, banned, cost);
        double bestScore = score;
        String bestText = text;
        attempts.add(new TaglineAttempt(text, score, 0));

        int rewrites = 0;
        while (score < ACCEPT_SCORE && rewrites < MAX_REWRITES) {
            String next = rewrite(bestText, brief, name, banned, attempts, cost);
            if (next == null || next.isBlank() || next.equals(bestText)) {
                break;
            }
            double nextScore = scoreFull(next, banned, cost);
            double delta = Math.round((nextScore - score) * 10.0) / 10.0;
            attempts.add(new TaglineAttempt(next, nextScore, delta));
            rewrites++;
            score = nextScore;
            if (nextScore > bestScore) {
                bestScore = nextScore;
                bestText = next;
            }
            if (delta < MIN_DELTA) {
                break;
            }
        }
        return new TaglineOption(bestText, bestScore, attempts);
    }

    private double scoreFull(String text, List<String> banned, Cost cost) {
        int lex = lexicon.score(text);
        int emb = EmbeddingScore.forText(embeddings, text);
        double critic = criticScore(text, banned, cost, lex, emb);
        return AntiGenericScore.combine(lex, emb, critic);
    }

    private double criticScore(String text, List<String> banned, Cost cost, int lex, int emb) {
        if (!llm.available()) {
            return offlineCritic(lex, emb);
        }
        try {
            StageResult<Critic> result = runner.run(llm, prompts.load("anti-generic-critic"),
                    criticUserMessage(text, banned), smallModel, Critic.class);
            cost.add(result.model(), result);
            if (result.value() != null) {
                return result.value().criticScore();
            }
            log.warn("S5 critic degraded offline: {}", result.error());
            return offlineCritic(lex, emb);
        } catch (LlmUnavailableException e) {
            log.warn("S5 critic degraded offline: {}", e.getMessage());
            return offlineCritic(lex, emb);
        }
    }

    private static double offlineCritic(int lex, int emb) {
        return Math.round((lex + emb) / 2.0);
    }

    private String rewrite(String current, BriefState brief, String name, List<String> banned,
                           List<TaglineAttempt> attempts, Cost cost) {
        if (!llm.available()) {
            return null;
        }
        List<String> lexiconHits = lexicon.hits(current);
        try {
            StageResult<Rewrite> result = runner.run(llm, prompts.load("anti-generic-rewrite"),
                    rewriteUserMessage(current, brief, name, banned, lexiconHits, attempts),
                    smallModel, Rewrite.class);
            cost.add(result.model(), result);
            if (result.value() == null || result.value().candidates() == null
                    || result.value().candidates().isEmpty()) {
                log.warn("S5 rewrite degraded: {}", result.error());
                return null;
            }
            return pickCandidate(result.value().candidates(), attempts);
        } catch (LlmUnavailableException e) {
            log.warn("S5 rewrite degraded: {}", e.getMessage());
            return null;
        }
    }

    /**
     * ponytail: rank rewrite candidates by lexicon+embedding only (no critic LLM per candidate);
     * upgrade path = full critic score per candidate when budget allows.
     */
    private String pickCandidate(List<String> candidates, List<TaglineAttempt> attempts) {
        return candidates.stream()
                .filter(c -> c != null && !c.isBlank())
                .map(String::strip)
                .filter(c -> attempts.stream().noneMatch(a -> a.text().equals(c)))
                .max((a, b) -> Double.compare(ranking(a), ranking(b)))
                .orElse(null);
    }

    private double ranking(String text) {
        return lexicon.score(text) + EmbeddingScore.forText(embeddings, text);
    }

    private MessagesOutput templateOutput(BriefState brief, String name) {
        String audience = value(brief, BriefFieldId.TARGET_USER, "the people who need it");
        String problem = value(brief, BriefFieldId.PROBLEM_ALTERNATIVE, "the old workaround");
        String outcome = value(brief, BriefFieldId.DESIRED_OUTCOME, "the result they want");
        String proof = value(brief, BriefFieldId.PROOF_ADVANTAGE, "Built from real user feedback");

        List<String> raws = List.of(
                name + " skips the " + shortNoun(problem),
                outcome + " without the " + shortNoun(problem),
                "Form the group before midterms");
        List<TaglineOption> taglines = new ArrayList<>(raws.size());
        for (String raw : raws) {
            taglines.add(scoredOffline(raw));
        }
        String pitch = name + " helps " + audience + " get " + outcome
                + " without fighting " + shortNoun(problem) + ".";
        MessageHierarchy hierarchy = new MessageHierarchy(
                name + " is for " + audience,
                List.of("Kills " + shortNoun(problem), "Delivers " + outcome),
                List.of(proof.strip()));
        return new MessagesOutput(taglines, pitch, hierarchy);
    }

    private TaglineOption scoredOffline(String text) {
        int lex = lexicon.score(text);
        int emb = EmbeddingScore.forText(embeddings, text);
        double score = AntiGenericScore.combine(lex, emb, offlineCritic(lex, emb));
        return new TaglineOption(text, score, List.of(new TaglineAttempt(text, score, 0)));
    }

    private String shortNoun(String phrase) {
        String cleaned = phrase.strip();
        if (cleaned.length() > 40) {
            cleaned = cleaned.substring(0, 40).strip() + "...";
        }
        return cleaned.toLowerCase();
    }

    private Generation templateGeneration(BriefState brief, String name) {
        return templateGeneration(brief, name, new Cost(budget), "template", 0);
    }

    private Generation templateGeneration(BriefState brief, String name, Cost cost,
                                          String model, long latencyMs) {
        MessagesOutput output = templateOutput(brief, name);
        validate(output);
        return new Generation(output, true, model, null, null,
                cost.tokensIn, cost.tokensOut, latencyMs, cost.usd());
    }

    private String draftUserMessage(BriefState brief, Map<String, Object> dna, String name,
                                    String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("<data brief>\n");
        sb.append("idea: ").append(nullSafe(brief.getIdea())).append('\n');
        for (BriefFieldId id : BriefFieldId.values()) {
            BriefField field = brief.field(id);
            if (field.value() != null && !field.value().isBlank()) {
                sb.append(id.id()).append(": ").append(field.value()).append('\n');
            }
        }
        sb.append("</data>\n<data name>\n").append(name).append("\n</data>\n");
        appendIfPresent(sb, "position", dna.get("position"));
        appendIfPresent(sb, "personality", dna.get("personality"));
        appendIfPresent(sb, "voice", dna.get("voice"));
        if (note != null && !note.isBlank()) {
            sb.append("<data regenerate_note>\n").append(note.strip()).append("\n</data>");
        }
        return sb.toString();
    }

    private void appendIfPresent(StringBuilder sb, String tag, Object value) {
        if (value == null) {
            return;
        }
        sb.append("<data ").append(tag).append(">\n").append(toJson(value)).append("\n</data>\n");
    }

    private String criticUserMessage(String text, List<String> banned) {
        return "<data candidate>\n" + text + "\n</data>\n"
                + "<data banned_patterns>\n" + String.join("\n", banned) + "\n</data>";
    }

    private String rewriteUserMessage(String current, BriefState brief, String name,
                                      List<String> banned, List<String> lexiconHits,
                                      List<TaglineAttempt> attempts) {
        StringBuilder sb = new StringBuilder();
        sb.append("<data original>\n").append(current).append("\n</data>\n");
        sb.append("<data brand>\nname: ").append(name)
                .append("\nidea: ").append(nullSafe(brief.getIdea())).append("\n</data>\n");
        sb.append("<data banned_patterns>\n");
        banned.forEach(b -> sb.append(b).append('\n'));
        lexiconHits.forEach(h -> sb.append("lexicon: ").append(h).append('\n'));
        sb.append("</data>\n<data previous_attempts>\n");
        for (TaglineAttempt attempt : attempts) {
            sb.append(attempt.text()).append('\n');
        }
        sb.append("</data>");
        return sb.toString();
    }

    private List<String> bannedWords(Map<String, Object> dna) {
        List<String> banned = new ArrayList<>();
        if (dna.get("voice") instanceof Map<?, ?> map
                && map.get("bannedWords") instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    banned.add(s.strip());
                }
            }
        }
        return banned;
    }

    private MessageHierarchy fromDraftHierarchy(Draft.Hierarchy hierarchy) {
        List<String> secondary = blankFiltered(hierarchy.secondary());
        List<String> proof = blankFiltered(hierarchy.tertiary());
        String primary = hierarchy.primary() == null ? "" : hierarchy.primary().strip();
        if (secondary.isEmpty()) {
            secondary = List.of(primary);
        }
        if (proof.isEmpty()) {
            proof = List.of(primary);
        }
        return new MessageHierarchy(primary, secondary, proof);
    }

    private static List<String> blankFiltered(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::strip)
                .toList();
    }

    private void validate(MessagesOutput output) {
        Set<ConstraintViolation<MessagesOutput>> violations = validator.validate(output);
        if (!violations.isEmpty()) {
            String error = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("invalid messages");
            throw new IllegalStateException("Messages output failed validation: " + error);
        }
    }

    private String selectedName(Map<String, Object> dna) {
        String identityName = textAt(asMap(dna.get("identity")), "name");
        if (identityName != null) {
            return identityName;
        }
        Map<String, Object> naming = asMap(dna.get("naming"));
        String namingName = textAt(naming, "name");
        if (namingName != null) {
            return namingName;
        }
        String selectedName = textAt(naming, "selectedName");
        if (selectedName != null) {
            return selectedName;
        }
        Object selected = naming.get("selected");
        if (selected instanceof String s && !s.isBlank()) {
            return s.strip();
        }
        if (selected instanceof Number n && naming.get("names") instanceof List<?> names) {
            int idx = n.intValue();
            if (idx >= 0 && idx < names.size()) {
                Object entry = names.get(idx);
                if (entry instanceof String s && !s.isBlank()) {
                    return s.strip();
                }
                if (entry instanceof Map<?, ?> m && m.get("name") instanceof String s && !s.isBlank()) {
                    return s.strip();
                }
            }
        }
        return null;
    }

    private static String textAt(Map<String, Object> map, String key) {
        return map.get(key) instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static List<Object> asList(Object value) {
        return value instanceof List ? (List<Object>) value : null;
    }

    private static String value(BriefState brief, BriefFieldId id, String fallback) {
        String v = brief.field(id).value();
        return v == null || v.isBlank() ? fallback : v.strip();
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    public record RunResult(MessagesOutput output,
                            long latencyMs,
                            boolean degraded,
                            String model,
                            String promptVersion) {
    }

    public record SseEvent(String name, Object data) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Draft(@JsonProperty("taglines") @NotNull @Size(min = 3, max = 6) List<String> taglines,
                 @JsonProperty("pitchOneLine") @NotBlank String pitchOneLine,
                 @JsonProperty("pitchParagraph") String pitchParagraph,
                 @JsonProperty("hierarchy") @NotNull @Valid Hierarchy hierarchy) {

        @JsonIgnoreProperties(ignoreUnknown = true)
        record Hierarchy(@JsonProperty("primary") @NotBlank String primary,
                         @JsonProperty("secondary") List<String> secondary,
                         @JsonProperty("tertiary") List<String> tertiary) {
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Critic(@JsonProperty("critic_score") double criticScore) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Rewrite(@JsonProperty("candidates") @NotNull @Size(min = 1, max = 5)
                   List<String> candidates) {
    }

    private record Generation(MessagesOutput output,
                              boolean degraded,
                              String model,
                              String promptVersion,
                              String rawResponse,
                              int tokensIn,
                              int tokensOut,
                              long latencyMs,
                              double costUsd) {
    }

    private static final class Cost {
        private final BudgetGuard budget;
        int tokensIn;
        int tokensOut;
        long extraLatencyMs;
        private double usd;

        Cost(BudgetGuard budget) {
            this.budget = budget;
        }

        void add(String model, StageResult<?> result) {
            tokensIn += result.tokensIn();
            tokensOut += result.tokensOut();
            extraLatencyMs += result.latencyMs();
            usd += budget.cost(model, result.tokensIn(), result.tokensOut());
        }

        double usd() {
            return usd;
        }
    }
}
