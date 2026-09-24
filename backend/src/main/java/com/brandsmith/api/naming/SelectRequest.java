package com.brandsmith.api.naming;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SelectRequest(@JsonProperty("nameIndex") Integer nameIndex,
                            @JsonProperty("name") String name) {
}
