package com.brandsmith.api.launch;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LaunchOutput(@NotNull @Valid Hero hero,
                           @NotBlank String pitch,
                           @NotNull @Size(min = 3, max = 3) List<@NotBlank String> posts,
                           @NotBlank String bioShort,
                           @NotBlank String bioLong) {

    public record Hero(@NotBlank String headline,
                       @NotBlank String subhead,
                       @NotBlank String cta) {
    }
}
