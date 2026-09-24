package com.brandsmith.api.stage;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record S0Output(@JsonProperty("clean_idea") @NotBlank @Size(max = 1200) String cleanIdea,
                       @JsonProperty("product_type") @NotBlank @Size(max = 100) String productType,
                       @JsonProperty("moderated") @NotNull Boolean moderated,
                       @JsonProperty("refusal_reason") @Size(max = 500) String refusalReason) {
}
