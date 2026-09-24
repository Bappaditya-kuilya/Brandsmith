package com.brandsmith.api.visual;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LogoGrammar(@JsonProperty("form") @NotBlank
                          @Pattern(regexp = "wordmark|monogram|wordmark\\+mark") String form,
                          @JsonProperty("letters") @NotBlank @Size(max = 3) String letters,
                          @JsonProperty("shapeNotes") @Size(max = 300) String shapeNotes) {
}
