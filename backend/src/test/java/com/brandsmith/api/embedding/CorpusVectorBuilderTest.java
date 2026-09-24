package com.brandsmith.api.embedding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

class CorpusVectorBuilderTest {

    @TempDir
    Path tempDir;

    @Test
    void withoutKeyWritesDeterministicHashVectorsAlignedWithPhrases() throws Exception {
        Path phrasesPath = tempDir.resolve("phrases.txt");
        Path outPath = tempDir.resolve("corpus-vectors.json");
        Files.writeString(phrasesPath, "empower every team\nunlock your potential\n\n# comment line\n  third phrase  \n");

        CorpusVectorBuilder.build(phrasesPath, outPath, null);

        float[][] vectors = new ObjectMapper().readValue(outPath.toFile(), float[][].class);
        assertEquals(3, vectors.length, "blank lines and comments are skipped");
        assertEquals(HashEmbedder.DIMENSIONS, vectors[0].length);

        HashEmbedder embedder = new HashEmbedder();
        for (int i = 0; i < vectors.length; i++) {
            assertEquals(java.util.Arrays.toString(embedder.embed(List.of("empower every team", "unlock your potential", "third phrase").get(i))),
                    java.util.Arrays.toString(vectors[i]), "vector " + i + " must align with phrase " + i);
        }

        EmbeddingIndex index = new InMemoryEmbeddingIndex(List.of(vectors[0], vectors[1], vectors[2]), embedder::embed);
        assertEquals(1.0, index.maxCosineSimilarity("third phrase"), 1e-6);
        assertTrue(EmbeddingScore.forText(index, "empower every team") == 0);
    }
}
