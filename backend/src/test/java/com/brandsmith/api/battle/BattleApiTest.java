package com.brandsmith.api.battle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockCookie;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "brandsmith.llm.api-key=",
        "brandsmith.cookies.secure=false",
        "brandsmith.rate-limit.create-capacity=200"
})
@AutoConfigureMockMvc
class BattleApiTest {

    private static final String VALID_IDEA = "A study group app that matches students by shared assignment deadlines";
    private static final String[] FIELDS = {"target_user", "problem_alternative", "desired_outcome",
            "category_competitors", "founder_goal", "constraints", "tone_hints", "proof_advantage"};

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
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        MockCookie owner = new MockCookie("owner_token",
                created.getResponse().getCookie("owner_token").getValue());
        return new Session(id, owner);
    }

    private Session sessionWithFinishedBrief() throws Exception {
        Session session = createSession();
        for (String field : FIELDS) {
            mockMvc.perform(patchBrief(session, field))
                    .andExpect(status().isOk());
        }
        return session;
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder patchBrief(
            Session session, String field) {
        return org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .patch("/api/sessions/" + session.id() + "/brief")
                .cookie(session.owner())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"fields\":{\"" + field + "\":{\"value\":\"Seed value for " + field + "\"}}}");
    }

    private JsonNode runSync(Session session) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id()
                        + "/stages/position/run?sync=1")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions.length()").value(3))
                .andExpect(jsonPath("$.judge.scores.length()").value(3))
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void syncRunReturnsThreeDistinctPositionsWithJudge() throws Exception {
        Session session = sessionWithFinishedBrief();

        JsonNode body = runSync(session);

        Set<String> mandates = new HashSet<>();
        Set<String> frames = new HashSet<>();
        for (JsonNode position : body.get("positions")) {
            mandates.add(position.get("mandate").asText());
            frames.add(BattleService.normalize(position.get("frameOfReference").asText()));
            assertTrue(position.get("category").asText().length() > 0);
            assertTrue(position.get("differentiator").asText().length() > 0);
            assertTrue(position.get("valueProposition").asText().length() > 0);
            assertTrue(position.get("proofPoints").size() >= 1);
        }
        assertEquals(Set.of("native", "contrarian", "emotional"), mandates);
        assertEquals(3, frames.size(), "category frames must be pairwise distinct");
        assertTrue(body.get("selected").isNull());

        JsonNode scores = body.at("/judge/scores");
        for (JsonNode score : scores) {
            for (String criterion : List.of("audienceFit", "distinctiveness", "credibility",
                    "memorability", "feasibility")) {
                int value = score.get(criterion).asInt();
                assertTrue(value >= 1 && value <= 5, criterion + " out of range: " + value);
            }
            assertTrue(score.get("explanation").asText().length() > 0);
        }

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandDna.battle.positions.length()").value(3))
                .andExpect(jsonPath("$.brandDna.battle.judge.scores.length()").value(3))
                .andExpect(jsonPath("$.brandDna.battle.selected").value(org.hamcrest.Matchers.nullValue()));

        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S2'",
                Integer.class, UUID.fromString(session.id()));
        assertTrue(runs >= 4, "expected at least 4 stage_run rows, got " + runs);
        List<Map<String, Object>> models = jdbc.queryForList(
                "SELECT DISTINCT model, status FROM stage_run WHERE session_id = ? AND stage = 'S2'",
                UUID.fromString(session.id()));
        assertEquals(1, models.size());
        assertEquals("template", models.get(0).get("model"));
        assertEquals("ok", models.get(0).get("status"));
    }

    @Test
    void runBeforeInterviewDoneReturns409() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/position/run?sync=1")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(BattleService.BRIEF_NOT_DONE_MESSAGE));
    }

    @Test
    void runRequiresOwnerCookie() throws Exception {
        Session session = sessionWithFinishedBrief();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/position/run?sync=1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void selectPersistsEditedPositionIntoBrandDna() throws Exception {
        Session session = sessionWithFinishedBrief();
        runSync(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/position/select")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"index\":1,\"edits\":{\"valueProposition\":\"Edited claim.\","
                                + "\"proofPoints\":[\"proof one\"]}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value(1))
                .andExpect(jsonPath("$.position.valueProposition").value("Edited claim."))
                .andExpect(jsonPath("$.position.mandate").value("contrarian"));

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandDna.position.valueProposition").value("Edited claim."))
                .andExpect(jsonPath("$.brandDna.position.mandate").value("contrarian"))
                .andExpect(jsonPath("$.brandDna.battle.selected").value(1));
    }

    @Test
    void regenerateWithNoteReturnsFreshBattle() throws Exception {
        Session session = sessionWithFinishedBrief();
        runSync(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/position/regenerate")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"less playful, more technical\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.positions.length()").value(3))
                .andExpect(jsonPath("$.judge.scores.length()").value(3))
                .andExpect(jsonPath("$.selected").value(org.hamcrest.Matchers.nullValue()));

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(jsonPath("$.brandDna.position").doesNotExist())
                .andExpect(jsonPath("$.brandDna.battle.selected")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void sseRunEmitsStageEvents() throws Exception {
        Session session = sessionWithFinishedBrief();

        MvcResult mvc = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/position/run")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON))
                .andReturn();

        assertTrue(mvc.getResponse().getContentType().contains("text/event-stream"));

        String body = "";
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            body = mvc.getResponse().getContentAsString();
            if (body.contains("stage_completed")) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(body.contains("stage_started"), "missing stage_started: " + body);
        assertTrue(body.contains("progress"), "missing progress: " + body);
        assertTrue(body.contains("stage_completed"), "missing stage_completed: " + body);
        assertTrue(body.contains("\"positions\""), "stage_completed missing positions: " + body);

        if (mvc.getRequest().isAsyncStarted()) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(mvc)).andReturn();
        }
    }
}
