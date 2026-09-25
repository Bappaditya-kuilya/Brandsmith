package com.brandsmith.api.naming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "brandsmith.llm.api-key=",
        "brandsmith.cookies.secure=false",
        "brandsmith.rate-limit.create-capacity=200"
})
@AutoConfigureMockMvc
class NamingApiTest {

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
                .andReturn();
        String id = mapper.readTree(created.getResponse().getContentAsString()).get("id").asText();
        MockCookie owner = new MockCookie("owner_token",
                created.getResponse().getCookie("owner_token").getValue());
        return new Session(id, owner);
    }

    private void withPersonality(Session session) {
        jdbc.update("UPDATE session SET brand_dna = ?::jsonb WHERE id = ?",
                "{\"position\":{\"category\":\"Study tools\",\"differentiator\":\"Deadline matching\"},"
                        + "\"personality\":{\"traits\":[{\"name\":\"Direct\",\"behavior\":\"Lead with outcome\"}]}}",
                UUID.fromString(session.id()));
    }

    private void lockNaming(Session session) {
        jdbc.update("UPDATE stage_run SET locked = true WHERE session_id = ? AND stage = 'S4'",
                UUID.fromString(session.id()));
    }

    @Test
    void runWithoutPersonalityReturns409() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(NamingService.NO_PERSONALITY_MESSAGE));
    }

    @Test
    void runNoKeyReturnsNineNamesWithScoresAndPersists() throws Exception {
        Session session = createSession();
        withPersonality(session);

        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.territories.length()").value(3))
                .andExpect(jsonPath("$.names.length()").value(9))
                .andExpect(jsonPath("$.domainDisclaimer").isString())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        for (JsonNode territory : body.get("territories")) {
            assertTrue(territory.get("name").asText().length() > 0);
            assertTrue(territory.get("rationale").asText().length() > 0);
            assertTrue(territory.get("whyFitsPersonality").asText().length() > 0);
        }
        for (JsonNode name : body.get("names")) {
            int pron = name.get("pronounceability").asInt();
            int lex = name.get("lexiconScore").asInt();
            int emb = name.get("embeddingScore").asInt();
            double critic = name.get("criticScore").asDouble();
            double anti = name.get("antiGenericScore").asDouble();
            assertTrue(pron >= 0 && pron <= 100, "pronounceability " + pron);
            assertTrue(lex >= 0 && lex <= 100, "lexicon " + lex);
            assertTrue(emb >= 0 && emb <= 100, "embedding " + emb);
            assertTrue(critic >= 0 && critic <= 100, "critic " + critic);
            assertTrue(anti >= 0 && anti <= 100, "antiGeneric " + anti);
            assertTrue(name.get("length").asInt() > 0);
            assertTrue(name.get("attempts").size() >= 1);
            assertTrue(name.get("domainSignal").isNull());
        }

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandDna.naming.names.length()").value(9))
                .andExpect(jsonPath("$.brandDna.naming.territories.length()").value(3));

        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S4'",
                Integer.class, UUID.fromString(session.id()));
        assertEquals(1, runs);
    }

    @Test
    void runSseEmitsStageEventsWithNamingData() throws Exception {
        Session session = createSession();
        withPersonality(session);

        MvcResult mvc = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/run")
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
        assertTrue(body.contains("\"names\""), "stage_completed missing names: " + body);
        assertTrue(body.contains("\"stage\":\"naming\""), "missing naming stage: " + body);

        String contentType = mvc.getResponse().getContentType();
        assertTrue(contentType != null && contentType.contains("text/event-stream"),
                "content type: " + contentType);

        if (mvc.getRequest().isAsyncStarted()) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(mvc)).andReturn();
        }
    }

    @Test
    void selectPersistsIdentityName() throws Exception {
        Session session = createSession();
        withPersonality(session);

        MvcResult run = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn();
        String chosen = mapper.readTree(run.getResponse().getContentAsString())
                .get("names").get(2).get("name").asText();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/select")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameIndex\":2}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(chosen))
                .andExpect(jsonPath("$.nameIndex").value(2));

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandDna.identity.name").value(chosen));

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/select")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + chosen + "\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value(chosen));
    }

    @Test
    void selectBeforeRunReturns409() throws Exception {
        Session session = createSession();
        withPersonality(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/select")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nameIndex\":0}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(NamingService.NO_NAMES_MESSAGE));
    }

    @Test
    void regenerateWithNotePersistsFreshRun() throws Exception {
        Session session = createSession();
        withPersonality(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/regenerate")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"more technical, less playful\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.names.length()").value(9));

        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S4'",
                Integer.class, UUID.fromString(session.id()));
        assertEquals(2, runs);

        String input = jdbc.queryForObject(
                "SELECT input::text FROM stage_run WHERE session_id = ? AND stage = 'S4' ORDER BY version DESC LIMIT 1",
                String.class, UUID.fromString(session.id()));
        assertTrue(input.contains("more technical"), "stage_run input should store note: " + input);
    }

    @Test
    void regenerateWhenLockedReturns409() throws Exception {
        Session session = createSession();
        withPersonality(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        lockNaming(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/regenerate")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"try again\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(NamingService.LOCKED_MESSAGE));

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
    }

    @Test
    void runRequiresOwnerCookie() throws Exception {
        Session session = createSession();
        withPersonality(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/naming/run")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
