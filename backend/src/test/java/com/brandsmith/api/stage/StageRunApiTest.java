package com.brandsmith.api.stage;

import static org.hamcrest.Matchers.hasItems;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class StageRunApiTest {

    private static final String VALID_IDEA = "A study group app that matches students by shared assignment deadlines";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper mapper;

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

    @Test
    void sessionCreateRecordsS0StageRun() throws Exception {
        Session session = createSession();

        mockMvc.perform(get("/api/sessions/" + session.id() + "/stage-runs").cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").exists())
                .andExpect(jsonPath("$[0].stage").value("S0"))
                .andExpect(jsonPath("$[0].status").value("degraded"))
                .andExpect(jsonPath("$[0].model").value("rules"))
                .andExpect(jsonPath("$[0].latencyMs").value(0))
                .andExpect(jsonPath("$[0].tokensIn").value(0))
                .andExpect(jsonPath("$[0].tokensOut").value(0))
                .andExpect(jsonPath("$[0].stale").value(false))
                .andExpect(jsonPath("$[0].locked").value(false));
    }

    @Test
    void stageRunsRequireOwnerCookie() throws Exception {
        Session session = createSession();

        mockMvc.perform(get("/api/sessions/" + session.id() + "/stage-runs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void interviewAnswerRecordsS1StageRun() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/interview/answer")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/sessions/" + session.id() + "/stage-runs").cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[*].stage", hasItems("S0", "S1")))
                .andExpect(jsonPath("$[1].stage").value("S1"))
                .andExpect(jsonPath("$[1].status").value("degraded"))
                .andExpect(jsonPath("$[1].model").value("template"))
                .andExpect(jsonPath("$[1].locked").value(false));
    }

    @Test
    void lockTogglesLockedFlag() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/S0/lock")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stage").value("S0"))
                .andExpect(jsonPath("$.locked").value(true));

        mockMvc.perform(get("/api/sessions/" + session.id() + "/stage-runs").cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locked").value(true));

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/S0/lock")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.locked").value(false));

        mockMvc.perform(get("/api/sessions/" + session.id() + "/stage-runs").cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].locked").value(false));
    }

    @Test
    void lockRequiresOwnerCookie() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/S0/lock")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":true}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void lockUnknownStageIsRejected() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/bogus/lock")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"locked\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void lockWithoutLockedFlagIsRejected() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/S0/lock")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }
}
