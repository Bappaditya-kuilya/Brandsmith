package com.brandsmith.api.messages;

import com.fasterxml.jackson.annotation.JsonProperty;

public record SelectRequest(@JsonProperty("index") Integer index) {
}
