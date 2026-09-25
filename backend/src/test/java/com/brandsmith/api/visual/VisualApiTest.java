package com.brandsmith.api.visual;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.brandsmith.api.det.Wcag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "brandsmith.llm.api-key=",
        "brandsmith.cookies.secure=false",
        "brandsmith.rate-limit.create-capacity=200"
})
@AutoConfigureMockMvc
class VisualApiTest {

    private static final String VALID_IDEA = "A study group app that matches students by shared assignment deadlines";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

    @Autowired
    private JdbcTemplate jdbc;

    private record Session(String id, MockCookie owner) {
    }

    private Session createSession() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"" + VALID_IDEA + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(cookie().exists("owner_token"))
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        MockCookie owner = new MockCookie("owner_token",
                created.getResponse().getCookie("owner_token").getValue());
        return new Session(id, owner);
    }

    private void selectPosition(Session session) {
        jdbc.update("UPDATE session SET brand_dna = ?::jsonb WHERE id = ?",
                "{\"position\":{\"category\":\"Study tools\",\"differentiator\":\"Deadline matching\"}}",
                UUID.fromString(session.id()));
    }

    private JsonNode runNoKey(Session session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/visual/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.palette.bg").isString())
                .andExpect(jsonPath("$.fonts.length()").value(2))
                .andExpect(jsonPath("$.shape").isString())
                .andExpect(jsonPath("$.logoSvg").value(containsString("<svg")))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void runWithoutPositionReturns409() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/visual/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(VisualService.NO_POSITION_MESSAGE));
    }

    @Test
    void runNoKeyPersistsVisualWithAaPaletteAndStageRun() throws Exception {
        Session session = createSession();
        selectPosition(session);

        JsonNode body = runNoKey(session);

        assertAa(body.path("palette"));
        assertTrue(body.path("logoSvg").asText().contains("<svg"));
        assertFalse(body.path("logoSvg").asText().contains("<script"));

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandDna.visual.palette.bg").isString())
                .andExpect(jsonPath("$.brandDna.visual.fonts.length()").value(2))
                .andExpect(jsonPath("$.brandDna.visual.logoSvg").value(containsString("<svg")))
                .andExpect(jsonPath("$.brandDna.visual.direction.moodWords").isArray());

        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S6'",
                Integer.class, UUID.fromString(session.id()));
        assertEqualsInt(1, runs);
    }

    @Test
    void runSseEmitsStageEventsWithVisualData() throws Exception {
        Session session = createSession();
        selectPosition(session);

        MvcResult mvc = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/visual/run")
                        .cookie(session.owner())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        String body = "";
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            body = mvc.getResponse().getContentAsString();
            if (body.contains("stage_completed")) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(body.contains("event:stage_started"), "missing stage_started: " + body);
        assertTrue(body.contains("\"stage\":\"visual\""), "missing visual stage: " + body);
        assertTrue(body.contains("event:progress"), "missing progress: " + body);
        assertTrue(body.contains("event:stage_completed"), "missing stage_completed: " + body);
        assertTrue(body.contains("\"logoSvg\""), "stage_completed missing logoSvg: " + body);
        assertTrue(body.contains("\"palette\""), "stage_completed missing palette: " + body);

        String contentType = mvc.getResponse().getContentType();
        assertTrue(contentType != null && contentType.contains("text/event-stream"),
                "content type: " + contentType);

        if (mvc.getRequest().isAsyncStarted()) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(mvc)).andReturn();
        }
    }

    @Test
    void patchTokensChangesAccentAndReturnsNewPalette() throws Exception {
        Session session = createSession();
        selectPosition(session);
        JsonNode before = runNoKey(session);
        String oldAccent = before.path("palette").path("accent").asText();

        MvcResult patched = mockMvc.perform(patch("/api/sessions/" + session.id() + "/visual/tokens")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accent\":\"#112233\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.palette.accent").value("#112233"))
                .andExpect(jsonPath("$.logoSvg").value(containsString("<svg")))
                .andReturn();

        JsonNode body = mapper.readTree(patched.getResponse().getContentAsString());
        assertTrue(body.path("palette").path("accent").asText().equals("#112233"));
        assertNotNull(oldAccent);

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(jsonPath("$.brandDna.visual.palette.accent").value("#112233"));
    }

    @Test
    void patchSeedHueRebuildsPaletteDeterministically() throws Exception {
        Session session = createSession();
        selectPosition(session);
        runNoKey(session);

        MvcResult first = mockMvc.perform(patch("/api/sessions/" + session.id() + "/visual/tokens")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seedHue\":40,\"saturation\":\"high\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.direction.seedHue").value(40))
                .andReturn();

        JsonNode body = mapper.readTree(first.getResponse().getContentAsString());
        assertAa(body.path("palette"));

        mockMvc.perform(patch("/api/sessions/" + session.id() + "/visual/tokens")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"seedHue\":40,\"saturation\":\"high\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.palette.accent")
                        .value(body.path("palette").path("accent").asText()));
    }

    @Test
    void patchBeforeRunReturns409() throws Exception {
        Session session = createSession();
        selectPosition(session);

        mockMvc.perform(patch("/api/sessions/" + session.id() + "/visual/tokens")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accent\":\"#112233\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(VisualService.NO_VISUAL_MESSAGE));
    }

    @Test
    void runRequiresOwnerCookie() throws Exception {
        Session session = createSession();
        selectPosition(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/visual/run")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    private static void assertAa(JsonNode palette) {
        String bg = palette.path("bg").asText();
        String surface = palette.path("surface").asText();
        String fg = palette.path("fg").asText();
        String muted = palette.path("muted").asText();
        String accent = palette.path("accent").asText();
        assertTrue(Wcag.meetsAa(fg, bg), "fg/bg");
        assertTrue(Wcag.meetsAa(fg, surface), "fg/surface");
        assertTrue(Wcag.meetsAa(muted, bg), "muted/bg");
        assertTrue(Wcag.meetsAa(muted, surface), "muted/surface");
        assertTrue(Wcag.meetsAa(accent, bg), "accent/bg");
        assertTrue(Wcag.meetsAa(accent, surface), "accent/surface");
    }

    private static void assertEqualsInt(int expected, Integer actual) {
        assertNotNull(actual);
        assertTrue(expected == actual, "expected " + expected + " got " + actual);
    }
}
