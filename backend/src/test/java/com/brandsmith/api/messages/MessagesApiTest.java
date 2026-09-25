package com.brandsmith.api.messages;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
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

import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "brandsmith.llm.api-key=",
        "brandsmith.cookies.secure=false",
        "brandsmith.rate-limit.create-capacity=200"
})
@AutoConfigureMockMvc
class MessagesApiTest {

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

    private void seedName(Session session) {
        jdbc.update("UPDATE session SET brand_dna = ?::jsonb WHERE id = ?",
                "{\"identity\":{\"name\":\"Loopform\"}}",
                UUID.fromString(session.id()));
    }

    @Test
    void runWithoutNameReturns409() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/messages/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(MessagesService.NO_NAME_MESSAGE));
    }

    @Test
    void runNoKeyPersistsAtLeastThreeTaglinesPitchAndHierarchy() throws Exception {
        Session session = createSession();
        seedName(session);

        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/messages/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taglines.length()").value(greaterThanOrEqualTo(3)))
                .andExpect(jsonPath("$.pitch").isString())
                .andExpect(jsonPath("$.hierarchy.primary").isString())
                .andExpect(jsonPath("$.hierarchy.secondary").isArray())
                .andExpect(jsonPath("$.hierarchy.proof").isArray())
                .andReturn();

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandDna.messages.taglines").isArray())
                .andExpect(jsonPath("$.brandDna.messages.pitch").isString())
                .andExpect(jsonPath("$.brandDna.messages.hierarchy.primary").isString());

        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S5'",
                Integer.class, UUID.fromString(session.id()));
        assertEquals(1, runs);
        assertTrue(result.getResponse().getContentAsString().contains("taglines"));
    }

    @Test
    void runSseEmitsStageEvents() throws Exception {
        Session session = createSession();
        seedName(session);

        MvcResult mvc = mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/messages/run")
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
        assertTrue(body.contains("\"taglines\""), "stage_completed missing taglines: " + body);
        assertTrue(body.contains("\"stage\":\"messages\""), "missing messages stage: " + body);

        String contentType = mvc.getResponse().getContentType();
        assertTrue(contentType != null && contentType.contains("text/event-stream"),
                "content type: " + contentType);

        if (mvc.getRequest().isAsyncStarted()) {
            mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                    .asyncDispatch(mvc)).andReturn();
        }
    }

    @Test
    void selectPersistsIdentityTaglineAndPitch() throws Exception {
        Session session = createSession();
        seedName(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/messages/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/messages/select")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"index\":0}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.selected").value(0))
                .andExpect(jsonPath("$.tagline").isString())
                .andExpect(jsonPath("$.pitch").isString());

        mockMvc.perform(get("/api/sessions/" + session.id()).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brandDna.identity.tagline").isString())
                .andExpect(jsonPath("$.brandDna.identity.pitch").isString())
                .andExpect(jsonPath("$.brandDna.identity.name").value("Loopform"))
                .andExpect(jsonPath("$.brandDna.messages.selected").value(0));
    }

    @Test
    void selectWithoutRunningMessagesReturns409() throws Exception {
        Session session = createSession();
        seedName(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/messages/select")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"index\":0}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(MessagesService.NO_MESSAGES_MESSAGE));
    }

    @Test
    void regenerateWithNoteRecordsSecondRun() throws Exception {
        Session session = createSession();
        seedName(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/messages/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/messages/regenerate")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"punchier\"}")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.taglines").isArray());

        Integer runs = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = 'S5'",
                Integer.class, UUID.fromString(session.id()));
        assertEquals(2, runs);

        String input = jdbc.queryForObject(
                "SELECT input::text FROM stage_run WHERE session_id = ? AND stage = 'S5' ORDER BY version DESC LIMIT 1",
                String.class, UUID.fromString(session.id()));
        assertTrue(input.contains("punchier"), "stage_run input should store regenerate note: " + input);
    }

    @Test
    void runRequiresOwnerCookie() throws Exception {
        Session session = createSession();
        seedName(session);

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/messages/run")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }
}
