package com.brandsmith.api.naming;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record NamingOutput(@JsonProperty("territories") @NotNull @Valid @Size(min = 3, max = 3)
                           List<Territory> territories,
                           @JsonProperty("names") @NotNull @Valid @Size(min = 9, max = 9)
                           List<BrandName> names,
                           @JsonProperty("domainDisclaimer") String domainDisclaimer) {
}
