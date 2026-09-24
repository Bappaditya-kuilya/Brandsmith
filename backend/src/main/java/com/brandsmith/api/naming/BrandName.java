package com.brandsmith.api.naming;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BrandName(@JsonProperty("name") @NotBlank @Size(max = 80) String name,
                        @JsonProperty("territory") @NotBlank String territory,
                        @JsonProperty("rationale") @NotBlank String rationale,
                        @JsonProperty("length") int length,
                        @JsonProperty("pronounceability") int pronounceability,
                        @JsonProperty("lexiconScore") int lexiconScore,
                        @JsonProperty("embeddingScore") int embeddingScore,
                        @JsonProperty("criticScore") double criticScore,
                        @JsonProperty("antiGenericScore") double antiGenericScore,
                        @JsonProperty("domainSignal") String domainSignal,
                        @JsonProperty("attempts") List<NameAttempt> attempts) {
}
