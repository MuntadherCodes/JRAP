package dev.hmcodes.jrap.analysis.rubric;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Loads rubric versions from the classpath (rubric/v{version}.json). */
@Component
public class RubricLoader {

    private final ObjectMapper objectMapper;
    private final String activeVersion;
    private final Map<String, Rubric> cache = new ConcurrentHashMap<>();

    public RubricLoader(ObjectMapper objectMapper,
                        @Value("${jrap.analysis.rubric-version:1.0}") String activeVersion) {
        this.objectMapper = objectMapper;
        this.activeVersion = activeVersion;
    }

    public Rubric active() {
        return load(activeVersion);
    }

    public Rubric load(String version) {
        return cache.computeIfAbsent(version, v -> {
            String path = "/rubric/v" + v + ".json";
            try (InputStream in = RubricLoader.class.getResourceAsStream(path)) {
                if (in == null) {
                    throw new IllegalStateException("Rubric not found on classpath: " + path);
                }
                JsonNode root = objectMapper.readTree(in);
                Map<String, Double> thresholds = new HashMap<>();
                root.path("thresholds").fields().forEachRemaining(entry ->
                        thresholds.put(entry.getKey(), entry.getValue().asDouble()));
                Map<String, Integer> deltas = new HashMap<>();
                root.path("deltas").fields().forEachRemaining(entry ->
                        deltas.put(entry.getKey(), entry.getValue().asInt()));
                return new Rubric(root.path("version").asText(v), thresholds, deltas);
            } catch (IOException e) {
                throw new IllegalStateException("Failed to read rubric " + path, e);
            }
        });
    }
}
