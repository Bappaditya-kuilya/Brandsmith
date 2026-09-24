package com.brandsmith.api.session;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

import com.brandsmith.api.stage.S0IntakeService;

@SpringBootTest(properties = {
        "brandsmith.llm.api-key=",
        "brandsmith.cookies.secure=false",
        "brandsmith.rate-limit.create-capacity=200"
})
@AutoConfigureMockMvc
class SessionApiTest {

    private static final String VALID_IDEA = "A study group app that matches students by shared assignment deadlines";
    private static final String HARMFUL_IDEA = "I want a brand that sells meth and counterfeit money to students";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void createRejectsIdeaUnderTenChars() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"too short\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void createRejectsIdeaOverThousandChars() throws Exception {
        String idea = "x".repeat(1001);
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"" + idea + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    void harmfulIdeaIsRefusedWithPlainMessage() throws Exception {
        mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"" + HARMFUL_IDEA + "\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.message").value(S0IntakeService.REFUSAL_MESSAGE));
    }

    @Test
    void createGetDeleteRoundTripWithOwnerCookie() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/sessions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"" + VALID_IDEA + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.idea").value(VALID_IDEA))
                .andExpect(cookie().exists("owner_token"))
                .andExpect(cookie().httpOnly("owner_token", true))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Lax")))
                .andReturn();

        String id = created.getResponse().getContentAsString().replaceAll(".*\"id\":\"([^\"]+)\".*", "$1");
        MockCookie owner = new MockCookie("owner_token",
                created.getResponse().getCookie("owner_token").getValue());

        mockMvc.perform(get("/api/sessions/" + id).cookie(owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.idea").value(VALID_IDEA))
                .andExpect(jsonPath("$.briefState.clean_idea").exists());

        mockMvc.perform(get("/api/sessions/" + id))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(delete("/api/sessions/" + id).cookie(owner))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/sessions/" + id).cookie(owner))
                .andExpect(status().isNotFound());
    }
}
