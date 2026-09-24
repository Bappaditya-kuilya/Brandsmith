package com.brandsmith.api.naming;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Territory(@JsonProperty("name") @NotBlank @Size(max = 80) String name,
                        @JsonProperty("rationale") @NotBlank @Size(max = 400) String rationale,
                        @JsonProperty("whyFitsPersonality") @NotBlank @Size(max = 400) String whyFitsPersonality) {
}
