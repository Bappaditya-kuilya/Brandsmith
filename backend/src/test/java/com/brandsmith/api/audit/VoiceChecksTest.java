package com.brandsmith.api.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class VoiceChecksTest {

    private static final int[] RANGE = {6, 16};

    @Test
    void bannedWordIsDetectedAndPenalized() {
        VoiceChecks.Result result = VoiceChecks.check("pitch",
                "This tool will revolutionize how groups study", List.of("revolutionize"), RANGE);

        assertEquals(60, result.score());
        assertEquals(1, result.findings().size());
        Finding finding = result.findings().get(0);
        assertEquals("fail", finding.severity());
        assertEquals("revolutionize", finding.quote());
        assertEquals("voiceCompliance", finding.dimension());
        assertEquals("pitch", finding.asset());
        assertTrue(finding.rule().contains("revolutionize"));
    }

    @Test
    void bannedWordMatchIsCaseInsensitiveAndWordBounded() {
        assertTrue(VoiceChecks.containsWord("We REVOLUTIONIZE study groups", "revolutionize"));
        assertFalse(VoiceChecks.containsWord("revolutionizeyour workflow", "revolutionize"));
        assertFalse(VoiceChecks.containsWord("a evolutionary path", "revolutionize"));
    }

    @Test
    void cleanTextScoresOneHundred() {
        VoiceChecks.Result result = VoiceChecks.check("pitch",
                "Students use this app to find a study group before the deadline.",
                List.of("revolutionize"), RANGE);

        assertEquals(100, result.score());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void shortTaglineSkipsSentenceLengthAndGradeChecks() {
        VoiceChecks.Result result = VoiceChecks.check("tagline",
                "A very long tagline sentence that would otherwise fail the range check",
                List.of(), RANGE);

        assertEquals(100, result.score());
        assertTrue(result.findings().isEmpty());
    }

    @Test
    void proseOutsideSentenceRangeIsFlagged() {
        StringBuilder longSentence = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            longSentence.append("word ");
        }
        VoiceChecks.Result result = VoiceChecks.check("pitch", longSentence.toString() + "end.", List.of(), RANGE);

        assertEquals(80, result.score());
        assertEquals(1, result.findings().size());
        assertEquals("fail", result.findings().get(0).severity());
        assertTrue(result.findings().get(0).rule().contains("sentenceWords"));
    }

    @Test
    void exclamationRunBeyondOneIsFlagged() {
        VoiceChecks.Result result = VoiceChecks.check("hero", "Yes!! We did it!!", List.of(), RANGE);

        assertEquals(90, result.score());
        assertEquals(1, result.findings().size());
        assertTrue(result.findings().get(0).rule().contains("exclamation"));
    }

    @Test
    void checkAllUsesWorstAssetAndCollectsFindings() {
        Map<String, String> assets = Map.of(
                "tagline", "Group up before midterms",
                "pitch", "This will revolutionize your study groups");

        VoiceChecks.Result result = VoiceChecks.checkAll(assets, List.of("revolutionize"), RANGE);

        assertEquals(60, result.score());
        assertEquals(1, result.findings().size());
        assertEquals("pitch", result.findings().get(0).asset());
    }

    @Test
    void emptyAssetsScoreOneHundred() {
        assertEquals(100, VoiceChecks.checkAll(Map.of(), List.of("revolutionize"), RANGE).score());
    }

    @Test
    void templateReviseStripsForbiddenWordsAndExclamations() {
        String revised = VoiceChecks.templateRevise(
                "We will revolutionize your study groups!!", List.of("revolutionize"));

        assertFalse(revised.contains("revolutionize"));
        assertFalse(revised.contains("!!"));
        assertEquals("We will your study groups!", revised);
    }

    @Test
    void templateReviseKeepsCleanTextIntact() {
        String text = "Match by shared assignment before the deadline.";
        assertEquals(text, VoiceChecks.templateRevise(text, List.of("revolutionize")));
    }
}
