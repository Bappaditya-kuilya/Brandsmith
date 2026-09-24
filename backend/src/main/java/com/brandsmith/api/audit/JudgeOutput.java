package com.brandsmith.api.audit;

import java.util.List;
import java.util.Map;

import jakarta.validation.constraints.NotNull;

public record JudgeOutput(@NotNull Map<String, Integer> scores,
                          Integer overall,
                          List<Finding> findings,
                          List<ReviseInstruction> reviseInstructions) {

    public JudgeOutput {
        findings = findings == null ? List.of() : findings;
        reviseInstructions = reviseInstructions == null ? List.of() : reviseInstructions;
    }
}
