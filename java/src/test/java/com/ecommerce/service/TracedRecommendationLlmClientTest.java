package com.ecommerce.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TracedRecommendationLlmClientTest {
    private ThreadPoolExecutor executor;
    private TracedRecommendationLlmClient client;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new java.util.concurrent.ArrayBlockingQueue<>(2));
        meters = new SimpleMeterRegistry();
        client = new TracedRecommendationLlmClient(mock(ChatClient.class), "test-provider",
                "configured-model", 1000, executor, meters);
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
        meters.close();
    }

    @Test
    void recordsProviderUsageWithoutPersistingPromptsOrOutput() {
        ChatResponse response = response("provider-model", 12, 7, 19, "{\"ok\":true}");
        LlmCallBudget budget = new LlmCallBudget(1);
        List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
        budget.setTraceListener(traces::add);

        boolean parsed = client.completeResponse("supervisor", budget, "opaque-run-fingerprint",
                raw -> new ObjectMapper().readTree(raw).path("ok").asBoolean(), () -> response);

        assertThat(parsed).isTrue();
        assertThat(budget.snapshot()).containsEntry("promptTokens", 12L)
                .containsEntry("completionTokens", 7L).containsEntry("totalTokens", 19L)
                .containsEntry("usageStatus", "available");
        assertThat(traces).hasSize(1);
        Map<String, Object> trace = traces.get(0).asMap();
        assertThat(trace).containsEntry("phase", "supervisor")
                .containsEntry("provider", "test-provider")
                .containsEntry("model", "provider-model")
                .containsEntry("status", "success")
                .containsEntry("providerInvoked", true)
                .containsEntry("promptTokens", 12)
                .containsEntry("completionTokens", 7)
                .containsEntry("totalTokens", 19)
                .containsEntry("usageStatus", "available");
        assertThat(trace.toString()).doesNotContain("private prompt", "raw model output", "sk-secret");
        assertThat(meters.get("agent.llm.calls").tags("phase", "supervisor", "provider", "test-provider",
                "model", "configured-model", "outcome", "success").counter().count()).isEqualTo(1);
        assertThat(meters.get("agent.llm.duration").tags("phase", "supervisor", "provider", "test-provider",
                "model", "configured-model", "outcome", "success").timer().count()).isEqualTo(1);
        assertThat(meters.get("agent.llm.tokens").tags("phase", "supervisor", "provider", "test-provider",
                "model", "configured-model", "token_type", "total").summary().totalAmount()).isEqualTo(19);
        assertThat(meters.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().equals("provider-model"))).isTrue();
    }

    @Test
    void recordsStructuredValidationFallbackWithoutLeakingModelOutput() {
        LlmCallBudget budget = new LlmCallBudget(1);
        List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
        budget.setTraceListener(traces::add);

        assertThatThrownBy(() -> client.completeInjected("specialist_product", budget, "safe-fingerprint",
                () -> "raw model output sk-secret", raw -> {
                    throw new TracedRecommendationLlmClient.OutputValidationException("tool_outside_candidate_set");
                })).isInstanceOf(TracedRecommendationLlmClient.TracedLlmException.class)
                .hasMessage("tool_outside_candidate_set")
                .hasNoCause()
                .satisfies(error -> assertThat(error.toString()).doesNotContain("raw model output", "sk-secret"));

        assertThat(traces).singleElement().satisfies(trace -> {
            assertThat(trace.status()).isEqualTo("fallback");
            assertThat(trace.fallbackReason()).isEqualTo("tool_outside_candidate_set");
            assertThat(trace.providerInvoked()).isTrue();
            assertThat(trace.asMap().toString()).doesNotContain("raw model output", "sk-secret");
        });
        assertThat(budget.snapshot()).containsEntry("fallbackCount", 1);
        assertThat(meters.get("agent.llm.failures").tag("reason", "tool_outside_candidate_set").counter().count())
                .isEqualTo(1);
    }

    @Test
    void budgetRejectionIsMeasuredWithoutCallingTheProviderOrAddingDynamicLabels() {
        LlmCallBudget budget = new LlmCallBudget(0);
        assertThatThrownBy(() -> client.completeInjected("customer-private-value", budget, "fingerprint",
                () -> "must not run", raw -> raw))
                .isInstanceOf(TracedRecommendationLlmClient.TracedLlmException.class)
                .hasMessage("llm_budget_exhausted");

        assertThat(meters.get("agent.llm.budget.blocked").tag("phase", "other")
                .tag("reason", "llm_budget_exhausted").counter().count()).isEqualTo(1);
        assertThat(meters.getMeters().stream().flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().contains("customer-private-value"))).isTrue();
    }

    @Test
    void providerExceptionsProduceSafeFallbackReason() {
        LlmCallBudget budget = new LlmCallBudget(1);
        List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
        budget.setTraceListener(traces::add);

        assertThatThrownBy(() -> client.completeResponse("final_decision", budget, "fingerprint",
                raw -> raw, () -> { throw new IllegalStateException("provider response sk-secret"); }))
                .isInstanceOf(TracedRecommendationLlmClient.TracedLlmException.class)
                .hasMessage("provider_error").hasNoCause();

        assertThat(traces).singleElement().satisfies(trace -> {
            assertThat(trace.fallbackReason()).isEqualTo("provider_error");
            assertThat(trace.asMap().toString()).doesNotContain("provider response", "sk-secret");
        });
    }

    private ChatResponse response(String model, int promptTokens, int completionTokens,
                                  int totalTokens, String output) {
        ChatResponse response = mock(ChatResponse.class);
        ChatResponseMetadata metadata = mock(ChatResponseMetadata.class);
        Usage usage = mock(Usage.class);
        Generation generation = mock(Generation.class);
        AssistantMessage message = mock(AssistantMessage.class);
        when(response.getMetadata()).thenReturn(metadata);
        when(metadata.getModel()).thenReturn(model);
        when(metadata.getUsage()).thenReturn(usage);
        when(usage.getPromptTokens()).thenReturn(promptTokens);
        when(usage.getCompletionTokens()).thenReturn(completionTokens);
        when(usage.getTotalTokens()).thenReturn(totalTokens);
        when(response.getResult()).thenReturn(generation);
        when(generation.getOutput()).thenReturn(message);
        when(message.getText()).thenReturn(output);
        return response;
    }
}
