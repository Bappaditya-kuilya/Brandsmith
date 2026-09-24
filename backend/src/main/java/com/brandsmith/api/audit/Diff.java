package com.brandsmith.api.audit;

public record Diff(String asset, String dimension, String before, String after) {
}
