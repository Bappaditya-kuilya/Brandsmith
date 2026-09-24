package com.brandsmith.api.battle;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record Position(@NotBlank @Size(max = 200) String category,
                       @NotBlank @Size(max = 300) String frameOfReference,
                       @NotBlank @Size(max = 300) String target,
                       @NotBlank @Size(max = 300) String insight,
                       @NotBlank @Size(max = 300) String differentiator,
                       @NotBlank @Size(max = 300) String valueProposition,
                       @NotEmpty @Size(min = 1, max = 4) List<String> proofPoints,
                       @NotBlank @Size(max = 300) String competitiveAngle,
                       @NotBlank @Size(max = 300) String biggestRisk,
                       @Pattern(regexp = "native|contrarian|emotional") String mandate) {

    public Position {
        if (proofPoints != null) {
            proofPoints = List.copyOf(proofPoints);
        }
    }

    public Position withMandate(String assignedMandate) {
        return new Position(category, frameOfReference, target, insight, differentiator,
                valueProposition, proofPoints, competitiveAngle, biggestRisk, assignedMandate);
    }
}
