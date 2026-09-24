package com.brandsmith.api.audit;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record DriftRequest(@NotBlank @Size(max = 4000) String text,
                           @NotBlank @Pattern(regexp = "post|email|landing") String assetType) {
}
