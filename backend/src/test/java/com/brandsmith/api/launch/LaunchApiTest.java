package com.brandsmith.api.launch;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
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
class LaunchApiTest {

    private static final String VALID_IDEA = "A study group app that matches students by shared assignment deadlines";
    private static final String SEEDED_DNA = """
            {"position":{"category":"Study tools","differentiator":"Deadline matching"},
             "identity":{"name":"DeadlineDuo","tagline":"Never miss a team deadline again"}}""";

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
                SEEDED_DNA, UUID.fromString(session.id()));
    }

    @Test
    void runWithoutIdentityReturns409() throws Exception {
        Session session = createSession();
        jdbc.update("UPDATE session SET brand_dna = ?::jsonb WHERE id = ?",
                "{\"position\":{\"category\":\"Study tools\"}}", UUID.fromString(session.id()));

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/launch/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(LaunchService.DNA_NOT_READY_MESSAGE));
    }

    @Test
    void runWithoutPositionReturns409() throws Exception {
        Session session = createSession();
        jdbc.update("UPDATE session SET brand_dna = ?::jsonb WHERE id = ?",
                "{\"identity\":{\"name\":\"DeadlineDuo\",\"tagline\":\"Tagline here\"}}",
                UUID.fromString(session.id()));

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/launch/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(LaunchService.DNA_NOT_READY_MESSAGE));
    }

    @Test
    void runNoKeyPersistsAssetsAndStageRun() throws Exception {
        Session session = createSession();
        seedDna(session);

        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/launch/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hero.headline").isNotEmpty())
                .andExpect(jsonPath("$.hero.subhead").isNotEmpty())
                .andExpect(jsonPath("$.hero.cta").isNotEmpty())
                .andExpect(jsonPath("$.pitch").isNotEmpty())
                .andExpect(jsonPath("$.posts.length()").value(3))
                .andExpect(jsonPath("$.bioShort").isNotEmpty())
                .andExpect(jsonPath("$.bioLong").isNotEmpty())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.at("/posts/0").asText().length() > 0);

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandDna.assets.hero.headline").isNotEmpty())
                .andExpect(jsonPath("$.brandDna.assets.posts.length()").value(3))
                .andExpect(jsonPath("$.brandDna.assets.bioShort").isNotEmpty());

        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S8'",
                Integer.class, UUID.fromString(session.id()));
        assertEquals(1, runs);
        String model = jdbc.queryForObject(
                "SELECT model FROM stage_run WHERE session_id = ? AND stage = 'S8'",
                String.class, UUID.fromString(session.id()));
        assertEquals("template", model);
    }

    @Test
    void runSseEmitsStageEvents() throws Exception {
        Session session = createSession();
        seedDna(session);

        MvcResult mvc = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/launch/run")
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
        assertTrue(body.contains("\"posts\""), "stage_completed missing posts: " + body);

        String contentType = mvc.getResponse().getContentType();
        assertTrue(contentType != null && contentType.contains("text/event-stream"),
                "content type: " + contentType);

        if (mvc.getRequest().isAsyncStarted()) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(mvc)).andReturn();
        }
    }

    @Test
    void runRequiresOwnerCookie() throws Exception {
        Session session = createSession();
        seedDna(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/launch/run")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportMdDownloadsKitWithNameAndTagline() throws Exception {
        Session session = createSession();
        seedDna(session);

        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id() + "/export?format=md")
                        .cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment; filename=\"brand-kit.md\"")))
                .andExpect(content().string(containsString("# DeadlineDuo")))
                .andExpect(content().string(containsString("Never miss a team deadline again")))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertTrue(body.contains("DeadlineDuo"));
        assertTrue(body.contains("Never miss a team deadline again"));
        assertTrue(body.contains("## Position"));
        assertNotNull(result.getResponse().getContentType());
        assertTrue(result.getResponse().getContentType().contains("markdown"));
    }

    @Test
    void exportJsonReturnsBrandDnaDownload() throws Exception {
        Session session = createSession();
        seedDna(session);

        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id() + "/export?format=json")
                        .cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("filename=\"brand-kit.json\"")))
                .andExpect(jsonPath("$.identity.name").value("DeadlineDuo"))
                .andExpect(jsonPath("$.identity.tagline").value("Never miss a team deadline again"))
                .andExpect(jsonPath("$.position.category").value("Study tools"))
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertEquals("DeadlineDuo", body.at("/identity/name").asText());
    }

    @Test
    void exportRejectsUnknownFormat() throws Exception {
        Session session = createSession();
        seedDna(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/export?format=pdf")
                        .cookie(session.owner()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("md or json")));
    }

    @Test
    void exportRequiresOwnerCookie() throws Exception {
        Session session = createSession();
        seedDna(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/export?format=md"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportMdIncludesLaunchAssetsAfterRun() throws Exception {
        Session session = createSession();
        seedDna(session);
        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/launch/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/" + session.id() + "/export?format=md")
                        .cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("## Launch Assets")))
                .andExpect(content().string(containsString("### Hero")))
                .andExpect(content().string(containsString("### Posts")));
    }

    @Test
    void assetsPersistedInBrandDnaJsonb() throws Exception {
        Session session = createSession();
        seedDna(session);
        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/launch/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT brand_dna::text AS dna FROM session WHERE id = ?", UUID.fromString(session.id()));
        String dna = String.valueOf(row.get("dna"));
        assertTrue(dna.contains("\"assets\""), "brand_dna should persist assets: " + dna);
        assertTrue(dna.contains("\"hero\""));
        assertTrue(dna.contains("\"posts\""));
    }

    @Test
    void stageRunRecordsInputIdentity() throws Exception {
        Session session = createSession();
        seedDna(session);
        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/launch/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.posts.length()").value(greaterThanOrEqualTo(3)));

        String input = jdbc.queryForObject(
                "SELECT input::text FROM stage_run WHERE session_id = ? AND stage = 'S8'",
                String.class, UUID.fromString(session.id()));
        assertTrue(input.contains("DeadlineDuo"), "stage_run input should include identity: " + input);
    }
}
