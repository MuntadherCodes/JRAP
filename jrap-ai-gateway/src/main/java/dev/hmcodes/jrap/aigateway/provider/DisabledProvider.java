package dev.hmcodes.jrap.aigateway.provider;

/**
 * Default provider when no LLM is configured: deterministic parsers carry the whole
 * load and low-confidence extractions go to the human review queue instead of an LLM.
 */
public class DisabledProvider implements LlmProvider {

    @Override
    public ProviderResponse complete(String prompt, int maxOutputTokens) {
        throw new LlmProviderException("LLM provider is disabled (jrap.ai.provider=disabled)");
    }

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public String modelId() {
        return "disabled";
    }
}
