package com.brandsmith.api.share;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.session.OwnerAuth;
import com.brandsmith.api.session.SessionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class ShareService {

    public static final long TTL_DAYS = 30;
    public static final String NOT_FOUND_MESSAGE = "Share not found or expired";

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final SessionService sessions;
    private final ObjectMapper mapper;

    public ShareService(JdbcTemplate jdbc, SessionService sessions, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.sessions = sessions;
        this.mapper = mapper;
    }

    public CreatedShare create(UUID sessionId, String ownerToken) {
        sessions.requireOwner(sessionId, ownerToken);
        String token = OwnerAuth.newToken();
        Timestamp expiresAt = Timestamp.from(Instant.now().plus(TTL_DAYS, ChronoUnit.DAYS));
        jdbc.update("INSERT INTO share (token_hash, session_id, expires_at) VALUES (?, ?, ?)",
                OwnerAuth.sha256Hex(token), sessionId, expiresAt);
        return new CreatedShare(token, expiresAt);
    }

    public Map<String, Object> resolve(String token) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList("""
                            SELECT sess.brand_dna FROM share sh
                            JOIN session sess ON sess.id = sh.session_id
                            WHERE sh.token_hash = ? AND sh.expires_at > now()""",
                    OwnerAuth.sha256Hex(token));
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        if (rows.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, NOT_FOUND_MESSAGE);
        }
        return readMap(rows.get(0).get("brand_dna"));
    }

    public Export export(UUID sessionId, String ownerToken, String format) {
        sessions.requireOwner(sessionId, ownerToken);
        Map<String, Object> dna = sessions.loadBrandDna(sessionId, ownerToken);
        if ("md".equals(format)) {
            return new Export("brand-kit.md", "text/markdown;charset=UTF-8", KitMarkdown.render(dna));
        }
        if ("json".equals(format)) {
            return new Export("brand-kit.json", "application/json", toJsonPretty(dna));
        }
        throw new ResponseStatusException(BAD_REQUEST, "format must be md or json");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object jsonb) {
        if (jsonb == null) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(jsonb.toString(), MAP_TYPE);
        } catch (JsonProcessingException e) {
            return new LinkedHashMap<>();
        }
    }

    private String toJsonPretty(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    public record CreatedShare(String token, Timestamp expiresAt) {
    }

    public record Export(String filename, String contentType, String body) {
    }
}
