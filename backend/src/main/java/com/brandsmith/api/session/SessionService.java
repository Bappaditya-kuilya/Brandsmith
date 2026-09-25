package com.brandsmith.api.session;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.brandsmith.api.interview.BriefState;
import com.brandsmith.api.stage.S0IntakeService;
import com.brandsmith.api.stage.S0IntakeService.IntakeResult;
import com.brandsmith.api.stage.StageRunRecorder;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class SessionService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final S0IntakeService intake;
    private final ObjectMapper mapper;
    private final StageRunRecorder recorder;
    private final double defaultCap;
    private final long ttlDays;

    public SessionService(JdbcTemplate jdbc,
                          S0IntakeService intake,
                          ObjectMapper mapper,
                          StageRunRecorder recorder,
                          @Value("${brandsmith.budget.default-cap:0.40}") double defaultCap,
                          @Value("${brandsmith.session.ttl-days:30}") long ttlDays) {
        this.jdbc = jdbc;
        this.intake = intake;
        this.mapper = mapper;
        this.recorder = recorder;
        this.defaultCap = defaultCap;
        this.ttlDays = ttlDays;
    }

    public CreatedSession create(String idea) {
        IntakeResult s0 = intake.run(idea, 0.0, defaultCap);
        if (s0.output().moderated()) {
            throw new RefusalException(S0IntakeService.REFUSAL_MESSAGE);
        }
        UUID id = UUID.randomUUID();
        String token = OwnerAuth.newToken();
        String hash = OwnerAuth.sha256Hex(token);

        BriefState state = new BriefState();
        state.setIdea(idea);
        state.setCleanIdea(s0.output().cleanIdea());
        state.setProductType(s0.output().productType() == null ? "unknown" : s0.output().productType());

        jdbc.update("""
                        INSERT INTO session (id, owner_token_hash, expires_at, status, brief_state, brand_dna, tokens_used, budget_cap)
                        VALUES (?, ?, ?, 'active', ?::jsonb, '{}'::jsonb, ?, ?)""",
                id, hash, Timestamp.from(Instant.now().plus(ttlDays, ChronoUnit.DAYS)),
                toJson(state), dec(s0.costUsd()), dec(defaultCap));

        StageRunRecorder.Run run = recorder.start(id, "S0", Map.of("idea", idea));
        recorder.run(run, s0.degraded() ? "degraded" : "ok", s0.output(), s0.rawResponse(),
                s0.model(), s0.promptVersion(), s0.latencyMs(), s0.tokensIn(), s0.tokensOut());

        return new CreatedSession(id, idea, token);
    }

    public Map<String, Object> get(UUID id, String token) {
        Map<String, Object> row = fetchOwned(id, token);
        Map<String, Object> brief = readMap(row.get("brief_state"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id.toString());
        body.put("status", row.get("status"));
        body.put("createdAt", row.get("created_at"));
        body.put("expiresAt", row.get("expires_at"));
        body.put("tokensUsed", row.get("tokens_used"));
        body.put("budgetCap", row.get("budget_cap"));
        body.put("idea", brief.get("idea"));
        body.put("briefState", brief);
        body.put("brandDna", readMap(row.get("brand_dna")));
        return body;
    }

    public void delete(UUID id, String token) {
        fetchOwned(id, token);
        jdbc.update("DELETE FROM session WHERE id = ?", id);
    }

    public void requireOwner(UUID id, String token) {
        fetchOwned(id, token);
    }

    public BriefSnapshot loadBrief(UUID id, String token) {
        Map<String, Object> row = fetchOwned(id, token);
        return new BriefSnapshot(readMap(row.get("brief_state")),
                ((Number) row.get("tokens_used")).doubleValue(),
                ((Number) row.get("budget_cap")).doubleValue());
    }

    public void saveBrief(UUID id, Map<String, Object> brief, double addCostUsd) {
        jdbc.update("""
                        UPDATE session SET brief_state = ?::jsonb, tokens_used = tokens_used + ? WHERE id = ?""",
                toJson(brief), dec(addCostUsd), id);
    }

    public Map<String, Object> loadBrandDna(UUID id, String token) {
        return loadStage(id, token).brandDna();
    }

    public void saveBrandDna(UUID id, Map<String, Object> brandDna, double addCostUsd) {
        jdbc.update("""
                        UPDATE session SET brand_dna = ?::jsonb, tokens_used = tokens_used + ? WHERE id = ?""",
                toJson(brandDna), dec(addCostUsd), id);
    }

    public StageSnapshot loadStage(UUID id, String token) {
        return stageOf(fetchOwned(id, token));
    }

    public StageSnapshot loadStageForUpdate(UUID id, String token) {
        return stageOf(fetchOwned(id, token, true));
    }

    private StageSnapshot stageOf(Map<String, Object> row) {
        return new StageSnapshot(readMap(row.get("brief_state")), readMap(row.get("brand_dna")),
                ((Number) row.get("tokens_used")).doubleValue(),
                ((Number) row.get("budget_cap")).doubleValue());
    }

    public boolean stageLocked(UUID id, String stage) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM stage_run WHERE session_id = ? AND stage = ? AND locked",
                Integer.class, id, stage);
        return count != null && count > 0;
    }

    public void recordStageRun(StageRunRecord run) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version), 0) FROM stage_run WHERE session_id = ? AND stage = ?",
                Integer.class, run.sessionId(), run.stage());
        int version = (max == null ? 0 : max) + 1;
        jdbc.update("""
                        INSERT INTO stage_run (id, session_id, stage, version, prompt_version, input, output,
                                               raw_response, model, latency_ms, tokens_in, tokens_out, status, stale, locked)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, false, false)""",
                UUID.randomUUID(), run.sessionId(), run.stage(), version, run.promptVersion(),
                toJson(run.input()), toJson(run.output()),
                run.rawResponse(), run.model(), run.latencyMs(), run.tokensIn(), run.tokensOut(),
                run.degraded() ? "degraded" : "ok");
    }

    private Map<String, Object> fetchOwned(UUID id, String token) {
        return fetchOwned(id, token, false);
    }

    private Map<String, Object> fetchOwned(UUID id, String token, boolean forUpdate) {
        List<Map<String, Object>> rows;
        try {
            rows = jdbc.queryForList(
                    "SELECT * FROM session WHERE id = ? AND expires_at IS NOT NULL AND expires_at > now()"
                            + (forUpdate ? " FOR UPDATE" : ""), id);
        } catch (EmptyResultDataAccessException e) {
            throw new ResponseStatusException(NOT_FOUND, "Session not found");
        }
        if (rows.isEmpty()) {
            throw new ResponseStatusException(NOT_FOUND, "Session not found");
        }
        Map<String, Object> row = rows.get(0);
        if (!OwnerAuth.matches(token, String.valueOf(row.get("owner_token_hash")))) {
            throw new OwnerMismatchException();
        }
        return row;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(Object jsonb) {
        if (jsonb == null) {
            return Map.of();
        }
        try {
            return mapper.readValue(jsonb.toString(), MAP_TYPE);
        } catch (JsonProcessingException e) {
            return Map.of();
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize JSON", e);
        }
    }

    private static java.math.BigDecimal dec(double value) {
        return java.math.BigDecimal.valueOf(value);
    }

    public record CreatedSession(UUID id, String idea, String ownerToken) {
    }

    public record BriefSnapshot(Map<String, Object> brief, double spentUsd, double capUsd) {
    }

    public record StageSnapshot(Map<String, Object> brief, Map<String, Object> brandDna,
                                double spentUsd, double capUsd) {
    }

    public record StageRunRecord(UUID sessionId, String stage, Map<String, Object> input,
                                 Object output, String rawResponse, String model, String promptVersion,
                                 long latencyMs, int tokensIn, int tokensOut, boolean degraded) {
    }
}
