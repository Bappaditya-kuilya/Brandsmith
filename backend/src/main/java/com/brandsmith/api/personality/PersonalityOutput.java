package com.brandsmith.api.personality;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PersonalityOutput(@JsonProperty("traits") @NotNull @Size(min = 3, max = 5)
                                List<@Valid @NotNull Trait> traits,
                                @JsonProperty("voice") @Valid @NotNull VoiceSpec voice,
                                @JsonProperty("avoidList") List<String> avoidList) {
}
