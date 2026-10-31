package com.ecommerce.agent;

import com.ecommerce.data.RecommendationDataService;
import com.ecommerce.connector.CommerceConnector;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.RecommendationModeResolver;
import com.ecommerce.service.TracedRecommendationLlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProductRecAgentRerankTest {

    @Test
    void acceptsCompatibleStructuredOutputAndNormalizesWhitelistedIds() throws Exception {
        TracedRecommendationLlmClient llm = scriptedLlm("{\"productIds\":[\" P-2 \",\"P-1\"]}");
        LlmCallBudget budget = new LlmCallBudget(2);

        AgentResult result = execute(llm, budget);

        assertThat(productIds(result)).containsExactly("P-2", "P-1");
        assertThat(diagnostics(result)).containsEntry("status", "llm_success")
                .containsEntry("fallbackUsed", false)
                .containsEntry("retryCount", 0)
                .containsEntry("invalidIdCount", 0)
                .containsEntry("candidateCount", 2);
        assertThat(budget.getCallCount()).isEqualTo(1);
    }

    @Test
    void retriesEmptySelectionOnceAndUsesSuccessfulRetry() throws Exception {
        TracedRecommendationLlmClient llm = scriptedLlm("[]", "{\"selectedProductIds\":[\"P-1\"]}");
        LlmCallBudget budget = new LlmCallBudget(2);

        AgentResult result = execute(llm, budget);

        assertThat(productIds(result)).containsExactly("P-1", "P-2");
        assertThat(diagnostics(result)).containsEntry("status", "llm_retry_success")
                .containsEntry("fallbackUsed", false)
                .containsEntry("firstFailureReason", "empty_rerank_selection")
                .containsEntry("retryCount", 1);
        assertThat(budget.getCallCount()).isEqualTo(2);
        assertThat(budget.snapshot()).containsEntry("duplicateCallsBlocked", 0)
                .containsEntry("providerCallCount", 2);
        verify(llm, times(2)).complete(anyString(), anyString(), anyString(), any(), anyString(), any());
        ArgumentCaptor<String> fingerprints = ArgumentCaptor.forClass(String.class);
        verify(llm, times(2)).complete(anyString(), anyString(), anyString(), any(),
                fingerprints.capture(), any());
        assertThat(fingerprints.getAllValues()).containsExactly(
                "product_rerank:test", "product_rerank:test:retry:1");
    }

    @Test
    void rejectsIdsOutsideCandidateWhitelistWithoutRetry() throws Exception {
        TracedRecommendationLlmClient llm = scriptedLlm("{\"rankedProductIds\":[\"P-UNKNOWN\"]}");
        LlmCallBudget budget = new LlmCallBudget(2);
        List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
        budget.setTraceListener(traces::add);

        AgentResult result = execute(llm, budget);

        assertThat(productIds(result)).containsExactly("P-1", "P-2");
        assertThat(diagnostics(result)).containsEntry("status", "safe_fallback")
                .containsEntry("fallbackUsed", true)
                .containsEntry("firstFailureReason", "product_outside_candidate_set")
                .containsEntry("finalFailureReason", "product_outside_candidate_set")
                .containsEntry("retryCount", 0)
                .containsEntry("invalidIdCount", 1)
                .containsEntry("selectedCount", 2);
        assertThat(budget.getCallCount()).isEqualTo(1);
        assertThat(budget.snapshot()).containsEntry("budgetBlockedCalls", 0)
                .containsEntry("duplicateCallsBlocked", 0);
        assertThat(traces).singleElement().satisfies(trace -> {
            assertThat(trace.providerInvoked()).isTrue();
            assertThat(trace.status()).isEqualTo("fallback");
            assertThat(trace.fallbackReason()).isEqualTo("product_outside_candidate_set");
        });
        verify(llm, times(1)).complete(anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void preservesFirstCauseWhenRetryAlsoFailsAndFallsBackSafely() throws Exception {
        TracedRecommendationLlmClient llm = scriptedLlm("[]", "not-json");
        LlmCallBudget budget = new LlmCallBudget(2);
        List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
        budget.setTraceListener(traces::add);

        AgentResult result = execute(llm, budget);

        assertThat(productIds(result)).containsExactly("P-1", "P-2");
        assertThat(diagnostics(result)).containsEntry("status", "safe_fallback")
                .containsEntry("firstFailureReason", "empty_rerank_selection")
                .containsEntry("finalFailureReason", "invalid_json")
                .containsEntry("retrySkippedReason", null)
                .containsEntry("retryCount", 1)
                .containsEntry("candidateCount", 2)
                .containsEntry("selectedCount", 2);
        assertThat(budget.getCallCount()).isEqualTo(2);
        assertThat(traces).extracting(LlmCallBudget.CallTrace::fallbackReason)
                .containsExactly("empty_rerank_selection", "invalid_json");
        assertThat(traces).allSatisfy(trace -> {
            assertThat(trace.providerInvoked()).isTrue();
            assertThat(trace.status()).isEqualTo("fallback");
        });
    }

    @Test
    void reportsRetryAsSkippedWhenConcurrentCallExhaustsBudgetBeforeReservation() throws Exception {
        LlmCallBudget budget = new LlmCallBudget(2);
        TracedRecommendationLlmClient llm = scriptedLlm(
                "[]",
                new BeforeReserve(() -> assertThat(budget.reserve("other_phase", "concurrent-call").acquired()).isTrue(),
                        "{\"productIds\":[\"P-2\"]}"));
        List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
        budget.setTraceListener(traces::add);

        AgentResult result = execute(llm, budget);

        assertThat(productIds(result)).containsExactly("P-1", "P-2");
        assertThat(diagnostics(result)).containsEntry("status", "safe_fallback")
                .containsEntry("firstFailureReason", "empty_rerank_selection")
                .containsEntry("finalFailureReason", "empty_rerank_selection")
                .containsEntry("retrySkippedReason", "llm_budget_exhausted")
                .containsEntry("retryCount", 0);
        assertThat(budget.snapshot()).containsEntry("llmCallCount", 2)
                .containsEntry("providerCallCount", 1)
                .containsEntry("budgetBlockedCalls", 1);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(1).asMap()).containsEntry("providerInvoked", false)
                .containsEntry("fallbackReason", "llm_budget_exhausted");
    }

    @Test
    void reportsRetryAsSkippedWhenRetryFingerprintWasReservedConcurrently() throws Exception {
        LlmCallBudget budget = new LlmCallBudget(3);
        TracedRecommendationLlmClient llm = scriptedLlm(
                "[]",
                new BeforeReserve(() -> assertThat(budget.reserve(
                                "product_rerank", "product_rerank:test:retry:1").acquired()).isTrue(),
                        "{\"productIds\":[\"P-2\"]}"));
        List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
        budget.setTraceListener(traces::add);

        AgentResult result = execute(llm, budget);

        assertThat(productIds(result)).containsExactly("P-1", "P-2");
        assertThat(diagnostics(result)).containsEntry("status", "safe_fallback")
                .containsEntry("firstFailureReason", "empty_rerank_selection")
                .containsEntry("finalFailureReason", "empty_rerank_selection")
                .containsEntry("retrySkippedReason", "duplicate_call_blocked")
                .containsEntry("retryCount", 0);
        assertThat(budget.snapshot()).containsEntry("llmCallCount", 2)
                .containsEntry("providerCallCount", 1)
                .containsEntry("duplicateCallsBlocked", 1);
        assertThat(traces).hasSize(2);
        assertThat(traces.get(1).asMap()).containsEntry("providerInvoked", false)
                .containsEntry("fallbackReason", "duplicate_call_blocked");
    }

    @Test
    void doesNotRetryOrExceedBudgetWhenNoCallRemains() throws Exception {
        TracedRecommendationLlmClient llm = scriptedLlm("[]", "{\"productIds\":[\"P-2\"]}");
        LlmCallBudget budget = new LlmCallBudget(1);
        List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
        budget.setTraceListener(traces::add);

        AgentResult result = execute(llm, budget);

        assertThat(productIds(result)).containsExactly("P-1", "P-2");
        assertThat(diagnostics(result)).containsEntry("status", "safe_fallback")
                .containsEntry("firstFailureReason", "empty_rerank_selection")
                .containsEntry("finalFailureReason", "empty_rerank_selection")
                .containsEntry("retrySkippedReason", "llm_budget_exhausted")
                .containsEntry("retryCount", 0)
                .containsEntry("selectedCount", 2);
        assertThat(budget.getCallCount()).isEqualTo(1);
        assertThat(budget.snapshot()).containsEntry("budgetBlockedCalls", 0)
                .containsEntry("duplicateCallsBlocked", 0);
        verify(llm, times(1)).complete(anyString(), anyString(), anyString(), any(), anyString(), any());
        assertThat(traces).hasSize(2);
        assertThat(traces.get(0).asMap()).containsEntry("providerInvoked", true)
                .containsEntry("status", "fallback")
                .containsEntry("fallbackReason", "empty_rerank_selection");
        assertThat(traces.get(1).asMap()).containsEntry("providerInvoked", false)
                .containsEntry("status", "fallback")
                .containsEntry("fallbackReason", "llm_budget_exhausted");
    }

    @Test
    void retriesInvalidJsonAndInvalidObjectShapeButKeepsTheFirstFailureTrace() throws Exception {
        for (Map.Entry<String, String> invalid : Map.of(
                "not-json", "invalid_json",
                "{}", "invalid_model_output").entrySet()) {
            TracedRecommendationLlmClient llm = scriptedLlm(
                    invalid.getKey(), "{\"productIds\":[\"P-2\"]}");
            LlmCallBudget budget = new LlmCallBudget(2);
            List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
            budget.setTraceListener(traces::add);

            AgentResult result = execute(llm, budget);

            assertThat(diagnostics(result)).containsEntry("status", "llm_retry_success")
                    .containsEntry("fallbackUsed", false)
                    .containsEntry("firstFailureReason", invalid.getValue())
                    .containsEntry("finalFailureReason", null)
                    .containsEntry("retryCount", 1);
            assertThat(traces).hasSize(2);
            assertThat(traces.get(0).asMap()).containsEntry("providerInvoked", true)
                    .containsEntry("status", "fallback")
                    .containsEntry("fallbackReason", invalid.getValue());
            assertThat(traces.get(1).asMap()).containsEntry("providerInvoked", true)
                    .containsEntry("status", "success")
                    .containsEntry("fallbackReason", null);
        }
    }

    @Test
    void providerTimeoutAndErrorDoNotRetryAndRemainVisibleInTraceAndDiagnostics() throws Exception {
        for (String reason : List.of("provider_timeout", "provider_error")) {
            TracedRecommendationLlmClient llm = failingLlm(reason);
            LlmCallBudget budget = new LlmCallBudget(2);
            List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
            budget.setTraceListener(traces::add);

            AgentResult result = execute(llm, budget);

            assertThat(productIds(result)).containsExactly("P-1", "P-2");
            assertThat(diagnostics(result)).containsEntry("status", "safe_fallback")
                    .containsEntry("fallbackUsed", true)
                    .containsEntry("firstFailureReason", reason)
                    .containsEntry("finalFailureReason", reason)
                    .containsEntry("retryCount", 0)
                    .containsEntry("selectedCount", 2);
            assertThat(budget.getCallCount()).isEqualTo(1);
            assertThat(traces).singleElement().satisfies(trace -> {
                assertThat(trace.providerInvoked()).isTrue();
                assertThat(trace.status()).isEqualTo("fallback");
                assertThat(trace.fallbackReason()).isEqualTo(reason);
            });
            verify(llm, times(1)).complete(anyString(), anyString(), anyString(), any(), anyString(), any());
        }
    }

    @Test
    void emptyCandidateSetDoesNotInvokeTheProviderAndReportsZeroSelection() throws Exception {
        TracedRecommendationLlmClient llm = scriptedLlm("must-not-be-used");
        LlmCallBudget budget = new LlmCallBudget(2);

        AgentResult result = execute(llm, budget, List.of());

        assertThat(productIds(result)).isEmpty();
        assertThat(diagnostics(result)).containsEntry("status", "rules")
                .containsEntry("fallbackUsed", false)
                .containsEntry("candidateCount", 0)
                .containsEntry("selectedCount", 0)
                .containsEntry("retryCount", 0);
        assertThat(budget.getCallCount()).isZero();
        verify(llm, times(0)).complete(anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    private AgentResult execute(TracedRecommendationLlmClient llm, LlmCallBudget budget) throws Exception {
        return execute(llm, budget, List.of(product("P-1"), product("P-2")));
    }

    private AgentResult execute(TracedRecommendationLlmClient llm, LlmCallBudget budget,
                                List<Product> candidates) throws Exception {
        CommerceConnector connector = mock(CommerceConnector.class);
        when(connector.platform()).thenReturn("shopify");
        ProductRecAgent agent = new ProductRecAgent(mock(ChatClient.Builder.class), List.of(connector),
                new RecommendationModeResolver("LLM", "test-key"), mock(RecommendationDataService.class), llm);
        RecommendationRequest request = RecommendationRequest.builder()
                .userId("test-user").scene("homepage").numItems(2)
                .platform("shopify").region("SEA").country("MY")
                .locale("en-MY").currency("MYR").build();
        UserProfile profile = UserProfile.builder().preferredCategories(List.of("sports"))
                .priceRange(new double[]{0, 500}).build();
        Map<String, Object> params = new HashMap<>();
        params.put("request", request);
        params.put("userProfile", profile);
        params.put("candidateProducts", candidates);
        params.put("numItems", 2);
        params.put("llmBudget", budget);
        params.put("llmFingerprint", "product_rerank:test");
        return agent.execute(params);
    }

    private Product product(String id) {
        return Product.builder().productId(id).name("Product " + id).category("sports")
                .price(99).stock(5).supportedRegions(List.of("MY", "SEA")).currency("MYR")
                .warehouseRegion("MY").deliveryDays(2).platform("shopify")
                .crossBorderEligible(true).build();
    }

    @SuppressWarnings("unchecked")
    private List<String> productIds(AgentResult result) {
        return ((List<Product>) result.getData().get("products")).stream().map(Product::getProductId).toList();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> diagnostics(AgentResult result) {
        return (Map<String, Object>) result.getData().get("rerank_diagnostics");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private TracedRecommendationLlmClient scriptedLlm(Object... responses) {
        TracedRecommendationLlmClient client = mock(TracedRecommendationLlmClient.class);
        AtomicInteger index = new AtomicInteger();
        when(client.timeoutMs()).thenReturn(15_000L);
        doAnswer(invocation -> {
            LlmCallBudget budget = invocation.getArgument(1);
            String reason = invocation.getArgument(2);
            budget.recordTrace(new LlmCallBudget.CallTrace("product_rerank", "test", "test", 0,
                    "fallback", reason, null, null, null, "unavailable", false));
            return null;
        }).when(client).recordLocalFallback(anyString(), any(LlmCallBudget.class), anyString());
        when(client.complete(anyString(), anyString(), anyString(), any(LlmCallBudget.class),
                anyString(), any(TracedRecommendationLlmClient.OutputParser.class))).thenAnswer(invocation -> {
            String phase = invocation.getArgument(0);
            LlmCallBudget budget = invocation.getArgument(3);
            String fingerprint = invocation.getArgument(4);
            TracedRecommendationLlmClient.OutputParser parser = invocation.getArgument(5);
            Object response = responses[Math.min(index.getAndIncrement(), responses.length - 1)];
            if (response instanceof BeforeReserve beforeReserve) {
                beforeReserve.action().run();
                response = beforeReserve.response();
            }
            LlmCallBudget.Reservation reservation = budget.reserve(phase, fingerprint);
            if (!reservation.acquired()) {
                budget.recordTrace(trace("fallback", reservation.reason(), false));
                throw new TracedRecommendationLlmClient.TracedLlmException(reservation.reason());
            }
            try {
                Object parsed = parser.parse(String.valueOf(response));
                budget.recordTrace(trace("success", null));
                return parsed;
            } catch (TracedRecommendationLlmClient.OutputValidationException validation) {
                budget.recordTrace(trace("fallback", validation.reason()));
                throw new TracedRecommendationLlmClient.TracedLlmException(validation.reason());
            } catch (JsonProcessingException invalidJson) {
                budget.recordTrace(trace("fallback", "invalid_json"));
                throw new TracedRecommendationLlmClient.TracedLlmException("invalid_json");
            } catch (Exception invalidOutput) {
                budget.recordTrace(trace("fallback", "invalid_model_output"));
                throw new TracedRecommendationLlmClient.TracedLlmException("invalid_model_output");
            }
        });
        return client;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private TracedRecommendationLlmClient failingLlm(String reason) {
        TracedRecommendationLlmClient client = mock(TracedRecommendationLlmClient.class);
        when(client.timeoutMs()).thenReturn(15_000L);
        when(client.complete(anyString(), anyString(), anyString(), any(LlmCallBudget.class),
                anyString(), any(TracedRecommendationLlmClient.OutputParser.class))).thenAnswer(invocation -> {
            String phase = invocation.getArgument(0);
            LlmCallBudget budget = invocation.getArgument(3);
            String fingerprint = invocation.getArgument(4);
            LlmCallBudget.Reservation reservation = budget.reserve(phase, fingerprint);
            if (!reservation.acquired()) {
                throw new TracedRecommendationLlmClient.TracedLlmException(reservation.reason());
            }
            budget.recordTrace(trace("fallback", reason));
            throw new TracedRecommendationLlmClient.TracedLlmException(reason);
        });
        return client;
    }

    private LlmCallBudget.CallTrace trace(String status, String reason) {
        return trace(status, reason, true);
    }

    private LlmCallBudget.CallTrace trace(String status, String reason, boolean providerInvoked) {
        return new LlmCallBudget.CallTrace("product_rerank", "test", "test", 1,
                status, reason, null, null, null, "unavailable", providerInvoked);
    }

    private record BeforeReserve(Runnable action, String response) {}
}
