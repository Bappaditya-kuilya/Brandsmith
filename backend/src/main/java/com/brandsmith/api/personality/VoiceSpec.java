package com.brandsmith.api.personality;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record VoiceSpec(@JsonProperty("formality") @Min(1) @Max(5) int formality,
                        @JsonProperty("sentenceWords") @NotNull @Size(min = 2, max = 2) int[] sentenceWords,
                        @JsonProperty("humorLevel") @NotBlank @Pattern(regexp = "none|dry|warm|playful") String humorLevel,
                        @JsonProperty("bannedWords") @JsonAlias("banned") @NotNull List<String> bannedWords,
                        @JsonProperty("signatureMoves") @JsonAlias("moves")
                        @NotNull @Size(min = 3, max = 3) List<String> signatureMoves) {
}
