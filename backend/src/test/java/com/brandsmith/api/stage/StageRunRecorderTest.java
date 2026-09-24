package com.brandsmith.api.stage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.brandsmith.api.stage.StageRunRepository.StageRunView;

@SpringBootTest(properties = {
        "brandsmith.llm.api-key="
})
class StageRunRecorderTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private StageRunRecorder recorder;

    @Autowired
    private StageRunRepository repository;

    private UUID newSession() {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO session (id, owner_token_hash, expires_at) "
                        + "VALUES (?, 'hash', now() + interval '1 day')", id);
        return id;
    }

    private void runStage(UUID sessionId, String stage) {
        StageRunRecorder.Run run = recorder.start(sessionId, stage, Map.of("input", stage));
        recorder.run(run, "ok", Map.of("output", stage), null, "template", null, 0, 0, 0);
    }

    private Map<String, StageRunView> byStage(UUID sessionId) {
        return repository.list(sessionId).stream()
                .collect(java.util.stream.Collectors.toMap(StageRunView::stage, v -> v, (a, b) -> a));
    }

    @Test
    void reRunOfEarlierStageMarksDownstreamStaleExceptLocked() {
        UUID sessionId = newSession();
        runStage(sessionId, "S1");
        runStage(sessionId, "S2");
        runStage(sessionId, "S3");
        repository.setLocked(sessionId, "S2", true);

        runStage(sessionId, "S0");

        Map<String, StageRunView> stages = byStage(sessionId);
        assertTrue(stages.get("S1").stale(), "unlocked downstream S1 should be stale");
        assertFalse(stages.get("S2").stale(), "locked downstream S2 must stay fresh");
        assertTrue(stages.get("S3").stale(), "unlocked downstream S3 should be stale");
        assertFalse(stages.get("S0").stale(), "the re-run stage itself is never stale");
    }

    @Test
    void failMarksRunAsError() {
        UUID sessionId = newSession();
        StageRunRecorder.Run run = recorder.start(sessionId, "S1", Map.of("input", "x"));

        recorder.fail(run, "boom");

        String status = jdbc.queryForObject("SELECT status FROM stage_run WHERE id = ?",
                String.class, run.id());
        String raw = jdbc.queryForObject("SELECT raw_response FROM stage_run WHERE id = ?",
                String.class, run.id());
        assertEquals("error", status);
        assertEquals("boom", raw);
    }

    @Test
    void versionIncrementsOnReRun() {
        UUID sessionId = newSession();
        runStage(sessionId, "S1");
        runStage(sessionId, "S1");

        var versions = jdbc.queryForList(
                "SELECT version FROM stage_run WHERE session_id = ? AND stage = 'S1' ORDER BY version",
                Integer.class, sessionId);
        assertEquals(java.util.List.of(1, 2), versions);
    }

    @Test
    void startLeavesRunningRowUntilRunCompletes() {
        UUID sessionId = newSession();
        StageRunRecorder.Run run = recorder.start(sessionId, "S1", Map.of("input", "x"));

        String running = jdbc.queryForObject("SELECT status FROM stage_run WHERE id = ?",
                String.class, run.id());
        assertEquals("running", running);

        recorder.run(run, "degraded", Map.of("output", "y"), "raw", "template", "1", 5, 2, 3);

        StageRunView view = repository.list(sessionId).stream()
                .filter(v -> v.id().equals(run.id()))
                .findFirst()
                .orElseThrow();
        assertEquals("degraded", view.status());
        assertEquals(Long.valueOf(5), view.latencyMs());
        assertEquals(Integer.valueOf(2), view.tokensIn());
        assertEquals(Integer.valueOf(3), view.tokensOut());
        assertEquals("template", view.model());
        assertEquals("1", view.promptVersion());
    }
}
