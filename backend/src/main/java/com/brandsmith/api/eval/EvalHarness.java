package com.brandsmith.api.eval;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.brandsmith.api.audit.AuditService;
import com.brandsmith.api.battle.BattleResult;
import com.brandsmith.api.battle.BattleService;
import com.brandsmith.api.battle.SelectRequest;
import com.brandsmith.api.det.AntiGenericScore;
import com.brandsmith.api.det.LexiconScorer;
import com.brandsmith.api.embedding.EmbeddingIndex;
import com.brandsmith.api.embedding.EmbeddingScore;
import com.brandsmith.api.interview.BriefFieldId;
import com.brandsmith.api.interview.PatchBriefRequest;
import com.brandsmith.api.interview.S1InterviewService;
import com.brandsmith.api.launch.LaunchService;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.llm.LlmUnavailableException;
import com.brandsmith.api.messages.MessagesService;
import com.brandsmith.api.naming.NamingService;
import com.brandsmith.api.personality.PersonalityService;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.share.KitMarkdown;
import com.brandsmith.api.stage.StageResult;
import com.brandsmith.api.stage.StageRunner;
import com.brandsmith.api.visual.VisualService;
import com.brandsmith.api.BrandsmithApiApplication;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * CLI eval harness (PRD 7.8): 10 fixtures, single-prompt baseline vs full pipeline, blind judge.
 * Offline (no ANTHROPIC_API_KEY): template kits + heuristic lexicon/structure judge so the table
 * still prints. With a key: real generation and eval-judge.md LLM scoring.
 */
@Component
public class EvalHarness {

    public static final String VARIANT_BASELINE = "baseline";
    public static final String VARIANT_BRANDSMITH = "brandsmith";

    private static final Logger log = LoggerFactory.getLogger(EvalHarness.class);
    private static final String DEFAULT_FIXTURES = "fixtures/ideas.json";

    private final SessionService sessions;
    private final S1InterviewService interview;
    private final BattleService battle;
    private final PersonalityService personality;
    private final NamingService naming;
    private final MessagesService messages;
    private final VisualService visual;
    private final AuditService audit;
    private final LaunchService launch;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final LexiconScorer lexicon;
    private final EmbeddingIndex embeddings;
    private final String mainModel;

    public EvalHarness(SessionService sessions,
                       S1InterviewService interview,
                       BattleService battle,
                       PersonalityService personality,
                       NamingService naming,
                       MessagesService messages,
                       VisualService visual,
                       AuditService audit,
                       LaunchService launch,
                       LlmClient llm,
                       PromptLoader prompts,
                       StageRunner runner,
                       ObjectMapper mapper,
                       JdbcTemplate jdbc,
                       EmbeddingIndex embeddings,
                       @org.springframework.beans.factory.annotation.Value("${brandsmith.llm.main-model}") String mainModel) {
        this.sessions = sessions;
        this.interview = interview;
        this.battle = battle;
        this.personality = personality;
        this.naming = naming;
        this.messages = messages;
        this.visual = visual;
        this.audit = audit;
        this.launch = launch;
        this.llm = llm;
        this.prompts = prompts;
        this.runner = runner;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.lexicon = new LexiconScorer();
        this.embeddings = embeddings;
        this.mainModel = mainModel;
    }

    public String run(int limit) {
        List<Fixture> fixtures = loadFixtures(limit);
        if (fixtures.isEmpty()) {
            throw new IllegalStateException("No fixtures loaded from " + fixturesPath());
        }
        List<Row> rows = new ArrayList<>();
        for (Fixture fixture : fixtures) {
            String baselineKit = generateBaseline(fixture);
            String brandsmithKit = generateBrandsmith(fixture);
            Scores baselineScores = judge(fixture, baselineKit);
            Scores brandsmithScores = judge(fixture, brandsmithKit);
            save(fixture.id(), VARIANT_BASELINE, baselineScores);
            save(fixture.id(), VARIANT_BRANDSMITH, brandsmithScores);
            rows.add(new Row(fixture.id(), VARIANT_BASELINE, baselineScores));
            rows.add(new Row(fixture.id(), VARIANT_BRANDSMITH, brandsmithScores));
        }
        return markdownTable(rows);
    }

    private String generateBaseline(Fixture fixture) {
        if (llm.available()) {
            try {
                String prompt = Files.readString(baselinePromptPath(), StandardCharsets.UTF_8);
                int fence = prompt.indexOf("---", 3);
                if (prompt.startsWith("---") && fence > 0) {
                    prompt = prompt.substring(fence + 3);
                    if (prompt.startsWith("\n")) {
                        prompt = prompt.substring(1);
                    }
                }
                String user = prompt.replace("{{idea}}", fixture.idea());
                LlmClient.Response response = llm.complete(new LlmClient.Request(
                        "You are a branding expert producing one full brand kit.",
                        user, mainModel, 2048, 0.7));
                return response.text();
            } catch (IOException | LlmUnavailableException e) {
                log.warn("baseline LLM unavailable, using template stub: {}", e.getMessage());
            }
        }
        return baselineStub(fixture.idea());
    }

