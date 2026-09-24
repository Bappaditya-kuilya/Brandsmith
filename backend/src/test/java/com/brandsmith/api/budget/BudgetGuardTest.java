package com.brandsmith.api.budget;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class BudgetGuardTest {

    private final BudgetGuard guard = new BudgetGuard("claude-sonnet-5", 3.0, 15.0, 1.0, 5.0);

    @Test
    void rejectsWhenSpentAtOrOverCap() {
        assertThrows(BudgetExceededException.class, () -> guard.ensureWithinCap(0.40, 0.40));
        assertThrows(BudgetExceededException.class, () -> guard.ensureWithinCap(0.41, 0.40));
    }

    @Test
    void allowsWhenUnderCap() {
        assertDoesNotThrow(() -> guard.ensureWithinCap(0.39, 0.40));
        assertDoesNotThrow(() -> guard.ensureWithinCap(0.0, 0.40));
    }

    @Test
    void costUsesTierPrices() {
        assertEquals(18.0, guard.cost("claude-sonnet-5", 1_000_000, 1_000_000), 1e-9);
        assertEquals(6.0, guard.cost("claude-haiku-4-5-20251001", 1_000_000, 1_000_000), 1e-9);
        assertEquals(6.0, guard.cost("unknown-model", 1_000_000, 1_000_000), 1e-9);
    }

    @Test
    void costScalesWithTokenCounts() {
        assertEquals(0.003 + 0.0015, guard.cost("claude-sonnet-5", 1000, 100), 1e-9);
    }
}
