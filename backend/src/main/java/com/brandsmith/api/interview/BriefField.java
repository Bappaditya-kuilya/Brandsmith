package com.brandsmith.api.interview;

public record BriefField(String value, double confidence, String evidence, boolean assumption) {

    public BriefField {
        confidence = Math.max(0.0, Math.min(1.0, confidence));
    }
}
