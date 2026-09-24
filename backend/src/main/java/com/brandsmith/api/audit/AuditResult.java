package com.brandsmith.api.audit;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AuditResult(List<DimensionScore> dimensions,
                          int overall,
                          List<Finding> conflicts,
                          Map<String, List<String>> reviseInstructions) {

    public AuditResult {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        conflicts = conflicts == null ? List.of() : List.copyOf(conflicts);
        reviseInstructions = reviseInstructions == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(reviseInstructions));
    }

    public DimensionScore dimension(String name) {
        return dimensions.stream()
                .filter(d -> d.dimension().equals(name))
                .findFirst()
                .orElse(null);
    }

    public boolean pass() {
        return overall >= AuditService.REVISE_THRESHOLD
                && dimensions.stream().noneMatch(DimensionScore::failing);
    }
}
