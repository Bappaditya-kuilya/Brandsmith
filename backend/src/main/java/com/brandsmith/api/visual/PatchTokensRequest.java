package com.brandsmith.api.visual;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

public record PatchTokensRequest(@JsonProperty("seedHue") @Min(0) @Max(360) Integer seedHue,
                                 @JsonProperty("accent") @Pattern(regexp = "#[0-9a-fA-F]{6}") String accent,
                                 @JsonProperty("saturation")
                                 @Pattern(regexp = "low|medium|high") String saturation) {
}
