package com.brandsmith.api.naming;

import com.fasterxml.jackson.annotation.JsonProperty;

public record NameAttempt(@JsonProperty("name") String name,
                          @JsonProperty("antiGenericScore") double antiGenericScore,
                          @JsonProperty("criticScore") double criticScore) {
}
