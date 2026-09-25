package com.brandsmith.api.visual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.budget.BudgetGuard;
import com.brandsmith.api.det.Palette;
import com.brandsmith.api.det.SvgLogoRenderer;
import com.brandsmith.api.det.Wcag;
import com.brandsmith.api.llm.FakeLlmClient;
import com.brandsmith.api.llm.LlmClient;
import com.brandsmith.api.prompt.PromptLoader;
import com.brandsmith.api.session.SessionService;
import com.brandsmith.api.stage.StageRunner;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

class VisualServiceTest {

    private static final String VALID_LLM_JSON = """
            {
              "moodWords": ["clear", "practical", "calm"],
              "seedHue": 210,
              "saturationBand": "medium",
              "shapeLanguage": "modular",
              "typographyMood": "confident grotesque with a humanist body",
              "typePairIds": ["grotesque-sans", "humanist-sans"],
              "logoConcept": {"form": "wordmark", "letters": "SG", "shapeNotes": "blocks on a grid"},
              "imagery": "students collaborating over laptops in daylight",
              "visualAvoid": ["purple gradient", "lightbulb", "rocket"]
            }""";

    private static final String[][] TEXT_PAIRS = {
            {"fg", "bg"}, {"fg", "surface"},
            {"muted", "bg"}, {"muted", "surface"},
            {"accent", "bg"}, {"accent", "surface"}};

    private final ObjectMapper mapper = new ObjectMapper();
    private final PromptLoader prompts = new PromptLoader();
    private final Validator validator = Validation.byDefaultProvider().configure()
            .buildValidatorFactory().getValidator();
    private final StageRunner runner = new StageRunner(mapper, validator);
    private final BudgetGuard budget = new BudgetGuard("main-model", 3.0, 15.0, 1.0, 5.0);
    private final UUID sessionId = UUID.randomUUID();

    private SessionService sessions;
    private AtomicReference<Map<String, Object>> briefStore;
    private AtomicReference<Map<String, Object>> dnaStore;
    private AtomicReference<SessionService.StageRunRecord> stageRun;

    private static final class NoKeyLlm extends FakeLlmClient {
        @Override
        public boolean available() {
            return false;
        }
    }

    @BeforeEach
    void setUp() {
        sessions = mock(SessionService.class);
        briefStore = new AtomicReference<>(Map.of("idea", "A study group app for students",
                "product_type", "student tool"));
        dnaStore = new AtomicReference<>(Map.of(
                "position", Map.of("category", "Study tools", "differentiator", "Deadline matching")));
        stageRun = new AtomicReference<>();
        when(sessions.loadStage(any(), any())).thenAnswer(inv ->
                new SessionService.StageSnapshot(briefStore.get(), dnaStore.get(), 0.0, 0.40));
        when(sessions.loadStageForUpdate(any(), any())).thenAnswer(inv ->
                new SessionService.StageSnapshot(briefStore.get(), dnaStore.get(), 0.0, 0.40));
        when(sessions.stageLocked(any(), any())).thenReturn(false);
        doAnswer(inv -> {
            stageRun.set(inv.getArgument(0));
            return null;
        }).when(sessions).recordStageRun(any());
        doAnswer(inv -> {
            dnaStore.set(inv.getArgument(1));
            return null;
        }).when(sessions).saveBrandDna(any(), any(), anyDouble());
    }

    private VisualService service(LlmClient llm) {
        return new VisualService(sessions, llm, prompts, runner, budget, mapper, "main-model");
    }

    private static void assertPaletteAa(Palette palette) {
        for (String[] pair : TEXT_PAIRS) {
            String text = "fg".equals(pair[0]) ? palette.fg()
                    : "muted".equals(pair[0]) ? palette.muted() : palette.accent();
            String bg = "bg".equals(pair[1]) ? palette.bg() : palette.surface();
            double ratio = Wcag.ratio(text, bg);
            assertTrue(ratio >= Wcag.AA_TEXT,
                    () -> pair[0] + " on " + pair[1] + " = " + ratio);
        }
    }

    @Test
    void noKeyPathBuildsAaPaletteAndSafeSvg() {
        VisualBoard board = service(new NoKeyLlm()).run(sessionId, "tok", null);

        assertTrue(board.direction().moodWords().size() >= 3);
        assertPaletteAa(board.visual().palette());
        assertTrue(board.visual().logoSvg().contains("<svg"));
        assertFalse(board.visual().logoSvg().contains("<script"));
        for (String font : board.visual().fonts()) {
            assertTrue(SvgLogoRenderer.isAllowedFont(font), font);
        }
        assertNotNull(stageRun.get());
        assertEquals("S6", stageRun.get().stage());
        assertTrue(stageRun.get().degraded());
        assertNotNull(dnaStore.get().get("visual"));
    }

