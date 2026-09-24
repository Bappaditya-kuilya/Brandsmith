package com.brandsmith.api.battle;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record JudgeScore(@NotBlank String mandate,
                         @Min(1) @Max(5) int audienceFit,
                         @Min(1) @Max(5) int distinctiveness,
                         @Min(1) @Max(5) int credibility,
                         @Min(1) @Max(5) int memorability,
                         @Min(1) @Max(5) int feasibility,
                         @NotBlank @Size(max = 500) String explanation) {

    public int total() {
        return audienceFit + distinctiveness + credibility + memorability + feasibility;
    }
}
