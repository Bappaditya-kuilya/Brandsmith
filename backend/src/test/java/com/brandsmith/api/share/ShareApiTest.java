package com.brandsmith.api.share;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
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

import com.brandsmith.api.session.OwnerAuth;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@SpringBootTest(properties = {
        "brandsmith.llm.api-key=",
        "brandsmith.cookies.secure=false",
        "brandsmith.rate-limit.create-capacity=200"
})
@AutoConfigureMockMvc
class ShareApiTest {

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
        jdbc.update("UPDATE session SET brand_dna = ?::jsonb WHERE id = ?",
                SEEDED_DNA, UUID.fromString(id));
        return new Session(id, owner);
    }

    private String createShare(Session session) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/sessions/" + session.id() + "/share")
                        .cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.expiresAt").isNotEmpty())
                .andReturn();
        JsonNode body = mapper.readTree(created.getResponse().getContentAsString());
        return body.get("token").asText();
    }

    @Test
    void shareStoresOnlySha256HashOfToken() throws Exception {
        Session session = createSession();
        String token = createShare(session);

        String stored = jdbc.queryForObject(
                "SELECT token_hash FROM share WHERE session_id = ? ORDER BY expires_at DESC LIMIT 1",
                String.class, UUID.fromString(session.id()));
        assertNotNull(stored);
        assertEquals(OwnerAuth.sha256Hex(token), stored, "share must store SHA-256 of token");
        assertNotEquals(token, stored);

        Integer rawHits = jdbc.queryForObject(
                "SELECT COUNT(*) FROM share WHERE token_hash = ?",
                Integer.class, token);
        assertEquals(0, rawHits, "raw token must never be stored");
    }

    @Test
    void shareExpiresInThirtyDays() throws Exception {
        Session session = createSession();
        createShare(session);

        Timestamp expiresAt = jdbc.queryForObject(
                "SELECT expires_at FROM share WHERE session_id = ? LIMIT 1",
                Timestamp.class, UUID.fromString(session.id()));
        long deltaMs = expiresAt.getTime() - System.currentTimeMillis();
        long expectedMs = ShareService.TTL_DAYS * 86_400_000L;
        assertTrue(Math.abs(deltaMs - expectedMs) < 60_000L,
                "expiresAt should be ~30 days out, deltaMs=" + deltaMs);
    }

    @Test
    void publicGetReturnsKitWithoutOwnerCookie() throws Exception {
        Session session = createSession();
        String token = createShare(session);

        mockMvc.perform(get("/api/share/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.name").value("DeadlineDuo"))
                .andExpect(jsonPath("$.identity.tagline").value("Never miss a team deadline again"))
                .andExpect(jsonPath("$.position.category").value("Study tools"));
    }

    @Test
    void publicGetIgnoresOwnerCookieAndStillWorks() throws Exception {
        Session session = createSession();
        String token = createShare(session);

        mockMvc.perform(get("/api/share/" + token).cookie(session.owner()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity.name").value("DeadlineDuo"));
    }

    @Test
    void unknownTokenReturns404() throws Exception {
        mockMvc.perform(get("/api/share/definitely-not-a-real-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ShareService.NOT_FOUND_MESSAGE));
    }

    @Test
    void expiredTokenReturns404() throws Exception {
        Session session = createSession();
        String expiredToken = "expired-share-token-" + UUID.randomUUID();
        jdbc.update("INSERT INTO share (token_hash, session_id, expires_at) VALUES (?, ?, ?)",
                OwnerAuth.sha256Hex(expiredToken), UUID.fromString(session.id()),
                Timestamp.from(Instant.now().minusSeconds(60)));

        mockMvc.perform(get("/api/share/" + expiredToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(ShareService.NOT_FOUND_MESSAGE));
    }

    @Test
    void shareRequiresOwnerCookie() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + session.id() + "/share"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void shareForMissingSessionReturns404() throws Exception {
        Session session = createSession();

        mockMvc.perform(post("/api/sessions/" + UUID.randomUUID() + "/share")
                        .cookie(session.owner()))
                .andExpect(status().isNotFound());
    }

    @Test
    void publicGetAfterShareCoversLaunchAssets() throws Exception {
        Session session = createSession();
        mockMvc.perform(post("/api/sessions/" + session.id() + "/stages/launch/run")
                        .cookie(session.owner())
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk());
        String token = createShare(session);

        mockMvc.perform(get("/api/share/" + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assets.hero.headline").isNotEmpty())
                .andExpect(jsonPath("$.assets.posts.length()").value(3));
    }
}
