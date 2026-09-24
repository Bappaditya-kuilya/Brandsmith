package com.brandsmith.api.visual;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VisualDirection(@JsonProperty("moodWords")
                              @NotNull @Size(min = 3, max = 5)
                              List<@NotBlank @Size(max = 40) String> moodWords,
                              @JsonProperty("seedHue") @Min(0) @Max(360) int seedHue,
                              @JsonProperty("saturation") @JsonAlias("saturationBand") @NotBlank
                              @Pattern(regexp = "low|medium|high") String saturation,
                              @JsonProperty("shapeLanguage") @NotBlank
                              @Pattern(regexp = "rounded|sharp|modular|organic") String shapeLanguage,
                              @JsonProperty("fontPairIds") @JsonAlias("typePairIds")
                              @NotNull @Size(min = 2, max = 2)
                              List<@NotBlank @FontId String> fontPairIds,
                              @JsonProperty("logoGrammar") @JsonAlias("logoConcept")
                              @Valid @NotNull LogoGrammar logoGrammar,
                              @JsonProperty("imagery") @NotBlank @Size(max = 400) String imagery,
                              @JsonProperty("avoidList") @JsonAlias("visualAvoid")
                              @NotNull @Size(min = 1, max = 6)
                              List<@NotBlank @Size(max = 80) String> avoidList) {
}