    private String generateBrandsmith(Fixture fixture) {
        SessionService.CreatedSession session = sessions.create(fixture.idea());
        UUID id = session.id();
        String token = session.ownerToken();
        try {
            seedBrief(id, token, fixture.idea());

            BattleResult battleResult = battle.run(id, token, null, event -> {
            });
            if (battleResult.positions() == null || battleResult.positions().isEmpty()) {
                throw new IllegalStateException("Positioning battle returned no positions");
            }
            battle.select(id, token, new SelectRequest(0, null));

            personality.run(id, token, null);

            naming.run(id, token, null);
            naming.select(id, token, new com.brandsmith.api.naming.SelectRequest(0, null));

            messages.run(id, token, null);
            messages.select(id, token, new com.brandsmith.api.messages.SelectRequest(0));

            visual.run(id, token, null);
            launch.run(id, token, null);
            audit.run(id, token, event -> {
            });

            Map<String, Object> dna = sessions.loadBrandDna(id, token);
            return KitMarkdown.render(dna);
        } finally {
            try {
                sessions.delete(id, token);
            } catch (RuntimeException e) {
                log.warn("eval session cleanup failed for {}: {}", id, e.getMessage());
            }
        }
    }

    private void seedBrief(UUID id, String token, String idea) {
        Map<String, PatchBriefRequest.FieldPatch> fields = new LinkedHashMap<>();
        for (BriefFieldId field : BriefFieldId.values()) {
            fields.put(field.id(), new PatchBriefRequest.FieldPatch(idea));
        }
        interview.patch(id, token, new PatchBriefRequest(fields));
    }

    private Scores judge(Fixture fixture, String kit) {
        if (llm.available()) {
            try {
                PromptLoader.Prompt prompt = prompts.load("eval-judge");
                String user = "<data idea>\n" + fixture.idea() + "\n</data>\n"
                        + "<data kit>\n" + kit + "\n</data>";
                StageResult<JudgeScores> result = runner.run(llm, prompt, user, mainModel, JudgeScores.class);
                if (result.value() != null) {
                    return new Scores(result.value().distinctiveness(),
                            result.value().consistency(), result.value().usefulness(), "llm");
                }
                log.warn("LLM judge degraded to heuristic: {}", result.error());
            } catch (LlmUnavailableException e) {
                log.warn("LLM judge unavailable, using heuristic: {}", e.getMessage());
            }
        }
        return heuristicJudge(kit);
    }

    Scores heuristicJudge(String kit) {
        int lex = lexicon.score(kit);
        int emb = EmbeddingScore.forText(embeddings, kit);
        int distinctiveness = (int) Math.round(AntiGenericScore.combine(lex, emb, (lex + emb) / 2.0));
        int consistency = structureScore(kit);
        int usefulness = usefulnessScore(kit);
        return new Scores(distinctiveness, consistency, usefulness, "heuristic");
    }

    private int structureScore(String kit) {
        String[] markers = {"# ", "> ", "## Pitch", "### Hero", "### Posts", "Bio"};
        int present = 0;
        for (String marker : markers) {
            if (kit.contains(marker)) {
                present++;
            }
        }
        return Math.clamp(present * 100 / markers.length, 0, 100);
    }

    private int usefulnessScore(String kit) {
        int words = kit.isBlank() ? 0 : kit.split("\\s+").length;
        int posts = 0;
        for (String line : kit.split("\n")) {
            String stripped = line.strip();
            if (stripped.matches("\\d+\\.\\s+.+")) {
                posts++;
            }
        }
        int score = Math.min(70, words / 4);
        score += Math.min(30, posts * 10);
        return Math.clamp(score, 0, 100);
    }

