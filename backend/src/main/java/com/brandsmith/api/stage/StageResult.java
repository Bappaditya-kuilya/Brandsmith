package com.brandsmith.api.stage;

public record StageResult<T>(T value,
                             String rawResponse,
                             boolean degraded,
                             String model,
                             String promptVersion,
                             int tokensIn,
                             int tokensOut,
                             long latencyMs,
                             String error) {
}
