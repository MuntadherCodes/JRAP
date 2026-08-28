package dev.hmcodes.jrap.aigateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Versioned prompt templates loaded from the classpath (prompts/{name}.{version}.txt).
 * Prompts and their active versions are configuration, not code (CON-4); which version
 * an audit used is recorded on every llm_call and extraction row.
 */
@Component
public class PromptRegistry {

    private final Map<String, String> activeVersions;
    private final Map<String, String> cache = new ConcurrentHashMap<>();

    /** @param pins comma-separated overrides, e.g. "board-extraction=v2,article-extraction=v1". */
    public PromptRegistry(@Value("${jrap.ai.prompt-versions:}") String pins) {
        Map<String, String> parsed = new java.util.HashMap<>();
        if (pins != null && !pins.isBlank()) {
            for (String pair : pins.split(",")) {
                String[] parts = pair.split("=", 2);
                if (parts.length == 2 && !parts[0].isBlank()) {
                    parsed.put(parts[0].trim(), parts[1].trim());
                }
            }
        }
        this.activeVersions = Map.copyOf(parsed);
    }

    public String activeVersion(String promptName) {
        return activeVersions.getOrDefault(promptName, "v1");
    }

    /** Renders the active version of a prompt, substituting {{variable}} placeholders. */
    public String render(String promptName, Map<String, String> variables) {
        String template = template(promptName, activeVersion(promptName));
        String rendered = template;
        for (Map.Entry<String, String> variable : variables.entrySet()) {
            rendered = rendered.replace("{{" + variable.getKey() + "}}",
                    variable.getValue() == null ? "" : variable.getValue());
        }
        return rendered;
    }

    String template(String promptName, String version) {
        String key = promptName + "." + version;
        return cache.computeIfAbsent(key, k -> {
            String path = "/prompts/" + promptName + "." + version + ".txt";
            try (InputStream in = PromptRegistry.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("Prompt template not found on classpath: " + path);
                }
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read prompt template " + path, e);
            }
        });
    }
}
