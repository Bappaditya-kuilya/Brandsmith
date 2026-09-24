package com.brandsmith.api.interview;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
class InterviewApiTest {

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
        String body = created.getResponse().getContentAsString();
        String id = mapper.readTree(body).get("id").asText();
        MockCookie owner = new MockCookie("owner_token",
                created.getResponse().getCookie("owner_token").getValue());
        return new Session(id, owner);
    }

    private JsonNode postAnswer(Session session, String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/sessions/" + session.id() + "/interview/answer")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.briefState").exists())
                .andExpect(jsonPath("$.overallConfidence").exists())
                .andExpect(jsonPath("$.questionCount").exists())
                .andExpect(jsonPath("$.done").exists())
                .andReturn();
        return mapper.readTree(result.getResponse().getContentAsString());
    }

    @Test
    void interviewRequiresOwnerCookie() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/interview/answer")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void startReturnsFirstQuestionThenAdvances() throws Exception {
        Session session = createSession();

        JsonNode first = postAnswer(session, "{}");
        assertTrue(first.get("nextQuestion").asText().length() > 0);
        assertTrue(first.get("nextQuestion").asText().startsWith("Think of the last person"));
        assertEquals("target_user", first.get("fieldId").asText());
        assertEquals(1, first.get("questionCount").asInt());
        assertFalse(first.get("done").asBoolean());

        JsonNode second = postAnswer(session, "{\"answer\":\"They texted the group chat the night before.\"}");
        assertEquals("problem_alternative", second.get("fieldId").asText());
        assertEquals(2, second.get("questionCount").asInt());
        assertEquals("They texted the group chat the night before.",
                second.at("/briefState/fields/target_user/value").asText());
        assertFalse(second.get("done").asBoolean());
    }

    @Test
    void skipMarksAssumptionAndLowConfidence() throws Exception {
        Session session = createSession();
        postAnswer(session, "{}");

        JsonNode afterSkip = postAnswer(session, "{\"skip\":true}");
        JsonNode target = afterSkip.at("/briefState/fields/target_user");
        assertTrue(target.get("assumption").asBoolean());
        assertEquals(BriefState.SKIP_CONFIDENCE, target.get("confidence").asDouble(), 1e-9);
        assertTrue(target.get("value").isNull());
        assertEquals("problem_alternative", afterSkip.get("fieldId").asText());
        assertFalse(afterSkip.get("done").asBoolean());
    }

    @Test
    void patchBriefSetsValueAndHighConfidence() throws Exception {
        Session session = createSession();

        mockMvc.perform(patch("/api/sessions/" + session.id() + "/brief")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{\"target_user\":{\"value\":\"Night-shift nurses on break\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fields.target_user.value").value("Night-shift nurses on break"))
                .andExpect(jsonPath("$.fields.target_user.confidence").value(greaterThanOrEqualTo(0.9)))
                .andExpect(jsonPath("$.fields.target_user.assumption").value(false));
    }

    @Test
    void patchUnknownFieldIsRejected() throws Exception {
        Session session = createSession();

        mockMvc.perform(patch("/api/sessions/" + session.id() + "/brief")
                        .cookie(session.owner())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fields\":{\"bogus\":{\"value\":\"x\"}}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void vagueIdeaInterviewGetsAtLeastFourQuestions() throws Exception {
        Session session = createSession();

        int questions = 0;
        JsonNode response = postAnswer(session, "{}");
        while (!response.get("done").asBoolean() && questions < 12) {
            assertTrue(response.get("nextQuestion").asText().length() > 0);
            questions++;
            response = postAnswer(session, "{\"answer\":\"They opened three tabs and gave up.\"}");
        }

        assertTrue(questions >= 4, "expected >= 4 questions, got " + questions);
        assertTrue(response.get("done").asBoolean());
        assertTrue(response.get("nextQuestion").isNull());
    }

    @Test
    void patchedFullBriefStopsInterviewEarly() throws Exception {
        Session session = createSession();
        String[] fieldIds = {"target_user", "problem_alternative", "desired_outcome", "category_competitors",
                "founder_goal", "constraints", "tone_hints", "proof_advantage"};
        for (String fieldId : fieldIds) {
            mockMvc.perform(patch("/api/sessions/" + session.id() + "/brief")
                            .cookie(session.owner())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fields\":{\"" + fieldId + "\":{\"value\":\"Seed value for " + fieldId + "\"}}}"))
                    .andExpect(status().isOk());
        }

        JsonNode response = postAnswer(session, "{}");
        assertTrue(response.get("done").asBoolean());
        assertTrue(response.get("nextQuestion").isNull());
        assertEquals(0, response.get("questionCount").asInt());
        assertTrue(response.get("overallConfidence").asDouble() >= 0.75);
    }
}
