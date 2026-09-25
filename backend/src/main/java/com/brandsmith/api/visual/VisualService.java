package com.brandsmith.api.visual;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.det.Palette;
import com.brandsmith.api.det.PaletteGenerator;
import com.brandsmith.api.det.SvgLogoRenderer;
import com.brandsmith.api.det.SvgLogoRenderer.LogoSpec;
import com.brandsmith.api.det.SvgLogoRenderer.Shape;
import com.brandsmith.api.interview.BriefState;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.llm.LlmUnavailableException;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageResult;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class VisualService {

    public static final String STAGE = "S6";
    public static final String NO_POSITION_MESSAGE =
            "Choose a position before running visual direction. A chosen name is preferred.";
    public static final String NO_VISUAL_MESSAGE =
            "Run the visual stage before changing tokens.";
    private static final String PROGRESS_MESSAGE = "Building palette and logo...";

    private static final Logger log = LoggerFactory.getLogger(VisualService.class);
    private static final SvgLogoRenderer RENDERER = new SvgLogoRenderer();

    private final SessionService sessions;
    private final LlmClient llm;
    private final PromptLoader prompts;
    private final StageRunner runner;
    private final BudgetGuard budget;
    private final ObjectMapper mapper;
    private final String mainModel;

    public VisualService(SessionService sessions,
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

    public VisualBoard run(UUID id, String token, String note) {
        return run(id, token, note, event -> {
        });
    }

    public VisualBoard run(UUID id, String token, String note, Consumer<SseEvent> sink) {
        SessionService.StageSnapshot snapshot = sessions.loadStage(id, token);
        Map<String, Object> dna = snapshot.brandDna();
        if (!hasPosition(dna)) {
            throw new ResponseStatusException(CONFLICT, NO_POSITION_MESSAGE);
        }
        if (sessions.stageLocked(id, STAGE)) {
            throw new ResponseStatusException(CONFLICT, "Visual is locked. Unlock it before regenerating.");
        }

        BriefState brief = mapper.convertValue(snapshot.brief(), BriefState.class);
        brief.ensureFields();
        Map<String, Object> position = positionOf(dna);
        String brandName = brandName(dna);

        Generation gen = generate(brief, position, brandName, note, snapshot.spentUsd(),
                snapshot.capUsd(), sink);
        VisualDirection direction = gen.direction();
        BrandVisual visual = build(direction, brandName, null);
        VisualBoard board = new VisualBoard(visual, direction);

        Map<String, Object> nextDna = new LinkedHashMap<>(dna);
        nextDna.put("visual", board.toMap());
        sessions.saveBrandDna(id, nextDna, gen.costUsd());

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("brief", snapshot.brief());
        input.put("position", position);
        if (brandName != null) {
            input.put("name", brandName);
        }
        if (note != null && !note.isBlank()) {
            input.put("note", note.strip());
        }
        sessions.recordStageRun(new SessionService.StageRunRecord(
                id, STAGE, input, board.toMap(), gen.rawResponse(), gen.model(), gen.promptVersion(),
                gen.latencyMs(), gen.tokensIn(), gen.tokensOut(), gen.degraded()));

        return board;
    }

    public VisualBoard patch(UUID id, String token, PatchTokensRequest request) {
        if (request == null) {
            throw new ResponseStatusException(BAD_REQUEST, "patch body required");
        }
        SessionService.StageSnapshot snapshot = sessions.loadStage(id, token);
        Map<String, Object> dna = snapshot.brandDna();
        Object existing = dna.get("visual");
        if (!(existing instanceof Map<?, ?> visualMap) || visualMap.isEmpty()) {
            throw new ResponseStatusException(CONFLICT, NO_VISUAL_MESSAGE);
        }
        Object rawDirection = visualMap.get("direction");
        if (rawDirection == null) {
            throw new ResponseStatusException(CONFLICT, NO_VISUAL_MESSAGE);
        }
        VisualDirection direction = rawDirection instanceof VisualDirection dir
                ? dir
                : mapper.convertValue(rawDirection, VisualDirection.class);
        if (direction == null) {
            throw new ResponseStatusException(CONFLICT, NO_VISUAL_MESSAGE);
        }

        int seedHue = request.seedHue() == null ? direction.seedHue() : request.seedHue();
        if (seedHue < 0 || seedHue > 360) {
            throw new ResponseStatusException(BAD_REQUEST, "seedHue must be between 0 and 360");
        }
        String saturation = request.saturation() == null ? direction.saturation() : request.saturation();
        String accent = request.accent();
        if (accent != null && !accent.matches("#[0-9a-fA-F]{6}")) {
            throw new ResponseStatusException(BAD_REQUEST, "accent must be a #rrggbb hex color");
        }

        VisualDirection updated = new VisualDirection(
                direction.moodWords(), seedHue, saturation, direction.shapeLanguage(),
                direction.fontPairIds(), direction.logoGrammar(), direction.imagery(), direction.avoidList());
        BrandVisual visual = build(updated, brandName(dna), accent);
        VisualBoard board = new VisualBoard(visual, updated);

        Map<String, Object> nextDna = new LinkedHashMap<>(dna);
        nextDna.put("visual", board.toMap());
        sessions.saveBrandDna(id, nextDna, 0);
        return board;
    }

    private Generation generate(BriefState brief, Map<String, Object> position, String brandName, String note,
                                double spentUsd, double capUsd, Consumer<SseEvent> sink) {
        sink.accept(new SseEvent("progress", Map.of("stage", STAGE, "message", PROGRESS_MESSAGE)));
        if (!llm.available()) {
            return templateGeneration(brief, 0, "template", 0, 0);
        }
        budget.ensureWithinCap(spentUsd, capUsd);
        try {
            StageResult<VisualDirection> result = runner.run(llm, prompts.load("s6-visual"),
                    userMessage(brief, position, brandName, note), mainModel, VisualDirection.class);
            double cost = budget.cost(result.model(), result.tokensIn(), result.tokensOut());
            if (result.value() != null) {
                return new Generation(result.value(), result.degraded(), result.model(),
                        result.promptVersion(), result.rawResponse(), result.tokensIn(),
                        result.tokensOut(), result.latencyMs(), cost);
            }
            log.warn("S6 degraded to template: {}", result.error());
            return templateGeneration(brief, cost, result.model(), result.latencyMs(), 0);
        } catch (LlmUnavailableException e) {
            log.warn("S6 degraded to template: {}", e.getMessage());
            return templateGeneration(brief, 0, "template", 0, 0);
        }
    }

    private String userMessage(BriefState brief, Map<String, Object> position, String brandName, String note) {
        StringBuilder sb = new StringBuilder();
        sb.append("<data brief>\n");
        sb.append("idea: ").append(brief.getIdea() == null ? "" : brief.getIdea()).append('\n');
        sb.append("product_type: ").append(brief.getProductType() == null ? "" : brief.getProductType()).append('\n');
        sb.append("</data>\n<data position>\n");
        position.forEach((k, v) -> sb.append(k).append(": ").append(v).append('\n'));
        sb.append("</data>");
        if (brandName != null) {
            sb.append("\n<data identity>\nname: ").append(brandName).append("\n</data>");
        }
        if (note != null && !note.isBlank()) {
            sb.append("\n<data regenerate_note>\n").append(note.strip()).append("\n</data>");
        }
        return sb.toString();
    }

    private Generation templateGeneration(BriefState brief, double cost, String model, long latencyMs, int tokens) {
        return new Generation(template(brief), true, model, null, null, tokens, tokens, latencyMs, cost);
    }

    private VisualDirection template(BriefState brief) {
        String product = brief.getProductType() == null || brief.getProductType().isBlank()
                ? "brand" : brief.getProductType().strip();
        return new VisualDirection(
                List.of("clear", "practical", "calm"),
                210,
                "medium",
                "modular",
                List.of("grotesque-sans", "humanist-sans"),
                new LogoGrammar("wordmark", initials(product), "blocks sit on a quiet grid"),
                "Real moments of " + product + " in use, natural light, no stock smiles",
                List.of("purple gradient", "lightbulb", "rocket", "rainbow glow"));
    }

    private BrandVisual build(VisualDirection direction, String brandName, String accentOverride) {
        double saturation = switch (direction.saturation()) {
            case "low" -> 0.30;
            case "high" -> 0.85;
            default -> 0.55;
        };
        double bias = lightnessBias(direction.moodWords());
        Palette palette = PaletteGenerator.generate(direction.seedHue(), saturation, bias);
        if (accentOverride != null) {
            palette = new Palette(palette.bg(), palette.surface(), accentOverride,
                    palette.fg(), palette.muted());
        }
        String text = logoText(direction, brandName);
        String type = "monogram".equals(direction.logoGrammar().form()) ? "monogram" : "wordmark";
        LogoSpec spec = new LogoSpec(type, text,
                shapes(direction.shapeLanguage(), palette),
                direction.fontPairIds().get(0),
                letterSpacing(direction.shapeLanguage()));
        return new BrandVisual(palette, direction.fontPairIds(), direction.shapeLanguage(),
                RENDERER.render(spec));
    }

    static double lightnessBias(List<String> moodWords) {
        for (String word : moodWords) {
            String w = word.toLowerCase(Locale.ROOT);
            if (w.contains("dark") || w.contains("moody") || w.contains("bold")
                    || w.contains("noir") || w.contains("premium") || w.contains("serious")) {
                return -0.5;
            }
        }
        return 0.4;
    }

    static List<Shape> shapes(String shapeLanguage, Palette palette) {
        return switch (shapeLanguage) {
            case "rounded" -> List.of(new Shape("circle", 282, 28, 24, 24, palette.accent()));
            case "sharp" -> List.of(new Shape("rect", 284, 32, 16, 16, palette.accent()));
            case "modular" -> List.of(
                    new Shape("rect", 272, 28, 14, 14, palette.accent()),
                    new Shape("rect", 290, 46, 14, 14, palette.muted()));
            case "organic" -> List.of(new Shape("arc", 270, 55, 36, 24, palette.accent()));
            default -> List.of();
        };
    }

    static double letterSpacing(String shapeLanguage) {
        return switch (shapeLanguage) {
            case "sharp" -> 2.0;
            case "modular" -> 1.0;
            case "rounded" -> 0.5;
            default -> 0.0;
        };
    }

    private static String logoText(VisualDirection direction, String brandName) {
        String letters = direction.logoGrammar().letters();
        if ("monogram".equals(direction.logoGrammar().form())) {
            return letters;
        }
        if (brandName != null && !brandName.isBlank()) {
            String name = brandName.strip();
            return name.length() > 80 ? name.substring(0, 80) : name;
        }
        return letters;
    }

    private static String initials(String product) {
        String[] parts = product.split("[^A-Za-z0-9]+");
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!part.isEmpty() && sb.length() < 3) {
                sb.append(Character.toUpperCase(part.charAt(0)));
            }
        }
        return sb.length() == 0 ? "BR" : sb.toString();
    }

    private static boolean hasPosition(Map<String, Object> dna) {
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> positionOf(Map<String, Object> dna) {
        Object position = dna.get("position");
        return position instanceof Map ? (Map<String, Object>) position : Map.of();
    }

    private static String brandName(Map<String, Object> dna) {
        Object identity = dna.get("identity");
        if (identity instanceof Map<?, ?> map) {
            Object name = map.get("name");
            if (name instanceof String text && !text.isBlank()) {
                return text.strip();
            }
        }
        return null;
    }

    public record VisualRunResult(VisualBoard board, long latencyMs, boolean degraded,
                                  String model, String promptVersion) {
    }

    public record SseEvent(String name, Object data) {
    }

    private record Generation(VisualDirection direction,
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
