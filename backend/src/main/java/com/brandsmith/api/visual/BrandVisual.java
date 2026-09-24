package com.brandsmith.api.visual;

import java.util.List;

import com.brandsmith.api.det.Palette;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

public record BrandVisual(@JsonProperty("palette") @Valid @NotNull Palette palette,
                          @JsonProperty("fonts") @NotNull List<String> fonts,
                          @JsonProperty("shape") @NotNull String shape,
                          @JsonProperty("logoSvg") @NotNull String logoSvg) {
}
