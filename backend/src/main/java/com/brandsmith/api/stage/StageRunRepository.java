package com.brandsmith.api.stage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

@Component
public class StageRunRepository {

    public static final List<String> ORDER = List.of("S0", "S1", "S2", "S3", "S4", "S5", "S6", "S7", "S8");

    // ponytail: stage_run has no created_at; ctid stands in for insert order until a migration adds one
    private static final String LIST_SQL = """
            SELECT r.id, r.stage, r.model, r.latency_ms, r.prompt_version, r.status,
                   r.tokens_in, r.tokens_out, r.stale, r.locked,
                   (SELECT s.value FROM score s WHERE s.stage_run_id = r.id LIMIT 1) AS score
            FROM stage_run r
            WHERE r.session_id = ?
            ORDER BY CASE r.stage
                    WHEN 'S0' THEN 0 WHEN 'S1' THEN 1 WHEN 'S2' THEN 2 WHEN 'S3' THEN 3
                    WHEN 'S4' THEN 4 WHEN 'S5' THEN 5 WHEN 'S6' THEN 6 WHEN 'S7' THEN 7
                    WHEN 'S8' THEN 8 ELSE 9 END, r.ctid
            """;

    private static final RowMapper<StageRunView> ROW_MAPPER = (rs, rowNum) -> new StageRunView(
            rs.getObject("id", UUID.class),
            rs.getString("stage"),
            rs.getString("model"),
            rs.getObject("latency_ms", Long.class),
            rs.getString("prompt_version"),
            rs.getString("status"),
            rs.getObject("tokens_in", Integer.class),
            rs.getObject("tokens_out", Integer.class),
            rs.getBoolean("stale"),
            rs.getBoolean("locked"),
            rs.getObject("score", Double.class));

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public StageRunRepository(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public UUID insertRunning(UUID sessionId, String stage, Object input) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                        INSERT INTO stage_run (id, session_id, stage, version, status, input)
                        VALUES (?, ?, ?, (
                            SELECT COALESCE(MAX(version), 0) + 1 FROM stage_run
                            WHERE session_id = ? AND stage = ?
                        ), 'running', ?::jsonb)""",
                id, sessionId, stage, sessionId, stage, toJson(input));
        return id;
    }

    public void finish(UUID id, String status, Object output, String rawResponse, String model,
                       String promptVersion, Long latencyMs, Integer tokensIn, Integer tokensOut) {
        jdbc.update("""
                        UPDATE stage_run
                        SET status = ?, output = ?::jsonb, raw_response = ?, model = ?,
                            prompt_version = ?, latency_ms = ?, tokens_in = ?, tokens_out = ?
                        WHERE id = ?""",
                status, toJson(output), rawResponse, model, promptVersion,
                latencyMs, tokensIn, tokensOut, id);
    }

    public void fail(UUID id, String error) {
        jdbc.update("UPDATE stage_run SET status = 'error', raw_response = ? WHERE id = ?", error, id);
    }

    public void markDownstreamStale(UUID sessionId, String stage) {
        int idx = ORDER.indexOf(stage);
        if (idx < 0 || idx >= ORDER.size() - 1) {
            return;
        }
        List<String> downstream = ORDER.subList(idx + 1, ORDER.size());
        String placeholders = String.join(",", Collections.nCopies(downstream.size(), "?"));
        List<Object> args = new ArrayList<>();
        args.add(sessionId);
        args.addAll(downstream);
        // ponytail: locked rows are never invalidated by upstream re-runs
        jdbc.update("UPDATE stage_run SET stale = true WHERE session_id = ? AND stage IN ("
                + placeholders + ") AND NOT locked", args.toArray());
    }

    public void setLocked(UUID sessionId, String stage, boolean locked) {
        jdbc.update("UPDATE stage_run SET locked = ? WHERE session_id = ? AND stage = ?",
                locked, sessionId, stage);
    }

    public List<StageRunView> list(UUID sessionId) {
        return jdbc.query(LIST_SQL, ROW_MAPPER, sessionId);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    public record StageRunView(UUID id,
                               String stage,
                               String model,
                               Long latencyMs,
                               String promptVersion,
                               String status,
                               Integer tokensIn,
                               Integer tokensOut,
                               boolean stale,
                               boolean locked,
                               Double score) {
    }
}
