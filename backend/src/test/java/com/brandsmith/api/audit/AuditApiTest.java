package com.brandsmith.api.audit;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "brandsmith.llm.api-key=",
        "brandsmith.cookies.secure=false",
        "brandsmith.rate-limit.create-capacity=200"
})
@AutoConfigureMockMvc
class AuditApiTest {

    private static final String VALID_IDEA = "A study group app that matches students by shared assignment deadlines";

    private static final String DNA_JSON = """
            {
              "brief": {"audience": "Students cramming deadlines"},
              "position": {"category": "Study tools", "differentiator": "Deadline matching"},
              "personality": {
                "traits": [{"name": "Direct", "whyFits": "brief says clarity", "behavior": "lead with outcome", "neverBecome": "Cruel"}],
                "avoidList": ["Cruel"]
              },
              "voice": {"formality": 3, "sentenceWords": [6, 16], "humorLevel": "dry",
                        "bannedWords": ["revolutionize", "seamless"], "signatureMoves": ["open with the problem"]},
              "identity": {"name": "Deadline Club", "tagline": "Group up before midterms",
                           "pitch": "A study group app that matches students by shared assignment deadlines."},
              "visual": {"fonts": ["display-sans", "body-sans"],
                         "palette": {"bg": "#ffffff", "surface": "#f4f4f5", "accent": "#1d4ed8", "fg": "#111111", "muted": "#6b7280"}},
              "assets": {"hero": "Find your group before the deadline hits.",
                         "posts": ["Match by assignment, not by vibe."],
                         "bio": "Built by two students who missed the deadline."}
            }""";

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

    private void seedDna(Session session) {
        jdbc.update("UPDATE session SET brand_dna = ?::jsonb WHERE id = ?",
                DNA_JSON, UUID.fromString(session.id()));
    }

    @Test
    void auditNoKeyReturnsFiveDimensionsAndPersistsScores() throws Exception {
        Session session = createSession();
        seedDna(session);

        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id() + "/audit")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.dimensions.length()").value(5))
                .andExpect(jsonPath("$.result.overall").value(greaterThanOrEqualTo(80)))
                .andExpect(jsonPath("$.result.conflicts").isArray())
                .andExpect(jsonPath("$.result.reviseInstructions").exists())
                .andExpect(jsonPath("$.diffs").isArray())
                .andExpect(jsonPath("$.reviseRounds").value(0))
                .andExpect(jsonPath("$.degraded").value(true))
                .andExpect(jsonPath("$.result.dimensions[0].dimension").value("personalityFit"))
                .andExpect(jsonPath("$.result.dimensions[0].weight").value(25))
                .andExpect(jsonPath("$.result.dimensions[1].dimension").value("voiceCompliance"))
                .andExpect(jsonPath("$.result.dimensions[1].weight").value(25))
                .andExpect(jsonPath("$.result.dimensions[2].dimension").value("audienceFit"))
                .andExpect(jsonPath("$.result.dimensions[2].weight").value(20))
                .andExpect(jsonPath("$.result.dimensions[3].dimension").value("positioningAlignment"))
                .andExpect(jsonPath("$.result.dimensions[3].weight").value(20))
                .andExpect(jsonPath("$.result.dimensions[4].dimension").value("visualCoherence"))
                .andExpect(jsonPath("$.result.dimensions[4].weight").value(10))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        int overall = mapper.readTree(body).at("/result/overall").asInt();

        Integer stageRuns = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S7'",
                Integer.class, UUID.fromString(session.id()));
        assertEquals(1, stageRuns);

        String status = jdbc.queryForObject(
                "SELECT status FROM stage_run WHERE session_id = ? AND stage = 'S7'",
                String.class, UUID.fromString(session.id()));
        assertEquals("degraded", status);

        Integer scoreRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM score s JOIN stage_run r ON s.stage_run_id = r.id "
                        + "WHERE r.session_id = ? AND r.stage = 'S7'",
                Integer.class, UUID.fromString(session.id()));
        assertEquals(6, scoreRows);

        Double overallRow = jdbc.queryForObject(
                "SELECT s.value FROM score s JOIN stage_run r ON s.stage_run_id = r.id "
                        + "WHERE r.session_id = ? AND r.stage = 'S7' AND s.kind = 'overall'",
                Double.class, UUID.fromString(session.id()));
        assertEquals((double) overall, overallRow, 0.001);
    }

    @Test
    void auditSseEmitsStageEvents() throws Exception {
        Session session = createSession();
        seedDna(session);

        MvcResult mvc = mockMvc.perform(post("/api/sessions/" + session.id() + "/audit")
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
        assertTrue(body.contains("stage_started"), "missing stage_started: " + body);
        assertTrue(body.contains("progress"), "missing progress: " + body);
        assertTrue(body.contains("stage_completed"), "missing stage_completed: " + body);
        assertTrue(body.contains("\"stage\":\"S7\""), "missing stage S7: " + body);
        assertTrue(body.contains("voiceCompliance"),
                "stage_completed missing audit data: " + body);

        String contentType = mvc.getResponse().getContentType();
        assertTrue(contentType != null && contentType.contains("text/event-stream"),
                "content type: " + contentType);

        if (mvc.getRequest().isAsyncStarted()) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(mvc)).andReturn();
        }
    }

    @Test
    void auditRequiresOwnerCookie() throws Exception {
        Session session = createSession();
        seedDna(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/audit")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void driftCheckFlagsBannedWordAndRewritesIt() throws Exception {
        Session session = createSession();
        seedDna(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/drift-check")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"We will revolutionize your study routine!!\",\"assetType\":\"post\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetType").value("post"))
                .andExpect(jsonPath("$.pass").value(false))
                .andExpect(jsonPath("$.dimensions.length()").value(5))
                .andExpect(jsonPath("$.dimensions[1].dimension").value("voiceCompliance"))
                .andExpect(jsonPath("$.dimensions[1].pass").value(false))
                .andExpect(jsonPath("$.dimensions[1].score").value(50))
                .andExpect(jsonPath("$.flaggedPhrases", hasItem("revolutionize")))
                .andExpect(jsonPath("$.rewrite", not(containsString("revolutionize"))));
    }

    @Test
    void driftCheckCleanCopyPasses() throws Exception {
        Session session = createSession();
        seedDna(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/drift-check")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Match by shared assignment before the deadline.\",\"assetType\":\"post\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(true))
                .andExpect(jsonPath("$.flaggedPhrases").isEmpty())
                .andExpect(jsonPath("$.rewrite").value("Match by shared assignment before the deadline."));
    }

    @Test
    void driftCheckRejectsUnknownAssetType() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/drift-check")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Some copy\",\"assetType\":\"billboard\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void driftCheckRequiresOwnerCookie() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/drift-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"Some copy\",\"assetType\":\"post\"}"))
                .andExpect(status().isUnauthorized());
    }
}
