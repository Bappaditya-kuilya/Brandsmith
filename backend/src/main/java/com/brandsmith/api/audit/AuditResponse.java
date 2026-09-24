package com.brandsmith.api.audit;

import java.util.List;

public record AuditResponse(AuditResult result,
                            List<Diff> diffs,
                            int reviseRounds,
                            boolean degraded) {

    public AuditResponse {
        diffs = diffs == null ? List.of() : List.copyOf(diffs);
    }
}
