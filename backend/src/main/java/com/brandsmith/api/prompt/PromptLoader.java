package com.brandsmith.api.prompt;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class PromptLoader {

    private final Map<String, Prompt> cache = new ConcurrentHashMap<>();

    public Prompt load(String name) {
        return cache.computeIfAbsent(name, this::loadUncached);
    }

    private Prompt loadUncached(String name) {
        for (String ext : new String[] {"md", "txt"}) {
            ClassPathResource resource = new ClassPathResource("prompts/" + name + "." + ext);
            if (resource.exists()) {
                return parse(name, read(resource));
            }
        }
        throw new IllegalStateException("Prompt not found: " + name);
    }

    private String read(ClassPathResource resource) {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read prompt: " + resource.getFilename(), e);
        }
    }

    private Prompt parse(String name, String content) {
        String version = filenameVersion(name);
        String body = content;
        if (content.startsWith("---")) {
            int end = content.indexOf("---", 3);
            if (end > 0) {
                String frontmatter = content.substring(3, end);
                body = content.substring(end + 3);
                if (body.startsWith("\n")) {
                    body = body.substring(1);
                }
                for (String line : frontmatter.split("\n")) {
                    if (line.strip().startsWith("version:")) {
                        version = line.substring(line.indexOf(':') + 1).strip();
                    }
                }
            }
        }
        return new Prompt(name, version, body.strip());
    }

    private String filenameVersion(String name) {
        int idx = name.lastIndexOf("-v");
        if (idx >= 0 && name.substring(idx + 2).chars().allMatch(Character::isDigit)) {
            return name.substring(idx + 1);
        }
        return "0";
    }

    public record Prompt(String name, String version, String system) {
    }
}
