package dev.hmcodes.jrap.aigateway.provider;

/**
 * A hosted-LLM backend. Implementations are reachable ONLY through {@code LlmGateway}
 * (CON-4): no other JRAP component may call an LLM directly.
 */
public interface LlmProvider {

    record ProviderResponse(String text, String model, Integer inputTokens, Integer outputTokens) {}

    /** @throws LlmProviderException on transport or API failure. */
    ProviderResponse complete(String prompt, int maxOutputTokens);

    boolean isEnabled();

    String modelId();
}
