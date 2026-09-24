package com.brandsmith.api.audit;

import java.util.List;

public record DriftResult(String assetType,
                          int overall,
                          boolean pass,
                          List<Verdict> dimensions,
                          List<String> flaggedPhrases,
                          String rewrite) {

    public DriftResult {
        dimensions = dimensions == null ? List.of() : List.copyOf(dimensions);
        flaggedPhrases = flaggedPhrases == null ? List.of() : List.copyOf(flaggedPhrases);
    }

    public record Verdict(String dimension,
                          int weight,
                          int score,
                          boolean pass,
                          List<String> evidence,
                          List<Finding> deterministicFindings) {

        public Verdict {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            deterministicFindings = deterministicFindings == null ? List.of() : List.copyOf(deterministicFindings);
        }
    }
}
