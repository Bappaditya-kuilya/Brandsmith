package com.brandsmith.api.personality;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record Trait(@JsonProperty("name") @NotBlank @Size(max = 80) String name,
                    @JsonProperty("whyFits") @JsonAlias("evidence")
                    @NotBlank @Size(max = 600) String whyFits,
                    @JsonProperty("behavior") @NotBlank @Size(max = 400) String behavior,
                    @JsonProperty("neverBecome") @NotBlank @Size(max = 200) String neverBecome) {
}