    private void save(String fixtureId, String variant, Scores scores) {
        try {
            String json = mapper.writeValueAsString(scores);
            jdbc.update("""
                            INSERT INTO eval_run (id, fixture_id, variant, scores)
                            VALUES (?, ?, ?, ?::jsonb)""",
                    UUID.randomUUID(), fixtureId, variant, json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize eval scores", e);
        }
    }

    static String markdownTable(List<Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("| fixture | variant | distinctiveness | consistency | usefulness |\n");
        sb.append("|---|---|---:|---:|---:|\n");
        for (Row row : rows) {
            Scores s = row.scores();
            sb.append("| ").append(row.fixtureId())
                    .append(" | ").append(row.variant())
                    .append(" | ").append(s.distinctiveness())
                    .append(" | ").append(s.consistency())
                    .append(" | ").append(s.usefulness())
                    .append(" |\n");
        }
        sb.append('\n').append(averages(rows)).append('\n');
        return sb.toString();
    }

    static String averages(List<Row> rows) {
        Map<String, List<Scores>> byVariant = new LinkedHashMap<>();
        for (Row row : rows) {
            byVariant.computeIfAbsent(row.variant(), k -> new ArrayList<>()).add(row.scores());
        }
        StringBuilder sb = new StringBuilder("**Averages**");
        Integer baselineDist = null;
        Integer brandDist = null;
        for (Map.Entry<String, List<Scores>> entry : byVariant.entrySet()) {
            List<Scores> list = entry.getValue();
            int d = 0;
            int c = 0;
            int u = 0;
            for (Scores s : list) {
                d += s.distinctiveness();
                c += s.consistency();
                u += s.usefulness();
            }
            int n = list.size();
            sb.append(" · ").append(entry.getKey())
                    .append(' ').append(d / n).append('/').append(c / n).append('/').append(u / n);
            if (VARIANT_BASELINE.equals(entry.getKey())) {
                baselineDist = d / n;
            } else if (VARIANT_BRANDSMITH.equals(entry.getKey())) {
                brandDist = d / n;
            }
        }
        if (baselineDist != null && brandDist != null) {
            sb.append(" · Δ distinctiveness ").append(brandDist - baselineDist >= 0 ? "+" : "")
                    .append(brandDist - baselineDist);
        }
        return sb.toString();
    }

    private List<Fixture> loadFixtures(int limit) {
        try {
            String json = Files.readString(fixturesPath(), StandardCharsets.UTF_8);
            Fixture[] all = mapper.readValue(json, Fixture[].class);
            int end = limit <= 0 ? all.length : Math.min(limit, all.length);
            return List.of(java.util.Arrays.copyOf(all, end));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load fixtures from " + fixturesPath(), e);
        }
    }

    private Path fixturesPath() {
        return resolveExisting(DEFAULT_FIXTURES, Path.of("..", "fixtures", "ideas.json"));
    }

    private Path baselinePromptPath() {
        return resolveExisting("fixtures/baseline-single-prompt.md",
                Path.of("..", "fixtures", "baseline-single-prompt.md"));
    }

    private Path resolveExisting(String primary, Path fallback) {
        Path p = Path.of(primary);
        if (Files.isRegularFile(p)) {
            return p;
        }
        if (Files.isRegularFile(fallback)) {
            return fallback;
        }
        throw new IllegalStateException("Missing " + primary + " (also tried " + fallback.toAbsolutePath() + ")");
    }

    private String baselineStub(String idea) {
        return """
                # QuickBrand AI

                > Unlock seamless productivity for modern teams

                ## Pitch

                %s is an AI-powered all-in-one workspace that empowers teams to supercharge
                collaboration and unlock their potential at scale.

                ### Hero

                **Headline:** The future of work starts here
                **Subhead:** Seamless, next-level experiences for every team
                **CTA:** Get Started

                ### Posts

                1. Ready to revolutionize the way you work? Our platform elevates every workflow.
                2. One platform, endless possibilities — say hello to synergy.
                3. Built for scale, designed for delight. Join thousands who leverage cutting-edge tools.

                Bio (short): Empowering teams worldwide, one seamless release at a time.
                """.formatted(idea.strip());
    }

    public static void main(String[] args) {
        int limit = 10;
        if (args.length > 0) {
            try {
                limit = Integer.parseInt(args[0].trim());
            } catch (NumberFormatException ignored) {
                // keep default 10
            }
        }
        SpringApplicationBuilder builder = new SpringApplicationBuilder(BrandsmithApiApplication.class)
                .web(WebApplicationType.NONE)
                .bannerMode(Banner.Mode.OFF)
                .properties("spring.main.banner-mode=off");
        try (ConfigurableApplicationContext ctx = builder.run()) {
            String table = ctx.getBean(EvalHarness.class).run(limit);
            System.out.println(table);
        }
    }

    public record Fixture(String id, String idea, String persona, String notes) {
    }

    public record Scores(int distinctiveness, int consistency, int usefulness, String judge) {
        public Scores {
            distinctiveness = Math.clamp(distinctiveness, 0, 100);
            consistency = Math.clamp(consistency, 0, 100);
            usefulness = Math.clamp(usefulness, 0, 100);
            judge = judge == null || judge.isBlank() ? "unknown" : judge.toLowerCase(Locale.ROOT);
        }
    }

    public record Row(String fixtureId, String variant, Scores scores) {
    }

    public record JudgeScores(@Min(0) @Max(100) int distinctiveness,
                              @Min(0) @Max(100) int consistency,
                              @Min(0) @Max(100) int usefulness,
                              @NotNull String rationale) {
    }
}
