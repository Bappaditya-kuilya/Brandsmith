package com.brandsmith.api.messages;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TaglineOption(@JsonProperty("text") @NotBlank String text,
                            @JsonProperty("score") double score,
                            @JsonProperty("attempts") @NotNull @Size(min = 1)
                            List<@Valid @NotNull TaglineAttempt> attempts) {
}
