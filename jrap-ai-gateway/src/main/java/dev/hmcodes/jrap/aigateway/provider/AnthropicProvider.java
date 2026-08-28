package dev.hmcodes.jrap.aigateway.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Anthropic Messages API over plain HTTPS (no SDK dependency). Model id and API key are
 * configuration, never code (CON-4). Switching providers is a config change.
 */
public class AnthropicProvider implements LlmProvider {

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public AnthropicProvider(ObjectMapper objectMapper, String baseUrl, String apiKey, String model) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public ProviderResponse complete(String prompt, int maxOutputTokens) {
        try {
            ObjectNode body = objectMapper.createObjectNode();
            body.put("model", model);
            body.put("max_tokens", maxOutputTokens);
            ObjectNode message = body.putArray("messages").addObject();
            message.put("role", "user");
            message.put("content", prompt);

            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/v1/messages"))
                    .timeout(Duration.ofSeconds(120))
                    .header("Content-Type", "application/json")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .POST(HttpRequest.BodyPublishers.ofString(
                            objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new LlmProviderException("Anthropic API returned HTTP " + response.statusCode()
                        + ": " + truncate(response.body()));
            }
            JsonNode root = objectMapper.readTree(response.body());
            StringBuilder text = new StringBuilder();
            for (JsonNode block : root.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    text.append(block.path("text").asText());
                }
            }
            JsonNode usage = root.path("usage");
            return new ProviderResponse(text.toString(), root.path("model").asText(model),
                    usage.hasNonNull("input_tokens") ? usage.get("input_tokens").asInt() : null,
                    usage.hasNonNull("output_tokens") ? usage.get("output_tokens").asInt() : null);
        } catch (IOException e) {
            throw new LlmProviderException("Anthropic API call failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmProviderException("Interrupted during Anthropic API call", e);
        }
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public String modelId() {
        return model;
    }

    private static String truncate(String body) {
        return body == null ? "" : body.length() > 300 ? body.substring(0, 300) : body;
    }
}
