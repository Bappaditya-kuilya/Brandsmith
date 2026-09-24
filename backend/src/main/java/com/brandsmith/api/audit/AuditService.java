package com.brandsmith.api.audit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.det.LexiconScorer;
import com.brandsmith.api.det.Wcag;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.llm.LlmUnavailableException;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.prompt.PromptLoader.Prompt;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageResult;
import com.brandsmith.api.stage.StageRunner;
import com.brandsmith.api.stage.StageRunRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class AuditService {

    public static final String STAGE = "S7";
    public static final String LOCKED_MESSAGE =
            "Consistency audit is locked. Unlock it before re-running.";
    public static final int REVISE_THRESHOLD = 70;
    public static final int MAX_REVISE_ROUNDS = 2;

    static final List<String> DIMENSIONS = List.of(
            "personalityFit", "voiceCompliance", "audienceFit", "positioningAlignment", "visualCoherence");

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);
    private static final TypeReference<LinkedHashMap<String, Object>> MAP_TYPE = new TypeReference<>() {
    };
    private static final Set<String> ALLOWED_FONTS = loadFonts();

    private final SessionService sessions;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final StageRunRecorder recorder;
    private final JdbcTemplate jdbc;
    private final LexiconScorer cliche;
    private final String mainModel;

    public AuditService(SessionService sessions,
                        LlmClient llm,
                        PromptLoader prompts,
                        StageRunner runner,
                        BudgetGuard budget,
                        ObjectMapper mapper,
                        StageRunRecorder recorder,
                        JdbcTemplate jdbc,
                        @Value("${brandsmith.llm.main-model}") String mainModel) {
        this.sessions = sessions;
        this.llm = llm;
        this.prompts = prompts;
        this.runner = runner;
        this.budget = budget;
        this.mapper = mapper;
        this.recorder = recorder;
        this.jdbc = jdbc;
        this.cliche = new LexiconScorer();
        this.mainModel = mainModel;
    }

    static int weightOf(String dimension) {
        return switch (dimension) {
            case "personalityFit" -> 25;
            case "voiceCompliance" -> 25;
            case "audienceFit" -> 20;
            case "positioningAlignment" -> 20;
            case "visualCoherence" -> 10;
            default -> throw new IllegalArgumentException("Unknown dimension: " + dimension);
        };
    }

    static int overallOf(List<DimensionScore> dimensions) {
        int weighted = 0;
        for (DimensionScore d : dimensions) {
            weighted += d.score() * weightOf(d.dimension());
        }
        return weighted / 100;
    }

    public AuditResponse run(UUID id, String token, Consumer<SseEvent> sink) {
        SessionService.StageSnapshot snapshot = sessions.loadStage(id, token);
        if (sessions.stageLocked(id, STAGE)) {
            throw new ResponseStatusException(CONFLICT, LOCKED_MESSAGE);
        }
        Map<String, Object> dna = new LinkedHashMap<>(snapshot.brandDna());
        Map<String, Object> brief = snapshot.brief();
        Map<String, String> assets = collectAssets(dna);
        List<String> banned = voiceBanned(dna);
        List<String> forbidden = concat(banned, avoidList(dna));
        int[] range = voiceRange(dna);

        StageRunRecorder.Run run = recorder.start(id, STAGE, Map.of("assets", assets));
        try {
            sink.accept(new SseEvent("stage_started", Map.of("stage", STAGE, "sessionId", id.toString())));
            sink.accept(new SseEvent("progress", progress("Scoring 5 dimensions")));

            String model = "template";
            String promptVersion = null;
            String raw = null;
            int tokensIn = 0;
            int tokensOut = 0;
            long latencyMs = 0;
            double cost = 0;

            JudgeCall judge = judgeOnce(dna, brief, assets, snapshot.spentUsd(), snapshot.capUsd());
            model = judge.model();
            promptVersion = judge.promptVersion();
            raw = judge.raw();
            tokensIn += judge.tokensIn();
            tokensOut += judge.tokensOut();
            latencyMs += judge.latencyMs();
            cost += judge.costUsd();

            VoiceChecks.Result voice = VoiceChecks.checkAll(assets, banned, range);
            AuditResult result = build(dna, brief, assets, voice, judge.output());

            List<Diff> diffs = new ArrayList<>();
            int rounds = 0;
            while (rounds < MAX_REVISE_ROUNDS && hasFailing(result)) {
                List<ReviseTarget> targets = targets(result, assets);
                if (targets.isEmpty()) {
                    break;
                }
                rounds++;
                sink.accept(new SseEvent("progress",
                        progress("Auto-revise round " + rounds + " of " + MAX_REVISE_ROUNDS)));
                for (ReviseTarget target : targets) {
                    String before = assets.get(target.asset());
                    ReviseCall revised = revise(target, before, forbidden,
                            snapshot.spentUsd() + cost, snapshot.capUsd());
                    if (revised.model() != null) {
                        model = revised.model();
                    }
                    if (revised.promptVersion() != null) {
                        promptVersion = revised.promptVersion();
                    }
                    if (revised.raw() != null) {
                        raw = revised.raw();
                    }
                    tokensIn += revised.tokensIn();
                    tokensOut += revised.tokensOut();
                    latencyMs += revised.latencyMs();
                    cost += revised.costUsd();
                    String after = revised.text();
                    if (!after.equals(before)) {
                        diffs.add(new Diff(target.asset(), target.dimension(), before, after));
                        assets.put(target.asset(), after);
                        writeAsset(dna, target.asset(), after);
                    }
                }
                voice = VoiceChecks.checkAll(assets, banned, range);
                judge = judgeOnce(dna, brief, assets, snapshot.spentUsd() + cost, snapshot.capUsd());
                model = judge.model();
                promptVersion = judge.promptVersion();
                if (judge.raw() != null) {
                    raw = judge.raw();
                }
                tokensIn += judge.tokensIn();
                tokensOut += judge.tokensOut();
                latencyMs += judge.latencyMs();
                cost += judge.costUsd();
                result = build(dna, brief, assets, voice, judge.output());
            }

            boolean degraded = judge.degraded();
            AuditResponse response = new AuditResponse(result, diffs, rounds, degraded);
            sessions.saveBrandDna(id, dna, cost);
            recorder.run(run, degraded ? "degraded" : "ok", response, raw, model, promptVersion,
                    latencyMs, tokensIn, tokensOut);
            insertScores(run.id(), result);
            sink.accept(new SseEvent("stage_completed", Map.of(
                    "stage", STAGE,
                    "status", degraded ? "degraded" : "ok",
                    "data", response)));
            return response;
        } catch (RuntimeException e) {
            recorder.fail(run, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            throw e;
        }
    }

    public DriftResult driftCheck(UUID id, String token, String text, String assetType) {
        SessionService.StageSnapshot snapshot = sessions.loadStage(id, token);
        Map<String, Object> dna = snapshot.brandDna();
        Map<String, Object> brief = snapshot.brief();
        String candidate = text.strip();
        Map<String, String> assets = Map.of(assetType, candidate);
        List<String> banned = voiceBanned(dna);
        List<String> avoid = avoidList(dna);

        JudgeCall judge = judgeOnce(dna, brief, assets, snapshot.spentUsd(), snapshot.capUsd());
        VoiceChecks.Result voice = VoiceChecks.checkAll(assets, banned, voiceRange(dna));
        AuditResult result = build(dna, brief, assets, voice, judge.output());

        List<String> flagged = flagged(candidate, banned, result);
        boolean allPass = result.dimensions().stream().noneMatch(DimensionScore::failing);
        String rewrite = (!allPass || !flagged.isEmpty())
                ? rewriteDrift(candidate, concat(banned, avoid), flagged, result, assetType, snapshot)
                : candidate;

        List<DriftResult.Verdict> verdicts = result.dimensions().stream()
                .map(d -> new DriftResult.Verdict(d.dimension(), d.weight(), d.score(),
                        !d.failing(), d.evidence(), d.deterministicFindings()))
                .toList();
        return new DriftResult(assetType, result.overall(), allPass, verdicts, flagged, rewrite);
    }

    private AuditResult build(Map<String, Object> dna, Map<String, Object> brief,
                              Map<String, String> assets, VoiceChecks.Result voice, JudgeOutput judge) {
        List<DimensionScore> dimensions = List.of(
                personalityDim(dna, assets, judge),
                voiceDim(voice, judge),
                audienceDim(dna, brief, assets, judge),
                positioningDim(dna, judge),
                visualDim(dna, judge));

        List<Finding> conflicts = new ArrayList<>();
        for (DimensionScore d : dimensions) {
            for (Finding f : d.deterministicFindings()) {
                if ("fail".equals(f.severity())) {
                    conflicts.add(f);
                }
            }
        }
        if (judge != null) {
            for (Finding f : judge.findings()) {
                if ("fail".equals(f.severity())) {
                    conflicts.add(f);
                }
            }
        }

        Map<String, List<String>> revise = new LinkedHashMap<>();
        if (judge != null) {
            for (ReviseInstruction instruction : judge.reviseInstructions()) {
                if (instruction.asset() == null || instruction.asset().isBlank()
                        || instruction.instruction() == null || instruction.instruction().isBlank()) {
                    continue;
                }
                revise.computeIfAbsent(instruction.asset().strip(), k -> new ArrayList<>())
                        .add(instruction.instruction().strip());
            }
        }
        return new AuditResult(dimensions, overallOf(dimensions), conflicts, revise);
    }

    private DimensionScore personalityDim(Map<String, Object> dna, Map<String, String> assets,
                                          JudgeOutput judge) {
        String dim = "personalityFit";
        Integer llm = llmScore(judge, dim);
        if (llm != null) {
            return new DimensionScore(dim, weightOf(dim), llm, quotes(judge, dim), List.of());
        }
        List<Map<String, Object>> traits = mapsAt(mapAt(dna, "personality"), "traits");
        List<String> avoid = avoidList(dna);
        List<String> evidence = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        int score;
        if (traits.isEmpty()) {
            score = 55;
            findings.add(Finding.warn(dim, "personality", "", "personality traits missing from brand_dna"));
        } else {
            score = 80;
            for (Map<String, Object> trait : traits) {
                String name = strAt(trait, "name");
                if (name != null) {
                    evidence.add(name);
                }
            }
            for (Map.Entry<String, String> asset : assets.entrySet()) {
                for (String word : avoid) {
                    if (VoiceChecks.containsWord(asset.getValue(), word)) {
                        score -= 20;
                        findings.add(Finding.fail(dim, asset.getKey(), word,
                                "avoid-list word '" + word + "' used"));
                    }
                }
            }
        }
        return new DimensionScore(dim, weightOf(dim), score, evidence, findings);
    }

    private DimensionScore voiceDim(VoiceChecks.Result voice, JudgeOutput judge) {
        String dim = "voiceCompliance";
        Integer llm = llmScore(judge, dim);
        int score = llm == null ? voice.score() : Math.min(voice.score(), llm);
        List<String> evidence = llm != null ? quotes(judge, dim) : List.of();
        return new DimensionScore(dim, weightOf(dim), score, evidence, voice.findings());
    }

    private DimensionScore audienceDim(Map<String, Object> dna, Map<String, Object> brief,
                                       Map<String, String> assets, JudgeOutput judge) {
        String dim = "audienceFit";
        Integer llm = llmScore(judge, dim);
        if (llm != null) {
            return new DimensionScore(dim, weightOf(dim), llm, quotes(judge, dim), List.of());
        }
        String audience = audienceText(dna, brief);
        List<String> evidence = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        int score;
        if (audience == null) {
            score = 55;
            findings.add(Finding.warn(dim, "brief", "", "audience not specified"));
        } else {
            boolean extended = hasExtendedAssets(collectAssets(dna));
            score = extended ? 78 : 70;
            evidence.add(audience);
            if (!extended) {
                evidence.add("no hero/posts/bio in brand_dna - limited audience evidence");
            }
        }
        return new DimensionScore(dim, weightOf(dim), score, evidence, findings);
    }

    private DimensionScore positioningDim(Map<String, Object> dna, JudgeOutput judge) {
        String dim = "positioningAlignment";
        Presence presence = positioningPresence(dna);
        Integer llm = llmScore(judge, dim);
        int score = llm == null ? presence.score() : Math.min(llm, presence.score());
        List<String> evidence = llm != null && !quotes(judge, dim).isEmpty()
                ? quotes(judge, dim) : presence.evidence();
        return new DimensionScore(dim, weightOf(dim), score, evidence, presence.findings());
    }

    private DimensionScore visualDim(Map<String, Object> dna, JudgeOutput judge) {
        String dim = "visualCoherence";
        VisualDet det = visualDet(dna);
        Integer llm = llmScore(judge, dim);
        int score = llm == null ? det.score() : Math.min(llm, det.score());
        List<String> evidence = llm != null && !quotes(judge, dim).isEmpty()
                ? quotes(judge, dim) : det.evidence();
        return new DimensionScore(dim, weightOf(dim), score, evidence, det.findings());
    }

    private Presence positioningPresence(Map<String, Object> dna) {
        Map<String, Object> identity = mapAt(dna, "identity");
        String name = strAt(identity, "name");
        String tagline = strAt(identity, "tagline");
        String pitch = strAt(identity, "pitch");
        int score = 0;
        List<String> evidence = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        if (name != null) {
            score += 50;
            evidence.add(name);
        } else {
            findings.add(Finding.fail("positioningAlignment", "identity", "",
                    "identity.name missing where required"));
        }
        if (tagline != null) {
            score += 50;
            evidence.add(tagline);
        } else {
            findings.add(Finding.fail("positioningAlignment", "identity", "",
                    "identity.tagline missing where required"));
        }
        if (pitch == null) {
            findings.add(Finding.warn("positioningAlignment", "identity", "", "identity.pitch missing"));
        }
        return new Presence(score, evidence, findings);
    }

    private VisualDet visualDet(Map<String, Object> dna) {
        String dim = "visualCoherence";
        Map<String, Object> visual = mapAt(dna, "visual");
        if (visual == null || visual.isEmpty()) {
            return new VisualDet(70, List.of(),
                    List.of(Finding.warn(dim, "visual", "",
                            "visual not present in brand_dna - contrast and fonts unverified")));
        }
        int score = 0;
        List<String> evidence = new ArrayList<>();
        List<Finding> findings = new ArrayList<>();
        Map<String, Object> palette = mapAt(visual, "palette");
        String fg = strAt(palette, "fg");
        String bg = strAt(palette, "bg");
        if (fg != null && bg != null) {
            try {
                double ratio = Wcag.ratio(fg, bg);
                evidence.add(fg + " on " + bg + " contrast " + String.format(Locale.ROOT, "%.2f", ratio));
                if (Wcag.meetsAa(fg, bg)) {
                    score += 50;
                } else {
                    findings.add(Finding.fail(dim, "palette", fg + " on " + bg,
                            "contrast ratio below WCAG AA 4.5"));
                }
            } catch (IllegalArgumentException e) {
                findings.add(Finding.fail(dim, "palette", fg + " on " + bg, "invalid hex color"));
            }
        } else {
            findings.add(Finding.warn(dim, "palette", "", "palette.fg/bg missing"));
        }
        List<String> fonts = strings(visual.get("fonts"));
        if (fonts.isEmpty()) {
            findings.add(Finding.warn(dim, "fonts", "", "visual.fonts missing"));
        } else {
            List<String> bad = fonts.stream().filter(f -> !ALLOWED_FONTS.contains(f)).toList();
            if (bad.isEmpty()) {
                score += 50;
                evidence.add("fonts: " + String.join(", ", fonts));
            } else {
                findings.add(Finding.fail(dim, "fonts", String.join(", ", bad),
                        "font ids not in allowed set"));
            }
        }
        return new VisualDet(score, evidence, findings);
    }

    private JudgeCall judgeOnce(Map<String, Object> dna, Map<String, Object> brief,
                                Map<String, String> assets, double spentUsd, double capUsd) {
        Prompt prompt = prompts.load("s7-judge");
        if (!llm.available()) {
            return new JudgeCall(null, true, "template", prompt.version(), null, 0, 0, 0, 0);
        }
        budget.ensureWithinCap(spentUsd, capUsd);
        try {
            StageResult<JudgeOutput> result = runner.run(llm, prompt,
                    judgeUserMessage(dna, brief, assets), mainModel, JudgeOutput.class);
            JudgeOutput output = usable(result.value()) ? result.value() : null;
            double cost = budget.cost(result.model(), result.tokensIn(), result.tokensOut());
            return new JudgeCall(output, output == null || result.degraded(), result.model(),
                    result.promptVersion(), result.rawResponse(), result.tokensIn(), result.tokensOut(),
                    result.latencyMs(), cost);
        } catch (LlmUnavailableException e) {
            log.warn("S7 judge degraded to heuristics: {}", e.getMessage());
            return new JudgeCall(null, true, "template", prompt.version(), null, 0, 0, 0, 0);
        }
    }

    private ReviseCall revise(ReviseTarget target, String text, List<String> forbidden,
                              double spentUsd, double capUsd) {
        Prompt prompt = prompts.load("s7-revise");
        if (llm.available()) {
            budget.ensureWithinCap(spentUsd, capUsd);
            try {
                StageResult<ReviseOutput> result = runner.run(llm, prompt,
                        reviseUserMessage(target, text, forbidden), mainModel, ReviseOutput.class);
                if (result.value() != null && result.value().text() != null
                        && !result.value().text().isBlank()) {
                    String revised = VoiceChecks.templateRevise(result.value().text().strip(), forbidden);
                    double cost = budget.cost(result.model(), result.tokensIn(), result.tokensOut());
                    return new ReviseCall(revised, result.model(), result.promptVersion(),
                            result.rawResponse(), result.tokensIn(), result.tokensOut(),
                            result.latencyMs(), cost);
                }
                log.warn("S7 revise degraded to template: {}", result.error());
            } catch (LlmUnavailableException e) {
                log.warn("S7 revise degraded to template: {}", e.getMessage());
            }
        }
        return new ReviseCall(VoiceChecks.templateRevise(text, forbidden), "template",
                prompt.version(), null, 0, 0, 0, 0);
    }

    private String rewriteDrift(String text, List<String> forbidden, List<String> flagged,
                                AuditResult result, String assetType, SessionService.StageSnapshot snapshot) {
        List<String> rules = new ArrayList<>();
        for (Finding f : result.conflicts()) {
            String rule = f.rule() == null ? "" : f.rule();
            if (f.quote() != null && !f.quote().isBlank()) {
                rule += " (quote: \"" + f.quote() + "\")";
            }
            rules.add(rule);
        }
        for (String phrase : flagged) {
            rules.add("flagged phrase: " + phrase);
        }
        List<String> strip = concat(forbidden, strippable(flagged));
        if (llm.available() && !rules.isEmpty()) {
            ReviseTarget target = new ReviseTarget(assetType, worstDimension(result), rules);
            try {
                return revise(target, text, strip, snapshot.spentUsd(), snapshot.capUsd()).text();
            } catch (RuntimeException e) {
                log.warn("drift rewrite degraded to template: {}", e.getMessage());
            }
        }
        return VoiceChecks.templateRevise(text, strip);
    }

    private List<String> flagged(String text, List<String> banned, AuditResult result) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (banned != null) {
            for (String word : banned) {
                if (VoiceChecks.containsWord(text, word)) {
                    out.add(word.strip());
                }
            }
        }
        out.addAll(cliche.hits(text));
        for (Finding f : result.conflicts()) {
            if (f.quote() != null && !f.quote().isBlank() && text.contains(f.quote())) {
                out.add(f.quote());
            }
        }
        return new ArrayList<>(out);
    }

    private List<ReviseTarget> targets(AuditResult result, Map<String, String> assets) {
        List<String> failing = result.dimensions().stream()
                .filter(DimensionScore::failing)
                .map(DimensionScore::dimension)
                .toList();
        if (failing.isEmpty()) {
            return List.of();
        }
        Map<String, String> dimByAsset = new LinkedHashMap<>();
        Map<String, LinkedHashSet<String>> rulesByAsset = new LinkedHashMap<>();
        result.reviseInstructions().forEach((asset, instructions) -> {
            if (!hasText(assets, asset)) {
                return;
            }
            dimByAsset.putIfAbsent(asset, dimForAsset(result, asset, failing));
            rulesByAsset.computeIfAbsent(asset, k -> new LinkedHashSet<>()).addAll(instructions);
        });
        for (Finding f : result.conflicts()) {
            if (!failing.contains(f.dimension()) || !hasText(assets, f.asset())) {
                continue;
            }
            dimByAsset.putIfAbsent(f.asset(), f.dimension());
            String rule = f.rule() == null ? "" : f.rule();
            if (f.quote() != null && !f.quote().isBlank()) {
                rule += " (quote: \"" + f.quote() + "\")";
            }
            rulesByAsset.computeIfAbsent(f.asset(), k -> new LinkedHashSet<>()).add(rule);
        }
        List<ReviseTarget> out = new ArrayList<>();
        dimByAsset.forEach((asset, dim) ->
                out.add(new ReviseTarget(asset, dim, List.copyOf(rulesByAsset.get(asset)))));
        return out;
    }

    private void insertScores(UUID stageRunId, AuditResult result) {
        for (DimensionScore d : result.dimensions()) {
            jdbc.update("""
                            INSERT INTO score (id, stage_run_id, kind, value, details)
                            VALUES (?, ?, ?, ?, ?::jsonb)""",
                    UUID.randomUUID(), stageRunId, d.dimension(), (double) d.score(), toJson(d));
        }
        jdbc.update("""
                        INSERT INTO score (id, stage_run_id, kind, value, details)
                        VALUES (?, ?, ?, ?, ?::jsonb)""",
                UUID.randomUUID(), stageRunId, "overall", (double) result.overall(), toJson(result));
    }

    private String judgeUserMessage(Map<String, Object> dna, Map<String, Object> brief,
                                    Map<String, String> assets) {
        return "<data brand_dna>\n" + toJson(dna) + "\n</data>\n"
                + "<data assets>\n" + toJson(assets) + "\n</data>\n"
                + "<data brief>\n" + toJson(brief) + "\n</data>";
    }

    private String reviseUserMessage(ReviseTarget target, String text, List<String> forbidden) {
        StringBuilder sb = new StringBuilder();
        sb.append("<data asset>").append(target.asset()).append("</data>\n");
        sb.append("<data dimension>").append(target.dimension()).append("</data>\n");
        sb.append("<data rules>\n");
        for (String rule : target.rules()) {
            sb.append("- ").append(rule).append('\n');
        }
        sb.append("</data>\n");
        sb.append("<data forbidden_words>").append(String.join(", ", forbidden)).append("</data>\n");
        sb.append("<data current_text>\n").append(text).append("\n</data>");
        return sb.toString();
    }

    static Map<String, String> collectAssets(Map<String, Object> dna) {
        LinkedHashMap<String, String> out = new LinkedHashMap<>();
        putIfPresent(out, "name", strAt(mapAt(dna, "identity"), "name"));
        putIfPresent(out, "tagline", strAt(mapAt(dna, "identity"), "tagline"));
        putIfPresent(out, "pitch", strAt(mapAt(dna, "identity"), "pitch"));
        Map<String, Object> assets = mapAt(dna, "assets");
        if (assets != null) {
            putIfPresent(out, "hero", strAt(assets, "hero"));
            if (assets.get("posts") instanceof List<?> posts) {
                for (int i = 0; i < posts.size(); i++) {
                    Object post = posts.get(i);
                    if (post instanceof String s && !s.isBlank()) {
                        out.put("posts[" + i + "]", s.strip());
                    }
                }
            }
            putIfPresent(out, "bio", strAt(assets, "bio"));
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    static void writeAsset(Map<String, Object> dna, String key, String value) {
        switch (key) {
            case "name", "tagline", "pitch" -> mutableMap(dna, "identity").put(key, value);
            case "hero", "bio" -> mutableMap(dna, "assets").put(key, value);
            default -> {
                if (key != null && key.startsWith("posts[") && key.endsWith("]")) {
                    try {
                        int index = Integer.parseInt(key.substring(6, key.length() - 1));
                        Map<String, Object> assets = mutableMap(dna, "assets");
                        List<Object> posts = assets.get("posts") instanceof List<?> existing
                                ? new ArrayList<>(existing) : new ArrayList<>();
                        while (posts.size() <= index) {
                            posts.add("");
                        }
                        posts.set(index, value);
                        assets.put("posts", posts);
                    } catch (NumberFormatException ignored) {
                        // unknown asset key, nothing to write
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutableMap(Map<String, Object> parent, String key) {
        Object existing = parent.get(key);
        Map<String, Object> copy = existing instanceof Map
                ? new LinkedHashMap<>((Map<String, Object>) existing)
                : new LinkedHashMap<>();
        parent.put(key, copy);
        return copy;
    }

    private static boolean hasFailing(AuditResult result) {
        return result.dimensions().stream().anyMatch(DimensionScore::failing);
    }

    private static boolean hasText(Map<String, String> assets, String asset) {
        if (asset == null || !assets.containsKey(asset)) {
            return false;
        }
        String value = assets.get(asset);
        return value != null && !value.isBlank();
    }

    private static String dimForAsset(AuditResult result, String asset, List<String> failing) {
        for (Finding f : result.conflicts()) {
            if (failing.contains(f.dimension()) && asset.equals(f.asset())) {
                return f.dimension();
            }
        }
        return failing.get(0);
    }

    private static String worstDimension(AuditResult result) {
        return result.dimensions().stream()
                .min((a, b) -> Integer.compare(a.score(), b.score()))
                .map(DimensionScore::dimension)
                .orElse("voiceCompliance");
    }

    private static boolean usable(JudgeOutput output) {
        if (output == null || output.scores() == null) {
            return false;
        }
        for (String dim : DIMENSIONS) {
            Integer value = output.scores().get(dim);
            if (value == null || value < 0 || value > 100) {
                return false;
            }
        }
        return true;
    }

    private static Integer llmScore(JudgeOutput output, String dimension) {
        if (output == null || output.scores() == null) {
            return null;
        }
        Integer value = output.scores().get(dimension);
        return value == null || value < 0 || value > 100 ? null : value;
    }

    private static List<String> quotes(JudgeOutput judge, String dimension) {
        if (judge == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Finding f : judge.findings()) {
            if (dimension.equals(f.dimension()) && f.quote() != null
                    && !f.quote().isBlank() && !out.contains(f.quote())) {
                out.add(f.quote());
            }
        }
        return out;
    }

    private static List<String> voiceBanned(Map<String, Object> dna) {
        Map<String, Object> voice = mapAt(dna, "voice");
        if (voice == null) {
            return List.of();
        }
        List<String> banned = strings(voice.get("bannedWords"));
        return banned.isEmpty() ? strings(voice.get("banned")) : banned;
    }

    private static List<String> avoidList(Map<String, Object> dna) {
        Map<String, Object> personality = mapAt(dna, "personality");
        return personality == null ? List.of() : strings(personality.get("avoidList"));
    }

    private static int[] voiceRange(Map<String, Object> dna) {
        Map<String, Object> voice = mapAt(dna, "voice");
        if (voice instanceof Map && voice.get("sentenceWords") instanceof List<?> range
                && range.size() == 2 && range.get(0) instanceof Number min && range.get(1) instanceof Number max) {
            return new int[] {min.intValue(), max.intValue()};
        }
        return null;
    }

    private static String audienceText(Map<String, Object> dna, Map<String, Object> brief) {
        String fromDna = strAt(mapAt(dna, "brief"), "audience");
        if (fromDna != null) {
            return fromDna;
        }
        return strAt(mapAt(mapAt(brief, "fields"), "target_user"), "value");
    }

    private static boolean hasExtendedAssets(Map<String, String> assets) {
        return assets.keySet().stream()
                .anyMatch(k -> k.equals("hero") || k.equals("bio") || k.startsWith("posts"));
    }

    private static List<String> strippable(List<String> phrases) {
        return phrases.stream()
                .filter(p -> p != null && !p.isBlank() && !p.startsWith("-"))
                .toList();
    }

    private static List<String> concat(List<String> first, List<String> second) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        if (first != null) {
            out.addAll(first);
        }
        if (second != null) {
            out.addAll(second);
        }
        return new ArrayList<>(out);
    }

    private static void putIfPresent(Map<String, String> target, String key, String value) {
        if (value != null && !value.isBlank()) {
            target.put(key, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapAt(Map<String, Object> parent, String key) {
        if (parent == null) {
            return null;
        }
        return parent.get(key) instanceof Map ? (Map<String, Object>) parent.get(key) : null;
    }

    private static List<Map<String, Object>> mapsAt(Map<String, Object> parent, String key) {
        if (parent == null || !(parent.get(key) instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> cast = (Map<String, Object>) map;
                out.add(cast);
            }
        }
        return out;
    }

    private static String strAt(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value instanceof String s && !s.isBlank() ? s.strip() : null;
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s && !s.isBlank()) {
                out.add(s.strip());
            }
        }
        return out;
    }

    private static Map<String, Object> progress(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("stage", STAGE);
        payload.put("message", message);
        return payload;
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    private static Set<String> loadFonts() {
        try (InputStream in = AuditService.class.getResourceAsStream("/fonts-allowed.txt")) {
            if (in == null) {
                return Set.of();
            }
            Set<String> fonts = new LinkedHashSet<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        fonts.add(trimmed);
                    }
                }
            }
            return Collections.unmodifiableSet(fonts);
        } catch (IOException e) {
            throw new IllegalStateException("failed to read fonts-allowed.txt", e);
        }
    }

    private record JudgeCall(JudgeOutput output,
                             boolean degraded,
                             String model,
                             String promptVersion,
                             String raw,
                             int tokensIn,
                             int tokensOut,
                             long latencyMs,
                             double costUsd) {
    }

    private record ReviseCall(String text,
                              String model,
                              String promptVersion,
                              String raw,
                              int tokensIn,
                              int tokensOut,
                              long latencyMs,
                              double costUsd) {
    }

    record ReviseTarget(String asset, String dimension, List<String> rules) {
    }

    private record Presence(int score, List<String> evidence, List<Finding> findings) {
    }

    private record VisualDet(int score, List<String> evidence, List<Finding> findings) {
    }

    public record ReviseOutput(@NotBlank @Size(max = 5000) String text) {
    }

    public record SseEvent(String name, Object data) {
    }
}
