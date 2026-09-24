package com.brandsmith.api.personality;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
class PersonalityApiTest {

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
        String body = created.getResponse().getContentAsString();
        String id = mapper.readTree(body).get("id").asText();
        MockCookie owner = new MockCookie("owner_token",
                created.getResponse().getCookie("owner_token").getValue());
        return new Session(id, owner);
    }

    private void selectPosition(Session session) {
        jdbc.update("UPDATE session SET brand_dna = ?::jsonb WHERE id = ?",
                "{\"position\":{\"category\":\"Study tools\",\"differentiator\":\"Deadline matching\"}}",
                UUID.fromString(session.id()));
    }

    private void lockPersonality(Session session) {
        jdbc.update("UPDATE stage_run SET locked = true WHERE session_id = ? AND stage = 'S3'",
                UUID.fromString(session.id()));
    }

    @Test
    void runWithoutPositionReturns409() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PersonalityService.NO_POSITION_MESSAGE));
    }

    @Test
    void runNoKeyPersistsPersonalityAndVoiceToBrandDna() throws Exception {
        Session session = createSession();
        selectPosition(session);

        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traits").isArray())
                .andExpect(jsonPath("$.traits.length()").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.voice.formality").isNumber())
                .andExpect(jsonPath("$.voice.signatureMoves.length()").value(3))
                .andExpect(jsonPath("$.avoidList").isArray())
                .andReturn();

        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertTrue(body.get("traits").size() <= 5);
        for (JsonNode trait : body.get("traits")) {
            assertTrue(trait.get("whyFits").asText().strip().length() > 0);
            assertTrue(trait.get("whyFits").asText().contains("\""),
                    "whyFits must quote brief: " + trait.get("whyFits").asText());
        }
        int formality = body.at("/voice/formality").asInt();
        assertTrue(formality >= 1 && formality <= 5);

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandDna.personality.traits").isArray())
                .andExpect(jsonPath("$.brandDna.personality.avoidList").isArray())
                .andExpect(jsonPath("$.brandDna.voice.formality").value(formality));

        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S3'",
                Integer.class, UUID.fromString(session.id()));
        assertEquals(1, runs);

        Map<String, Object> dna = jdbc.queryForMap(
                "SELECT brand_dna FROM session WHERE id = ?", UUID.fromString(session.id()));
        assertNotNull(dna.get("brand_dna"));
        assertTrue(dna.get("brand_dna").toString().contains("personality"));
        assertTrue(dna.get("brand_dna").toString().contains("voice"));
    }

    @Test
    void runSseEmitsStageEventsWithPersonalityData() throws Exception {
        Session session = createSession();
        selectPosition(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/run")
                        .cookie(session.owner())
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("event: stage_started")))
                .andExpect(content().string(containsString("\"stage\":\"personality\"")))
                .andExpect(content().string(containsString("event: stage_completed")))
                .andExpect(content().string(containsString("\"traits\""))
                );
    }

    @Test
    void regenerateWithNotePersistsFreshRun() throws Exception {
        Session session = createSession();
        selectPosition(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/regenerate")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"less playful\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.traits").isArray());

        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S3'",
                Integer.class, UUID.fromString(session.id()));
        assertEquals(2, runs);

        String input = jdbc.queryForObject(
                "SELECT input::text FROM stage_run WHERE session_id = ? AND stage = 'S3' ORDER BY version DESC LIMIT 1",
                String.class, UUID.fromString(session.id()));
        assertTrue(input.contains("less playful"), "stage_run input should store regenerate note: " + input);
    }

    @Test
    void regenerateWhenLockedReturns409() throws Exception {
        Session session = createSession();
        selectPosition(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        lockPersonality(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/regenerate")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"try again\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(PersonalityService.LOCKED_MESSAGE));

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict());
    }

    @Test
    void runRequiresOwnerCookie() throws Exception {
        Session session = createSession();
        selectPosition(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/run")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void regenerateNoteLongerThan300IsRejected() throws Exception {
        Session session = createSession();
        selectPosition(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/personality/regenerate")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"" + "x".repeat(301) + "\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
    }
}
