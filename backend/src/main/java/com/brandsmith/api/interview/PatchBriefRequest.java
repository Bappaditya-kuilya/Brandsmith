package com.brandsmith.api.interview;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record PatchBriefRequest(@NotEmpty(message = "fields is required")
                                Map<String, FieldPatch> fields) {

    public record FieldPatch(@NotBlank(message = "value is required")
                             @Size(max = 4000) String value) {
    }
}
