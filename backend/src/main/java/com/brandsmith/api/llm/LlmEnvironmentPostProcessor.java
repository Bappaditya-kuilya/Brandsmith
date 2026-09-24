package com.brandsmith.api.llm;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/**
 * Fills provider-specific defaults (models, free-tier pricing) when the
 * configured values are blank, so existing @Value injection points stay dumb.
 */
public class LlmEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        HttpLlmClient.Provider provider = HttpLlmClient.resolve(
                environment.getProperty("brandsmith.llm.provider", "auto"),
                environment.getProperty("brandsmith.llm.groq-api-key", ""),
                environment.getProperty("brandsmith.llm.gemini-api-key", ""),
                environment.getProperty("brandsmith.llm.api-key", ""));

        Map<String, Object> props = new HashMap<>();
        if (blank(environment.getProperty("brandsmith.llm.main-model"))) {
            props.put("brandsmith.llm.main-model", defaultMain(provider));
        }
        if (blank(environment.getProperty("brandsmith.llm.small-model"))) {
            props.put("brandsmith.llm.small-model", defaultSmall(provider));
        }
        boolean free = provider == HttpLlmClient.Provider.GROQ || provider == HttpLlmClient.Provider.GEMINI;
        price(environment, props, "main-input-per-mtok", free ? "0.0" : "3.0");
        price(environment, props, "main-output-per-mtok", free ? "0.0" : "15.0");
        price(environment, props, "small-input-per-mtok", free ? "0.0" : "1.0");
        price(environment, props, "small-output-per-mtok", free ? "0.0" : "5.0");
        if (!props.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource("llmProviderDefaults", props));
        }
    }

    static String defaultMain(HttpLlmClient.Provider provider) {
        return switch (provider) {
            case GROQ -> "qwen/qwen3.8-27b";
            case GEMINI -> "gemini-3.6-flash";
            default -> "claude-sonnet-5";
        };
    }

    static String defaultSmall(HttpLlmClient.Provider provider) {
        return switch (provider) {
            case GROQ -> "qwen/qwen3.8-27b";
            case GEMINI -> "gemini-3.6-flash";
            default -> "claude-haiku-4-5-20251001";
        };
    }

    private static void price(ConfigurableEnvironment environment, Map<String, Object> props, String name, String value) {
        String key = "brandsmith.llm.pricing." + name;
        if (blank(environment.getProperty(key))) {
            props.put(key, value);
        }
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public int getOrder() {
        // after ConfigDataEnvironmentPostProcessor (HIGHEST_PRECEDENCE + 10)
        return Ordered.HIGHEST_PRECEDENCE + 20;
    }
}
