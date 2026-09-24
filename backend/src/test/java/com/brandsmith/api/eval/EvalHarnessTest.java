package com.brandsmith.api.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {"brandsmith.llm.api-key="})
class EvalHarnessTest {

    @Autowired
    EvalHarness harness;

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void offlineTwoFixturesWritesEvalRunAndPrintsTable() {
        String table = harness.run(2);

        assertTrue(table.contains("| fixture | variant |"), table);
        assertTrue(table.contains("fx-01-student-tool"), table);
        assertTrue(table.contains(EvalHarness.VARIANT_BASELINE), table);
        assertTrue(table.contains(EvalHarness.VARIANT_BRANDSMITH), table);
        assertTrue(table.contains("**Averages**"), table);
        assertFalse(table.contains("null"), table);

        for (String fixtureId : List.of("fx-01-student-tool", "fx-02-creator-newsletter")) {
            Integer variants = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT variant) FROM eval_run WHERE fixture_id = ?",
                    Integer.class, fixtureId);
            assertEquals(2, variants, fixtureId);
        }

        String judge = jdbc.queryForObject(
                "SELECT scores->>'judge' FROM eval_run WHERE fixture_id = ? AND variant = ? LIMIT 1",
                String.class, "fx-01-student-tool", EvalHarness.VARIANT_BASELINE);
        assertEquals("heuristic", judge);
    }

    @Test
    void heuristicJudgeScoresClampedAndContrastsClicheVsClean() {
        EvalHarness.Scores cliche = harness.heuristicJudge(
                "# Brand > Empower seamless AI-powered all-in-one unlock journey");
        EvalHarness.Scores clean = harness.heuristicJudge(
                "# North Harbor > Harbor ice notes for small-coast coffee | Pitch | ### Posts\n1. Ship a launch note");

        assertTrue(cliche.distinctiveness() < clean.distinctiveness(),
                "cliche=" + cliche.distinctiveness() + " clean=" + clean.distinctiveness());
        assertTrue(cliche.distinctiveness() >= 0 && cliche.distinctiveness() <= 100);
        assertTrue(clean.consistency() >= 0 && clean.consistency() <= 100);
        assertTrue(clean.usefulness() >= 0 && clean.usefulness() <= 100);
        assertEquals("heuristic", clean.judge());
    }
}
