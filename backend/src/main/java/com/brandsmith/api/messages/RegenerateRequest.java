package com.brandsmith.api.messages;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Size;

public record RegenerateRequest(@JsonProperty("note") @Size(max = 300) String note) {
}
