package com.brandsmith.api.battle;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record JudgeResult(@NotNull @Size(min = 3, max = 3) @Valid List<JudgeScore> scores,
                          @JsonInclude(JsonInclude.Include.NON_NULL) List<Regenerated> regenerated) {

    public JudgeResult {
        scores = scores == null ? List.of() : List.copyOf(scores);
        regenerated = regenerated == null ? List.of() : List.copyOf(regenerated);
    }

    public record Regenerated(String mandate, String against, String reason) {
    }
}
