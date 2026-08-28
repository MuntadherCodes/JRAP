package dev.hmcodes.jrap.aigateway;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.aigateway.domain.LlmCall;
import dev.hmcodes.jrap.aigateway.provider.AnthropicProvider;
import dev.hmcodes.jrap.aigateway.provider.DisabledProvider;
import dev.hmcodes.jrap.aigateway.provider.LlmProvider;
import dev.hmcodes.jrap.aigateway.provider.LlmProviderException;
import dev.hmcodes.jrap.aigateway.repo.LlmCallRepository;
import dev.hmcodes.jrap.common.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * THE single LLM entry point (CON-4). Renders a versioned prompt, enforces the
 * per-audit token budget (FR-BILL-2 mechanism), calls the configured provider, and
 * logs every call immutably (NFR-AI-1). LLMs extract and draft — they never score.
 */
@Service
public class LlmGateway {

    private static final Logger log = LoggerFactory.getLogger(LlmGateway.class);

    public record GatewayResult(boolean ok, String text, String promptVersion, String error) {

        public static GatewayResult unavailable(String reason) {
            return new GatewayResult(false, null, null, reason);
        }
    }

    private final PromptRegistry prompts;
    private final LlmCallRepository calls;
    private final LlmProvider provider;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final long auditTokenBudget;

    public LlmGateway(PromptRegistry prompts, LlmCallRepository calls, ObjectMapper objectMapper,
                      Clock clock,
                      @Value("${jrap.ai.provider:disabled}") String providerName,
                      @Value("${jrap.ai.base-url:https://api.anthropic.com}") String baseUrl,
                      @Value("${jrap.ai.api-key:}") String apiKey,
                      @Value("${jrap.ai.model:claude-3-5-haiku-latest}") String model,
                      @Value("${jrap.ai.audit-token-budget:200000}") long auditTokenBudget,
                      ObjectProvider<LlmProvider> providerOverride) {
        this.prompts = prompts;
        this.calls = calls;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.auditTokenBudget = auditTokenBudget;
        LlmProvider overridden = providerOverride.getIfAvailable();
        if (overridden != null) {
            this.provider = overridden; // tests inject a fake provider bean
        } else if ("anthropic".equalsIgnoreCase(providerName)) {
            this.provider = new AnthropicProvider(objectMapper, baseUrl, apiKey, model);
        } else {
            this.provider = new DisabledProvider();
        }
    }

    public boolean isEnabled() {
        return provider.isEnabled();
    }

    /**
     * Completes a prompt for an audit. Never throws: extraction degrades to the human
     * review queue when the LLM is disabled, over budget, or failing (FR-INT-6 spirit).
     */
    public GatewayResult complete(String promptName, Map<String, String> variables,
                                  UUID auditId, List<UUID> inputSnapshotIds, int maxOutputTokens) {
        if (!provider.isEnabled()) {
            return GatewayResult.unavailable("llm-disabled");
        }
        String version = prompts.activeVersion(promptName);
        String prompt = prompts.render(promptName, variables);
        long used = calls.tokensUsedForAudit(auditId);
        if (used + prompt.length() / 4 > auditTokenBudget) {
            logCall(promptName, version, auditId, inputSnapshotIds, prompt.length(),
                    null, null, "BUDGET_EXCEEDED", "audit token budget exhausted", null);
            return GatewayResult.unavailable("llm-budget-exceeded");
        }
        try {
            LlmProvider.ProviderResponse response = provider.complete(prompt, maxOutputTokens);
            logCall(promptName, version, auditId, inputSnapshotIds, prompt.length(),
                    response.inputTokens(), response.outputTokens(), "OK", null, response.text());
            return new GatewayResult(true, response.text(), version, null);
        } catch (LlmProviderException e) {
            log.warn("LLM call failed for prompt {}: {}", promptName, e.getMessage());
            logCall(promptName, version, auditId, inputSnapshotIds, prompt.length(),
                    null, null, "ERROR", e.getMessage(), null);
            return GatewayResult.unavailable("llm-error");
        }
    }

    private void logCall(String promptName, String version, UUID auditId, List<UUID> snapshotIds,
                         int requestChars, Integer inputTokens, Integer outputTokens,
                         String status, String error, String responseText) {
        try {
            calls.save(new LlmCall(UUID.randomUUID(),
                    TenantContext.organisationId().orElse(null), auditId,
                    promptName, version, provider.modelId(), toJson(snapshotIds), requestChars,
                    inputTokens, outputTokens, status, error, responseText, clock.instant()));
        } catch (Exception e) {
            log.error("Failed to log LLM call (continuing): {}", e.getMessage());
        }
    }

    private String toJson(List<UUID> ids) {
        try {
            return objectMapper.writeValueAsString(
                    ids == null ? List.of() : ids.stream().map(UUID::toString).toList());
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }
}
