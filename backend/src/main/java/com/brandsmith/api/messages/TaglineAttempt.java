package com.brandsmith.api.messages;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotNull;

public record TaglineAttempt(@JsonProperty("text") @NotNull String text,
                             @JsonProperty("score") double score,
                             @JsonProperty("delta") double delta) {
}
