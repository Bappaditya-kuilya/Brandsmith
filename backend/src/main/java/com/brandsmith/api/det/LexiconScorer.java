package com.brandsmith.api.det;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lexicon anti-generic score: penalty hits against a curated cliche list, inverted to 0-100.
 * Entries are lowercase; a line starting with '-' matches word suffixes (e.g. -ify).
 * Each hit costs 10 points.
 */
public final class LexiconScorer {

    private static final int PENALTY_PER_HIT = 10;
    private static final String RESOURCE = "/cliche-list.txt";

    private final List<String> entries;

    public LexiconScorer() {
        this(loadDefault());
    }

    public LexiconScorer(List<String> entries) {
        this.entries = List.copyOf(entries);
    }

    public int entryCount() {
        return entries.size();
    }

    public int score(String text) {
        return Math.clamp(100 - PENALTY_PER_HIT * hitCount(text), 0, 100);
    }

    public List<String> hits(String text) {
        String lower = text == null ? "" : text.toLowerCase(Locale.ROOT);
        String[] words = lower.split("[^a-z0-9']+");
        List<String> found = new ArrayList<>();
        for (String entry : entries) {
            if (entry.startsWith("-")) {
                String suffix = entry.substring(1);
                for (String w : words) {
                    if (w.endsWith(suffix)) {
                        found.add(entry);
                        break;
                    }
                }
            } else if (lower.contains(entry)) {
                found.add(entry);
            }
        }
        return found;
    }

    private int hitCount(String text) {
        return hits(text).size();
    }

    private static List<String> loadDefault() {
        try (InputStream in = LexiconScorer.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            List<String> entries = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        continue;
                    }
                    entries.add(trimmed.toLowerCase(Locale.ROOT));
                }
            }
            return entries;
        } catch (IOException e) {
            throw new IllegalStateException("failed to read " + RESOURCE, e);
        }
    }
}
