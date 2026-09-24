package com.brandsmith.api.messages;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record MessageHierarchy(@JsonProperty("primary") @NotBlank String primary,
                               @JsonProperty("secondary") @NotEmpty @Size(min = 1, max = 4)
                               List<String> secondary,
                               @JsonProperty("proof") @NotEmpty @Size(min = 1, max = 4)
                               List<String> proof) {
}
