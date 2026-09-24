package com.brandsmith.api.share;

import java.util.List;
import java.util.Map;

public final class KitMarkdown {

    private KitMarkdown() {
    }

    public static String render(Map<String, Object> dna) {
        Map<?, ?> identity = map(dna.get("identity"));
        Map<?, ?> position = map(dna.get("position"));
        Map<?, ?> assets = map(dna.get("assets"));

        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(orDefault(text(identity, "name"), "Brand Kit")).append("\n\n");
        String tagline = text(identity, "tagline");
        if (tagline != null) {
            sb.append("> ").append(tagline).append("\n\n");
        }
        String pitch = text(identity, "pitch");
        if (pitch != null) {
            sb.append("## Pitch\n\n").append(pitch).append("\n\n");
        }
        if (!position.isEmpty()) {
            sb.append("## Position\n\n");
            field(sb, "Category", position.get("category"));
            field(sb, "Differentiator", position.get("differentiator"));
            field(sb, "Value proposition", position.get("valueProposition"));
            sb.append('\n');
        }
        if (!assets.isEmpty()) {
            sb.append("## Launch Assets\n\n");
            Object hero = assets.get("hero");
            if (hero instanceof Map<?, ?> h) {
                sb.append("### Hero\n\n");
                field(sb, "Headline", h.get("headline"));
                field(sb, "Subhead", h.get("subhead"));
                field(sb, "CTA", h.get("cta"));
                sb.append('\n');
            }
            field(sb, "Pitch", assets.get("pitch"));
            Object posts = assets.get("posts");
            if (posts instanceof List<?> list && !list.isEmpty()) {
                sb.append("### Posts\n\n");
                int n = 1;
                for (Object post : list) {
                    sb.append(n++).append(". ").append(post).append('\n');
                }
                sb.append('\n');
            }
            field(sb, "Bio (short)", assets.get("bioShort"));
            field(sb, "Bio (long)", assets.get("bioLong"));
            sb.append('\n');
        }
        return sb.toString().strip() + "\n";
    }

    private static void field(StringBuilder sb, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            sb.append("**").append(label).append(":** ").append(value).append("\n\n");
        }
    }

    private static Map<?, ?> map(Object value) {
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static String text(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).strip();
        return text.isEmpty() ? null : text;
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
