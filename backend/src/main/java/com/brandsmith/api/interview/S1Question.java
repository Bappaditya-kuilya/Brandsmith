package com.brandsmith.api.interview;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record S1Question(@JsonProperty("question") @NotBlank @Size(max = 500) String question) {
}