    @Test
    void llmPathParsesDirectionAndPersistsVisual() {
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);

        VisualBoard board = service(fake).run(sessionId, "tok", null);

        assertEquals(210, board.direction().seedHue());
        assertEquals(List.of("grotesque-sans", "humanist-sans"), board.visual().fonts());
        assertEquals("modular", board.visual().shape());
        assertPaletteAa(board.visual().palette());
        assertEquals(1, fake.calls());
        assertNotNull(dnaStore.get().get("visual"));
        verify(sessions).saveBrandDna(eq(sessionId), any(), anyDouble());
    }

    @Test
    void missingPositionReturns409AndSkipsGeneration() {
        dnaStore.set(Map.of());
        FakeLlmClient fake = new FakeLlmClient(VALID_LLM_JSON);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(fake).run(sessionId, "tok", null));

        assertEquals(409, e.getStatusCode().value());
        assertEquals(VisualService.NO_POSITION_MESSAGE, e.getReason());
        assertEquals(0, fake.calls());
        verify(sessions, never()).saveBrandDna(any(), any(), anyDouble());
        verify(sessions, never()).recordStageRun(any());
    }

    @Test
    void fontIdsOutsideAllowlistAreRejectedAndRetried() {
        String bad = VALID_LLM_JSON.replace("grotesque-sans", "evil-font")
                .replace("humanist-sans", "comic-nerd");
        FakeLlmClient fake = new FakeLlmClient(bad, bad);

        VisualBoard board = service(fake).run(sessionId, "tok", null);

        assertEquals(2, fake.calls());
        for (String font : board.visual().fonts()) {
            assertTrue(SvgLogoRenderer.isAllowedFont(font), "bad font leaked: " + font);
        }
        assertNotEquals(List.of("evil-font", "comic-nerd"), board.visual().fonts());
    }

    @Test
    void modelCannotInjectRawSvgThroughLogoGrammar() {
        String evil = VALID_LLM_JSON
                .replace("\"letters\": \"SG\"", "\"letters\": \"<s>\"")
                .replace("\"shapeNotes\": \"blocks on a grid\"",
                        "\"shapeNotes\": \"<script>alert(1)</script>\"");
        FakeLlmClient fake = new FakeLlmClient(evil);

        VisualBoard board = service(fake).run(sessionId, "tok", null);

        String svg = board.visual().logoSvg();
        assertFalse(svg.contains("<script"), svg);
        assertFalse(svg.contains("alert(1)"), svg);
        assertTrue(svg.contains("&lt;s&gt;"), svg);
        assertFalse(svg.contains("<foreignObject"));
    }

    @Test
    void patchWithNewAccentChangesPaletteAccent() {
        service(new NoKeyLlm()).run(sessionId, "tok", null);
        String before = boardPalette().accent();

        VisualBoard rebuilt = service(new NoKeyLlm())
                .patch(sessionId, "tok", new PatchTokensRequest(null, "#112233", null));

        assertEquals("#112233", rebuilt.visual().palette().accent());
        assertNotEquals(before, rebuilt.visual().palette().accent());
        assertTrue(Wcag.meetsAa(rebuilt.visual().palette().fg(), rebuilt.visual().palette().bg()));
        assertEquals("#112233", boardPalette().accent());
    }

    @Test
    void patchWithSeedHueRebuildsPaletteAndStaysAa() {
        service(new NoKeyLlm()).run(sessionId, "tok", null);
        String before = boardPalette().accent();

        VisualBoard rebuilt = service(new NoKeyLlm())
                .patch(sessionId, "tok", new PatchTokensRequest(40, null, "high"));

        assertEquals(40, rebuilt.direction().seedHue());
        assertEquals("high", rebuilt.direction().saturation());
        assertNotEquals(before, rebuilt.visual().palette().accent());
        assertPaletteAa(rebuilt.visual().palette());
    }

    @Test
    void patchBeforeRunReturns409() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(new NoKeyLlm()).patch(sessionId, "tok",
                        new PatchTokensRequest(10, null, null)));
        assertEquals(409, e.getStatusCode().value());
        assertEquals(VisualService.NO_VISUAL_MESSAGE, e.getReason());
    }

    @Test
    void patchRejectsInvalidAccentHex() {
        service(new NoKeyLlm()).run(sessionId, "tok", null);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service(new NoKeyLlm()).patch(sessionId, "tok",
                        new PatchTokensRequest(null, "not-a-color", null)));
        assertEquals(400, e.getStatusCode().value());
    }

    private Palette boardPalette() {
        Object visual = dnaStore.get().get("visual");
        assertNotNull(visual);
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) visual;
        Object palette = map.get("palette");
        assertTrue(palette instanceof Palette, "palette type: " + palette.getClass());
        return (Palette) palette;
    }
}
