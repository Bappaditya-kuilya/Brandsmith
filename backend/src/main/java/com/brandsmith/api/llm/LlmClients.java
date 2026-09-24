package com.brandsmith.api.llm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

@Configuration
public class LlmClients {

    @Bean
    LlmClient llmClient(ObjectMapper mapper,
                        @Value("${brandsmith.llm.provider:auto}") String provider,
                        @Value("${brandsmith.llm.groq-api-key:}") String groqKey,
                        @Value("${brandsmith.llm.gemini-api-key:}") String geminiKey,
                        @Value("${brandsmith.llm.api-key:}") String anthropicKey,
                        @Value("${brandsmith.llm.timeout-ms:60000}") long timeoutMs) {
        HttpLlmClient.Provider resolved = HttpLlmClient.resolve(provider, groqKey, geminiKey, anthropicKey);
        String key = switch (resolved) {
            case GROQ -> groqKey;
            case GEMINI -> geminiKey;
            case ANTHROPIC -> anthropicKey;
            case NONE -> "";
        };
        return new HttpLlmClient(mapper, resolved, key, timeoutMs);
    }
}
