package com.brandsmith.api.audit;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record Finding(String dimension, String asset, String quote, String rule, String severity) {

    public Finding {
        if (severity == null || severity.isBlank()) {
            severity = "warn";
        }
    }

    public static Finding fail(String dimension, String asset, String quote, String rule) {
        return new Finding(dimension, asset, quote, rule, "fail");
    }

    public static Finding warn(String dimension, String asset, String quote, String rule) {
        return new Finding(dimension, asset, quote, rule, "warn");
    }
}
