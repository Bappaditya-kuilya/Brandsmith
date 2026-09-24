package com.brandsmith.api.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.constraints.Size;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReviseInstruction(String asset, @Size(max = 600) String instruction) {
}
