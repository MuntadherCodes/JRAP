package dev.hmcodes.jrap.aigateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.hmcodes.jrap.aigateway.domain.LlmCall;
import dev.hmcodes.jrap.aigateway.provider.LlmProvider;
import dev.hmcodes.jrap.aigateway.provider.LlmProviderException;
import dev.hmcodes.jrap.aigateway.repo.LlmCallRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmGatewayTest {

    private final LlmCallRepository calls = mock(LlmCallRepository.class);

    @SuppressWarnings("unchecked")
    private LlmGateway gateway(LlmProvider provider, long budget) {
        ObjectProvider<LlmProvider> override = mock(ObjectProvider.class);
        when(override.getIfAvailable()).thenReturn(provider);
        return new LlmGateway(new PromptRegistry(""), calls, new ObjectMapper(),
                Clock.systemUTC(), "disabled", "http://unused", "", "test-model", budget, override);
    }

    private static LlmProvider fakeProvider() {
        return new LlmProvider() {
            @Override
            public ProviderResponse complete(String prompt, int maxOutputTokens) {
                return new ProviderResponse("[{\"name\":\"X\"}]", "fake-model", 100, 20);
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public String modelId() {
                return "fake-model";
            }
        };
    }

    @Test
    void successfulCallIsLoggedWithPromptVersionAndTokens() {
        when(calls.tokensUsedForAudit(any())).thenReturn(0L);
        LlmGateway gateway = gateway(fakeProvider(), 100_000);
        UUID auditId = UUID.randomUUID();

        LlmGateway.GatewayResult result = gateway.complete("board-extraction",
                Map.of("page_text", "text"), auditId, List.of(UUID.randomUUID()), 1000);

        assertThat(result.ok()).isTrue();
        assertThat(result.promptVersion()).isEqualTo("v1");
        ArgumentCaptor<LlmCall> saved = ArgumentCaptor.forClass(LlmCall.class);
        verify(calls).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("OK");
        assertThat(saved.getValue().getModel()).isEqualTo("fake-model");
        assertThat(saved.getValue().getInputTokens()).isEqualTo(100);
        assertThat(saved.getValue().getPromptVersion()).isEqualTo("v1");
    }

    @Test
    void budgetExhaustionBlocksTheCallAndLogsIt() {
        when(calls.tokensUsedForAudit(any())).thenReturn(1_000_000L);
        LlmGateway gateway = gateway(fakeProvider(), 200_000);

        LlmGateway.GatewayResult result = gateway.complete("board-extraction",
                Map.of("page_text", "text"), UUID.randomUUID(), List.of(), 1000);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("llm-budget-exceeded");
        ArgumentCaptor<LlmCall> saved = ArgumentCaptor.forClass(LlmCall.class);
        verify(calls).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo("BUDGET_EXCEEDED");
    }

    @Test
    void providerFailureDegradesWithoutThrowing() {
        when(calls.tokensUsedForAudit(any())).thenReturn(0L);
        LlmProvider failing = new LlmProvider() {
            @Override
            public ProviderResponse complete(String prompt, int maxOutputTokens) {
                throw new LlmProviderException("boom");
            }

            @Override
            public boolean isEnabled() {
                return true;
            }

            @Override
            public String modelId() {
                return "failing-model";
            }
        };
        LlmGateway gateway = gateway(failing, 100_000);

        LlmGateway.GatewayResult result = gateway.complete("board-extraction",
                Map.of("page_text", "text"), UUID.randomUUID(), List.of(), 1000);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("llm-error");
    }

    @Test
    void disabledProviderShortCircuits() {
        @SuppressWarnings("unchecked")
        ObjectProvider<LlmProvider> none = mock(ObjectProvider.class);
        when(none.getIfAvailable()).thenReturn(null);
        LlmGateway gateway = new LlmGateway(new PromptRegistry(""), calls, new ObjectMapper(),
                Clock.systemUTC(), "disabled", "http://unused", "", "m", 1000, none);

        LlmGateway.GatewayResult result = gateway.complete("board-extraction",
                Map.of(), UUID.randomUUID(), List.of(), 100);

        assertThat(result.ok()).isFalse();
        assertThat(result.error()).isEqualTo("llm-disabled");
        assertThat(gateway.isEnabled()).isFalse();
    }
}
