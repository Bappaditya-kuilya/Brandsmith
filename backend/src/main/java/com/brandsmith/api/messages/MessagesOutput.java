package com.brandsmith.api.messages;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record MessagesOutput(@JsonProperty("taglines") @NotNull @Size(min = 3, max = 6)
                             List<@Valid @NotNull TaglineOption> taglines,
                             @JsonProperty("pitch") @NotBlank String pitch,
                             @JsonProperty("hierarchy") @Valid @NotNull MessageHierarchy hierarchy) {
}
