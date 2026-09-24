package com.brandsmith.api.embedding;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Builds corpus-vectors.json aligned line-by-line with overused-phrases.txt. Uses OpenAI embeddings
 * when OPENAI_API_KEY is set, otherwise writes deterministic HashEmbedder fallback vectors so the
 * app boots without an API key.
 */
public final class CorpusVectorBuilder {

    private static final Path DEFAULT_PHRASES = Path.of("src/main/resources/corpus/overused-phrases.txt");
    private static final Path DEFAULT_OUT = Path.of("src/main/resources/corpus/corpus-vectors.json");

    private CorpusVectorBuilder() {
    }

    public static void main(String[] args) throws IOException {
        Path phrasesPath = args.length > 0 ? Path.of(args[0]) : DEFAULT_PHRASES;
        Path outPath = args.length > 1 ? Path.of(args[1]) : DEFAULT_OUT;
        build(phrasesPath, outPath, System.getenv("OPENAI_API_KEY"));
    }

    static void build(Path phrasesPath, Path outPath, String openAiApiKey) throws IOException {
        List<String> phrases = readPhrases(phrasesPath);
        if (phrases.isEmpty()) {
            throw new IllegalStateException("No phrases found in " + phrasesPath);
        }
        float[][] vectors;
        String source;
        if (openAiApiKey != null && !openAiApiKey.isBlank()) {
            List<float[]> embedded = new OpenAiEmbedder(openAiApiKey.strip()).embedAll(phrases);
            vectors = embedded.toArray(float[][]::new);
            source = "openai/text-embedding-3-small";
        } else {
            HashEmbedder hashEmbedder = new HashEmbedder();
            vectors = new float[phrases.size()][];
            for (int i = 0; i < phrases.size(); i++) {
                vectors[i] = hashEmbedder.embed(phrases.get(i));
            }
            source = "hash-fallback (no OPENAI_API_KEY)";
        }
        if (outPath.getParent() != null) {
            Files.createDirectories(outPath.getParent());
        }
        new ObjectMapper().writeValue(outPath.toFile(), vectors);
        System.out.println("Wrote " + phrases.size() + " vectors (" + vectors[0].length + " dims) from "
                + source + " to " + outPath);
    }

    static List<String> readPhrases(Path phrasesPath) throws IOException {
        try (var lines = Files.lines(phrasesPath, StandardCharsets.UTF_8)) {
            return lines.map(String::strip).filter(line -> !line.isEmpty() && !line.startsWith("#")).toList();
        }
    }
}
