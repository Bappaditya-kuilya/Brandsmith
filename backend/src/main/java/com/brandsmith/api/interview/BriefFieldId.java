package com.brandsmith.api.interview;

import java.util.Optional;

public enum BriefFieldId {

    TARGET_USER("target_user", "target user (who exactly, in what moment)", 5),
    PROBLEM_ALTERNATIVE("problem_alternative", "core problem and current alternative", 5),
    DESIRED_OUTCOME("desired_outcome", "desired outcome for the user", 4),
    CATEGORY_COMPETITORS("category_competitors", "category and competitors the user knows", 3),
    FOUNDER_GOAL("founder_goal", "founder goal (income, community, portfolio, mission)", 3),
    CONSTRAINTS("constraints", "constraints (budget, region, time)", 2),
    TONE_HINTS("tone_hints", "tone hints and things they dislike", 2),
    PROOF_ADVANTAGE("proof_advantage", "proof or unfair advantage", 2);

    private final String id;
    private final String label;
    private final int weight;

    BriefFieldId(String id, String label, int weight) {
        this.id = id;
        this.label = label;
        this.weight = weight;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public int weight() {
        return weight;
    }

    public static Optional<BriefFieldId> find(String id) {
        for (BriefFieldId field : values()) {
            if (field.id.equals(id)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    public static BriefFieldId of(String id) {
        return find(id).orElseThrow(() -> new IllegalArgumentException("Unknown brief field: " + id));
    }
}
