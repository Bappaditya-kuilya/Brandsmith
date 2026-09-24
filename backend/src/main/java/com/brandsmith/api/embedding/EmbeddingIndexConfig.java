package com.brandsmith.api.embedding;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class EmbeddingIndexConfig {

    @Bean
    EmbeddingIndex embeddingIndex(@Value("${brandsmith.embedding.api-key:}") String openAiApiKey) {
        return CorpusLoader.load(openAiApiKey);
    }
}
