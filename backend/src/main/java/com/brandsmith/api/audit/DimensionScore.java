package com.brandsmith.api.audit;

import java.util.List;

public record DimensionScore(String dimension,
                             int weight,
                             int score,
                             List<String> evidence,
                             List<Finding> deterministicFindings) {

    public DimensionScore {
        score = Math.clamp(score, 0, 100);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        deterministicFindings = deterministicFindings == null ? List.of() : List.copyOf(deterministicFindings);
    }

    public boolean failing() {
        return score < AuditService.REVISE_THRESHOLD;
    }
}
