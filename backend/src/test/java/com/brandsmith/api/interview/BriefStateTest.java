package com.brandsmith.api.interview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BriefStateTest {

    private static final String ANSWER = "They opened three group chats on Tuesday and gave up.";

    @Test
    void overallConfidenceIsWeightedAverageOfFieldConfidences() {
        BriefState brief = new BriefState();
        brief.applyUserEdit(BriefFieldId.TARGET_USER.id(), "Night-shift nurses");

        assertEquals(5.0 * BriefState.USER_EDIT_CONFIDENCE / 26.0, brief.overallConfidence(), 1e-9);

        brief.applyUserEdit(BriefFieldId.DESIRED_OUTCOME.id(), "Fewer missed handoffs");
        assertEquals((5.0 * 0.9 + 4.0 * 0.9) / 26.0, brief.overallConfidence(), 1e-9);
    }

    @Test
    void vagueBriefAsksAtLeastFourQuestionsBeforeStopping() {
        BriefState brief = new BriefState();
        int questions = 0;
        while (!brief.isDone() && questions < 12) {
            String fieldId = brief.selectNext().orElseThrow();
            brief.startQuestion(fieldId);
            questions++;
            brief.applyAnswer(ANSWER);
        }

        assertTrue(questions >= 4, "expected >= 4 questions, got " + questions);
        assertTrue(questions <= BriefState.MAX_QUESTIONS);
        assertTrue(brief.isDone());
    }

    @Test
    void detailedSeedWithHighConfidencesStopsImmediately() {
        BriefState brief = new BriefState();
        for (BriefFieldId id : BriefFieldId.values()) {
            brief.applyUserEdit(id.id(), "Specific detail for " + id.id());
        }

        assertTrue(brief.overallConfidence() >= BriefState.OVERALL_CONFIDENCE_THRESHOLD);
        assertTrue(brief.selectNext().isEmpty());
        assertTrue(brief.isDone());
        assertEquals(0, brief.getQuestionCount());
    }

    @Test
    void skipSetsLowConfidenceAndAssumptionFlag() {
        BriefState brief = new BriefState();
        brief.startQuestion(BriefFieldId.TARGET_USER.id());
        brief.applySkip();

        BriefField skipped = brief.field(BriefFieldId.TARGET_USER);
        assertNull(skipped.value());
        assertTrue(skipped.assumption());
        assertEquals(BriefState.SKIP_CONFIDENCE, skipped.confidence(), 1e-9);

        String next = brief.selectNext().orElseThrow();
        assertFalse(next.equals(BriefFieldId.TARGET_USER.id()));
        assertEquals(BriefFieldId.PROBLEM_ALTERNATIVE.id(), next);
    }

    @Test
    void weightedSelectionPicksMaxWeightTimesOneMinusConfidence() {
        BriefState brief = new BriefState();
        brief.getFields().put(BriefFieldId.TARGET_USER.id(), new BriefField(null, 0.9, null, false));
        brief.getFields().put(BriefFieldId.PROBLEM_ALTERNATIVE.id(), new BriefField(null, 0.9, null, false));

        assertEquals(BriefFieldId.DESIRED_OUTCOME.id(), brief.selectNext().orElseThrow());
    }

    @Test
    void stopsAfterSixQuestionsEvenWhenConfidenceIsLow() {
        BriefState brief = new BriefState();
        for (int i = 0; i < BriefState.MAX_QUESTIONS; i++) {
            brief.startQuestion(brief.selectNext().orElseThrow());
            brief.applySkip();
        }

        assertEquals(BriefState.MAX_QUESTIONS, brief.getQuestionCount());
        assertTrue(brief.overallConfidence() < BriefState.OVERALL_CONFIDENCE_THRESHOLD);
        assertTrue(brief.isDone());
    }

    @Test
    void answeredFieldIsNotAskedAgain() {
        BriefState brief = new BriefState();
        brief.startQuestion(BriefFieldId.TARGET_USER.id());
        brief.applyAnswer(ANSWER);

        assertEquals(ANSWER, brief.field(BriefFieldId.TARGET_USER).value());
        assertEquals(BriefState.ANSWER_CONFIDENCE, brief.field(BriefFieldId.TARGET_USER).confidence(), 1e-9);
        assertFalse(brief.field(BriefFieldId.TARGET_USER).assumption());
        assertEquals(BriefFieldId.PROBLEM_ALTERNATIVE.id(), brief.selectNext().orElseThrow());
    }
}
