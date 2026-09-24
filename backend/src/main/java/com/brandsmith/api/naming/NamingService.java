package com.brandsmith.api.naming;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.det.AntiGenericScore;
import com.brandsmith.api.det.LexiconScorer;
import com.brandsmith.api.det.Pronounceability;
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
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class NamingService {

    public static final String STAGE = "S4";
    public static final String NO_PERSONALITY_MESSAGE =
            "Run personality before naming.";
    public static final String LOCKED_MESSAGE =
            "Naming is locked. Unlock it before regenerating.";
    public static final String NO_NAMES_MESSAGE =
            "Run naming before selecting a name.";
    public static final String DOMAIN_DISCLAIMER =
            "Domain signal (RDAP) is skipped in this build. Not a trademark or domain clearance tool.";

    static final double LOOP_THRESHOLD = 70;
    static final double MIN_IMPROVEMENT = 3;
    static final int MAX_ROUNDS = 3;

    private static final Logger log = LoggerFactory.getLogger(NamingService.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final SetOfWords STOP = SetOfWords.of(
            "about", "after", "again", "also", "and", "are", "because", "been", "before", "being",
            "between", "both", "but", "can", "could", "did", "does", "for", "from", "had", "has",
            "have", "her", "here", "him", "his", "how", "into", "its", "just", "more", "most",
            "not", "now", "our", "out", "over", "said", "same", "she", "should", "some", "such",
            "than", "that", "the", "their", "them", "then", "there", "these", "they", "this",
            "those", "through", "under", "very", "was", "were", "what", "when", "where", "which",
            "while", "who", "will", "with", "would", "your", "yours");

    private final SessionService sessions;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final Validator validator;
    private final EmbeddingIndex embeddings;
    private final LexiconScorer lexicon = new LexiconScorer();
    private final String mainModel;
    private final String smallModel;

    public NamingService(SessionService sessions,
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
        this.mainModel = mainModel;
        this.smallModel = smallModel;
    }

    public RunResult run(UUID id, String token, String note) {
        SessionService.StageSnapshot snapshot = sessions.loadStage(id, token);
        Map<String, Object> dna = snapshot.brandDna();
        if (!hasPersonality(dna)) {
            throw new ResponseStatusException(CONFLICT, NO_PERSONALITY_MESSAGE);
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
        @SuppressWarnings("unchecked")
        Map<String, Object> personality = dna.get("personality") instanceof Map
                ? (Map<String, Object>) dna.get("personality")
                : Map.of();

        Generation gen = generate(brief, position, personality, note, snapshot.spentUsd(), snapshot.capUsd());
        NamingOutput output = gen.output();
        validate(output);

        Map<String, Object> nextDna = new LinkedHashMap<>(dna);
        nextDna.put("naming", mapper.convertValue(output, MAP_TYPE));
        clearSelectedName(nextDna);
        sessions.saveBrandDna(id, nextDna, gen.costUsd());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("brief", snapshot.brief());
        input.put("position", position);
        input.put("personality", personality);
        if (note != null && !note.isBlank()) {
            input.put("note", note.strip());
        }
        sessions.recordStageRun(new SessionService.StageRunRecord(
                id, STAGE, input, output, gen.rawResponse(), gen.model(), gen.promptVersion(),
                gen.latencyMs(), gen.tokensIn(), gen.tokensOut(), gen.degraded()));

        return new RunResult(output, gen.latencyMs(), gen.degraded(), gen.model(), gen.promptVersion());
    }

    public Map<String, Object> select(UUID id, String token, SelectRequest request) {
        if (request == null || (request.nameIndex() == null
                && (request.name() == null || request.name().isBlank()))) {
            throw new ResponseStatusException(BAD_REQUEST, "nameIndex or name is required");
        }
        Map<String, Object> dna = sessions.loadBrandDna(id, token);
        List<Object> names = namingNames(dna);
        if (names == null || names.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, NO_NAMES_MESSAGE);
        }

        int index;
        if (request.nameIndex() != null) {
            index = request.nameIndex();
            if (index < 0 || index >= names.size()) {
                throw new ResponseStatusException(BAD_REQUEST, "nameIndex out of range");
            }
        } else {
            index = -1;
            String wanted = request.name().strip().toLowerCase(Locale.ROOT);
            for (int i = 0; i < names.size(); i++) {
                Object raw = names.get(i);
                if (raw instanceof Map<?, ?> map && map.get("name") instanceof String n
                        && n.strip().toLowerCase(Locale.ROOT).equals(wanted)) {
                    index = i;
                    break;
                }
            }
            if (index < 0) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown name: " + request.name());
            }
        }

        BrandName selected = mapper.convertValue(names.get(index), BrandName.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> identity = dna.get("identity") instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) dna.get("identity"))
                : new LinkedHashMap<>();
        identity.put("name", selected.name());
        dna.put("identity", identity);
        sessions.saveBrandDna(id, dna, 0);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("name", selected.name());
        response.put("nameIndex", index);
        response.put("territory", selected.territory());
        return response;
    }

    private Generation generate(BriefState brief, Map<String, Object> position,
                                Map<String, Object> personality, String note,
                                double spentUsd, double capUsd) {
        if (!llm.available()) {
            return templateGeneration(brief, position, personality);
        }
        budget.ensureWithinCap(spentUsd, capUsd);
        CostLedger cost = new CostLedger(budget);
        try {
            StageResult<NamingRaw> result = runner.run(llm, prompts.load("s4-naming"),
                    userMessage(brief, position, personality, note), mainModel, NamingRaw.class);
            cost.add(result.model(), result.tokensIn(), result.tokensOut());

            Prepared prepared;
            boolean degraded = false;
            if (result.value() == null) {
                log.warn("S4 degraded to template: {}", result.error());
                degraded = true;
                prepared = templatePrepared(brief, position, personality);
                scoreLocalOnly(prepared);
            } else {
                prepared = fromRaw(result.value());
                for (ScoredWork work : prepared.works()) {
                    scoreWithCritic(work, spentUsd, capUsd, cost);
                }
                for (ScoredWork work : prepared.works()) {
                    if (work.anti < LOOP_THRESHOLD) {
                        antiGenericLoop(work, spentUsd, capUsd, cost);
                    }
                }
            }
            NamingOutput output = build(prepared);
            return new Generation(output, degraded, result.model(), result.promptVersion(),
                    result.rawResponse(), result.tokensIn(), result.tokensOut(),
                    result.latencyMs(), cost.usd());
        } catch (LlmUnavailableException e) {
            log.warn("S4 degraded to template: {}", e.getMessage());
            return templateGeneration(brief, position, personality);
        }
    }

    private void scoreWithCritic(ScoredWork work, double spentUsd, double capUsd, CostLedger cost) {
        scoreLocal(work);
        work.critic = criticScore(work.name, work.issues, spentUsd + cost.usd(), capUsd, cost);
        work.anti = AntiGenericScore.combine(work.lex, work.emb, work.critic);
        work.attempts.add(new NameAttempt(work.name, work.anti, work.critic));
    }

    private void scoreLocal(ScoredWork work) {
        work.pron = Pronounceability.score(work.name);
        work.lex = lexicon.score(work.name);
        work.emb = EmbeddingScore.forText(embeddings, work.name);
    }

    private void scoreLocalOnly(Prepared prepared) {
        for (ScoredWork work : prepared.works()) {
            scoreLocal(work);
            work.critic = offlineCritic();
            work.anti = AntiGenericScore.combine(work.lex, work.emb, work.critic);
            work.attempts.add(new NameAttempt(work.name, work.anti, work.critic));
        }
    }

    private void antiGenericLoop(ScoredWork work, double spentUsd, double capUsd, CostLedger cost) {
        double prev = work.anti;
        for (int round = 1; round <= MAX_ROUNDS; round++) {
            if (work.anti >= LOOP_THRESHOLD) {
                break;
            }
            List<String> candidates = rewrite(work, spentUsd + cost.usd(), capUsd, cost);
            if (candidates == null || candidates.isEmpty()) {
                break;
            }
            ScoredWork best = null;
            for (String candidate : candidates) {
                ScoredWork cand = new ScoredWork(candidate, work.territory, work.rationale);
                scoreWithCritic(cand, spentUsd, capUsd, cost);
                if (best == null || cand.anti > best.anti) {
                    best = cand;
                }
            }
            if (best == null) {
                break;
            }
            double improvement = best.anti - prev;
            if (best.anti > work.anti) {
                work.name = best.name;
                work.pron = best.pron;
                work.lex = best.lex;
                work.emb = best.emb;
                work.critic = best.critic;
                work.anti = best.anti;
                work.issues.clear();
                work.issues.addAll(best.issues);
                work.attempts.add(new NameAttempt(best.name, best.anti, best.critic));
            }
            if (work.anti >= LOOP_THRESHOLD || improvement < MIN_IMPROVEMENT) {
                break;
            }
            prev = work.anti;
        }
    }

    private double criticScore(String name, List<String> issues, double spentUsd, double capUsd,
                               CostLedger cost) {
        budget.ensureWithinCap(spentUsd, capUsd);
        try {
            StageResult<CriticRaw> result = runner.run(llm, prompts.load("anti-generic-critic"),
                    criticUserMessage(name), smallModel, CriticRaw.class);
            cost.add(result.model(), result.tokensIn(), result.tokensOut());
            if (result.value() == null) {
                log.warn("S4 critic degraded to offline score for {}", name);
                return offlineCritic();
            }
            if (result.value().quotes() != null) {
                for (CriticQuote quote : result.value().quotes()) {
                    if (quote != null && quote.phrase() != null && quote.issue() != null) {
                        issues.add(quote.phrase() + ": " + quote.issue());
                    }
                }
            }
            return result.value().criticScore();
        } catch (LlmUnavailableException e) {
            log.warn("S4 critic unavailable, using offline score: {}", e.getMessage());
            return offlineCritic();
        }
    }

    private List<String> rewrite(ScoredWork work, double spentUsd, double capUsd, CostLedger cost) {
        budget.ensureWithinCap(spentUsd, capUsd);
        try {
            StageResult<RewriteRaw> result = runner.run(llm, prompts.load("anti-generic-rewrite"),
                    rewriteUserMessage(work), smallModel, RewriteRaw.class);
            cost.add(result.model(), result.tokensIn(), result.tokensOut());
            if (result.value() == null) {
                log.warn("S4 rewrite degraded for {}", work.name);
                return null;
            }
            return result.value().candidates();
        } catch (LlmUnavailableException e) {
            log.warn("S4 rewrite unavailable: {}", e.getMessage());
            return null;
        }
    }

    private static double offlineCritic() {
        return 70;
    }

    private String userMessage(BriefState brief, Map<String, Object> position,
                               Map<String, Object> personality, String note) {
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
        sb.append("</data>\n<data personality>\n");
        personality.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        sb.append("</data>");
        if (note != null && !note.isBlank()) {
            sb.append("\n<data regenerate_note>\n").append(note.strip()).append("\n</data>");
        }
        return sb.toString();
    }

    private String criticUserMessage(String name) {
        return "<data candidate>\nname: " + name + "\n</data>";
    }

    private String rewriteUserMessage(ScoredWork work) {
        StringBuilder sb = new StringBuilder();
        sb.append("<data candidate>\n");
        sb.append("name: ").append(work.name).append('\n');
        sb.append("territory: ").append(work.territory).append('\n');
        sb.append("rationale: ").append(work.rationale).append('\n');
        sb.append("antiGenericScore: ").append(work.anti).append('\n');
        sb.append("</data>\n<data critic_issues>\n");
        if (work.issues.isEmpty()) {
            sb.append("raise specificity, ownability, surprise and audience fit\n");
        } else {
            for (String issue : work.issues) {
                sb.append(issue).append('\n');
            }
        }
        sb.append("</data>\n<data previous_attempts>\n");
        for (NameAttempt attempt : work.attempts) {
            sb.append(attempt.name()).append(" (").append(attempt.antiGenericScore()).append(")\n");
        }
        sb.append("</data>\n<data banned_patterns>\n");
        List<String> hits = lexicon.hits(work.name);
        if (hits.isEmpty()) {
            sb.append("no lexicon hits on current name; still avoid empower/unlock/seamless and -ify/-ly\n");
        } else {
            sb.append(String.join(", ", hits)).append('\n');
        }
        sb.append("</data>");
        return sb.toString();
    }

    private Generation templateGeneration(BriefState brief, Map<String, Object> position,
                                          Map<String, Object> personality) {
        Prepared prepared = templatePrepared(brief, position, personality);
        scoreLocalOnly(prepared);
        NamingOutput output = build(prepared);
        return new Generation(output, true, "template", null, null, 0, 0, 0, 0);
    }

    private Prepared templatePrepared(BriefState brief, Map<String, Object> position,
                                      Map<String, Object> personality) {
        List<String> words = contentWords(brief);
        String a = words.get(0);
        String b = words.size() > 1 ? words.get(1) : a;
        String c = words.size() > 2 ? words.get(2) : b;
        String trait = firstTrait(personality);

        Territory descriptive = new Territory(
                "descriptive-evocative",
                "Says what the brand is for using brief tokens \"" + a + "\" and \"" + b + "\".",
                "Matches a " + trait + " personality: literal, no metaphor to decode.");
        Territory invented = new Territory(
                "invented",
                "Coined forms from \"" + a + "\", \"" + b + "\" and \"" + c + "\" with no cliche suffixes.",
                "Gives the " + trait + " brand an ownable word competitors cannot already own.");
        Territory metaphor = new Territory(
                "metaphor",
                "Harbor/North/Ember images paired with brief tokens, offline without an API key.",
                "Adds warmth for a " + trait + " personality while staying pronounceable.");

        List<BrandName> names = new ArrayList<>();
        addTemplate(names, descriptive, cap(a) + cap(b),
                "Joins brief tokens \"" + a + "\" + \"" + b + "\".");
        addTemplate(names, descriptive, cap(a) + "Base",
                "Literal " + a + " platform named from the brief.");
        addTemplate(names, descriptive, cap(b) + "Desk",
                "Puts the audience word \"" + b + "\" on a work surface.");
        addTemplate(names, invented, clip(a, 5) + "ora",
                "Coinage: " + a + " stretched with an open vowel ending.");
        addTemplate(names, invented, clip(b, 4) + "lio",
                "Coinage: " + b + " clipped and closed with -lio (not -ify).");
        addTemplate(names, invented, clip(c, 4) + "umi",
                "Coinage: " + c + " clipped to a soft three-syllable word.");
        addTemplate(names, metaphor, "Harbor" + cap(a),
                "Harbor metaphor for safe " + a + " support.");
        addTemplate(names, metaphor, "North" + cap(b),
                "North-star direction without the banned phrase.");
        addTemplate(names, metaphor, "Ember" + cap(c),
                "Ember metaphor: small heat that keeps " + c + " going.");

        return new Prepared(List.of(descriptive, invented, metaphor), worksOf(names));
    }

    private void addTemplate(List<BrandName> names, Territory territory, String name, String rationale) {
        names.add(new BrandName(name, territory.name(), rationale, name.length(),
                0, 0, 0, 0, 0, null, List.of()));
    }

    private List<String> contentWords(BriefState brief) {
        String text = brief.getCleanIdea();
        if (text == null || text.isBlank()) {
            text = brief.getIdea();
        }
        if (text == null || text.isBlank()) {
            text = "brand kit";
        }
        List<String> words = new ArrayList<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
            if (token.length() >= 4 && !STOP.contains(token) && !words.contains(token)) {
                words.add(token);
            }
            if (words.size() == 3) {
                break;
            }
        }
        if (words.isEmpty()) {
            words.add("brand");
        }
        return words;
    }

    private String firstTrait(Map<String, Object> personality) {
        Object traits = personality.get("traits");
        if (traits instanceof List<?> list && !list.isEmpty()
                && list.get(0) instanceof Map<?, ?> map && map.get("name") instanceof String name) {
            return name;
        }
        return "focused";
    }

    private static String cap(String word) {
        if (word == null || word.isEmpty()) {
            return "";
        }
        return Character.toUpperCase(word.charAt(0)) + word.substring(1);
    }

    private static String clip(String word, int max) {
        if (word == null || word.isEmpty()) {
            return "x";
        }
        return word.length() <= max ? word : word.substring(0, max);
    }

    private Prepared fromRaw(NamingRaw raw) {
        List<Territory> territories = new ArrayList<>();
        List<BrandName> names = new ArrayList<>();
        for (TerritoryRaw territory : raw.territories()) {
            territories.add(new Territory(
                    territory.type().strip(),
                    "Three names exploring the " + territory.type().strip() + " space.",
                    territory.whyFits().strip()));
            for (NameRaw name : territory.names()) {
                names.add(new BrandName(
                        name.name().strip(),
                        territory.type().strip(),
                        name.rationale().strip(),
                        name.name().strip().length(),
                        0, 0, 0, 0, 0, null, List.of()));
            }
        }
        return new Prepared(territories, worksOf(names));
    }

    private List<ScoredWork> worksOf(List<BrandName> names) {
        List<ScoredWork> works = new ArrayList<>(names.size());
        for (BrandName name : names) {
            works.add(new ScoredWork(name.name(), name.territory(), name.rationale()));
        }
        return works;
    }

    private NamingOutput build(Prepared prepared) {
        List<BrandName> names = new ArrayList<>(prepared.works().size());
        for (ScoredWork work : prepared.works()) {
            names.add(new BrandName(
                    work.name, work.territory, work.rationale, work.name.length(),
                    work.pron, work.lex, work.emb, work.critic, work.anti,
                    null, List.copyOf(work.attempts)));
        }
        return new NamingOutput(prepared.territories(), names, DOMAIN_DISCLAIMER);
    }

    private void validate(NamingOutput output) {
        Set<ConstraintViolation<NamingOutput>> violations = validator.validate(output);
        if (!violations.isEmpty()) {
            String error = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .reduce((a, b) -> a + "; " + b)
                    .orElse("invalid naming output");
            throw new IllegalStateException("Naming output failed validation: " + error);
        }
    }

    private boolean hasPersonality(Map<String, Object> dna) {
        Object personality = dna.get("personality");
        if (personality instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Object> namingNames(Map<String, Object> dna) {
        Object naming = dna.get("naming");
        if (!(naming instanceof Map<?, ?> map)) {
            return null;
        }
        Object names = map.get("names");
        return names instanceof List<?> list ? (List<Object>) list : null;
    }

    @SuppressWarnings("unchecked")
    private void clearSelectedName(Map<String, Object> dna) {
        if (!(dna.get("identity") instanceof Map)) {
            return;
        }
        Map<String, Object> identity = new LinkedHashMap<>((Map<String, Object>) dna.get("identity"));
        identity.remove("name");
        if (identity.isEmpty()) {
            dna.remove("identity");
        } else {
            dna.put("identity", identity);
        }
    }

    private record Prepared(List<Territory> territories, List<ScoredWork> works) {
    }

    private static final class ScoredWork {
        private String name;
        private final String territory;
        private final String rationale;
        private int pron;
        private int lex;
        private int emb;
        private double critic;
        private double anti;
        private final List<String> issues = new ArrayList<>();
        private final List<NameAttempt> attempts = new ArrayList<>();

        private ScoredWork(String name, String territory, String rationale) {
            this.name = name;
            this.territory = territory;
            this.rationale = rationale;
        }
    }

    private final class CostLedger {
        private final BudgetGuard guard;
        private double usd;

        private CostLedger(BudgetGuard guard) {
            this.guard = guard;
        }

        private void add(String model, int tokensIn, int tokensOut) {
            usd += guard.cost(model, tokensIn, tokensOut);
        }

        private double usd() {
            return usd;
        }
    }

    public record RunResult(NamingOutput output,
                            long latencyMs,
                            boolean degraded,
                            String model,
                            String promptVersion) {
    }

    private record Generation(NamingOutput output,
                              boolean degraded,
                              String model,
                              String promptVersion,
                              String rawResponse,
                              int tokensIn,
                              int tokensOut,
                              long latencyMs,
                              double costUsd) {
    }

    public record NamingRaw(@JsonProperty("territories") @NotNull @Valid @Size(min = 3, max = 3)
                            List<TerritoryRaw> territories) {
    }

    public record TerritoryRaw(@JsonProperty("type") @NotBlank String type,
                               @JsonProperty("whyFits") @NotBlank String whyFits,
                               @JsonProperty("names") @NotNull @Valid @Size(min = 3, max = 3)
                               List<NameRaw> names) {
    }

    public record NameRaw(@JsonProperty("name") @NotBlank String name,
                          @JsonProperty("rationale") @NotBlank String rationale,
                          @JsonProperty("specificity") String specificity) {
    }

    public record CriticRaw(@JsonProperty("specificity") Integer specificity,
                            @JsonProperty("ownability") Integer ownability,
                            @JsonProperty("surprise") Integer surprise,
                            @JsonProperty("audienceFit") Integer audienceFit,
                            @JsonProperty("critic_score") @NotNull @Min(0) @Max(100)
                            Integer criticScore,
                            @JsonProperty("quotes") List<CriticQuote> quotes) {
    }

    public record CriticQuote(@JsonProperty("phrase") String phrase,
                              @JsonProperty("issue") String issue) {
    }

    public record RewriteRaw(@JsonProperty("candidates") @NotNull @Size(min = 1, max = 5)
                             List<@NotBlank String> candidates) {
    }

    private static final class SetOfWords {
        private final java.util.Set<String> words;

        private SetOfWords(java.util.Set<String> words) {
            this.words = words;
        }

        private static SetOfWords of(String... values) {
            return new SetOfWords(java.util.Set.of(values));
        }

        private boolean contains(String word) {
            return words.contains(word);
        }
    }
}
