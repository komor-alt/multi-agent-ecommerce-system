package com.ecommerce.service;

import com.ecommerce.agent.InventoryAgent;
import com.ecommerce.agent.MarketingCopyAgent;
import com.ecommerce.agent.ProductRecAgent;
import com.ecommerce.agent.UserProfileAgent;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.Product;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationPipelineExecutorHookTest {

    @Test
    void executesRegisteredToolWithLifecycleHooks() {
        UserProfileAgent userProfileAgent = mock(UserProfileAgent.class);
        RecordingHook hook = new RecordingHook();
        RecommendationPipelineExecutor executor = new RecommendationPipelineExecutor(
                userProfileAgent,
                mock(ProductRecAgent.class),
                mock(InventoryAgent.class),
                mock(MarketingCopyAgent.class),
                Runnable::run,
                List.of(hook)
        );
        AgentResult profileResult = AgentResult.builder()
                .agentName("user_profile")
                .success(true)
                .data(Map.of("profile", UserProfile.builder().userId("user_001").build()))
                .build();
        when(userProfileAgent.runAsync(anyMap(), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(profileResult));

        RecommendationPipelineState state = new RecommendationPipelineState(
                "run_001",
                RecommendationRequest.builder().userId("user_001").country("SG").currency("SGD").build()
        );
        ToolObservation observation = executor.executeTool(RecommendationPipelineExecutor.GET_USER_PROFILE, state);

        assertThat(observation.getEvidenceIds()).containsExactly("profile:user_001");
        assertThat(state.getAgentResults()).containsKey(RecommendationPipelineExecutor.USER_PROFILE_RESULT);
        assertThat(executor.registeredToolNames()).contains("search_products", "check_inventory");
        assertThat(hook.beforeTools).containsExactly(RecommendationPipelineExecutor.GET_USER_PROFILE);
        assertThat(hook.afterTools).containsExactly(RecommendationPipelineExecutor.GET_USER_PROFILE);
        assertThat(hook.errors).isEmpty();
    }

    @Test
    void notifiesHookWhenToolExecutionFails() {
        UserProfileAgent userProfileAgent = mock(UserProfileAgent.class);
        RecordingHook hook = new RecordingHook();
        RecommendationPipelineExecutor executor = new RecommendationPipelineExecutor(
                userProfileAgent,
                mock(ProductRecAgent.class),
                mock(InventoryAgent.class),
                mock(MarketingCopyAgent.class),
                Runnable::run,
                List.of(hook)
        );
        AgentResult failed = AgentResult.builder()
                .agentName("user_profile")
                .success(false)
                .error("profile timeout")
                .build();
        when(userProfileAgent.runAsync(anyMap(), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(failed));

        RecommendationPipelineState state = new RecommendationPipelineState(
                "run_001",
                RecommendationRequest.builder().userId("user_001").build()
        );

        assertThatThrownBy(() -> executor.executeTool(RecommendationPipelineExecutor.GET_USER_PROFILE, state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile timeout");
        assertThat(hook.beforeTools).containsExactly(RecommendationPipelineExecutor.GET_USER_PROFILE);
        assertThat(hook.afterTools).isEmpty();
        assertThat(hook.errors).containsExactly("profile timeout");
    }

    @Test
    void exposesSanitizedRerankDiagnosticsInToolObservation() {
        ProductRecAgent productRecAgent = mock(ProductRecAgent.class);
        RecommendationPipelineExecutor executor = new RecommendationPipelineExecutor(
                mock(UserProfileAgent.class), productRecAgent, mock(InventoryAgent.class),
                mock(MarketingCopyAgent.class), Runnable::run, List.of());
        Product product = Product.builder().productId("P-1").name("Product 1").build();
        Map<String, Object> diagnostics = Map.of(
                "status", "safe_fallback", "fallbackUsed", true,
                "firstFailureReason", "empty_rerank_selection", "retryCount", 1,
                "candidateCount", 1, "invalidIdCount", 0, "selectedCount", 1);
        AgentResult rerankResult = AgentResult.builder().agentName("product_rec").success(true)
                .data(Map.of("products", List.of(product), "rerank_diagnostics", diagnostics)).build();
        when(productRecAgent.runAsync(anyMap(), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(rerankResult));

        RecommendationPipelineState state = new RecommendationPipelineState("run-rerank",
                RecommendationRequest.builder().userId("user-1").numItems(1).build());
        state.setProfile(UserProfile.builder().userId("user-1").build());
        state.setRawProducts(List.of(product));

        ToolObservation observation = executor.executeTool(RecommendationPipelineExecutor.RERANK, state);

        assertThat(observation.getData()).containsEntry("rerankDiagnostics", diagnostics);
        assertThat(observation.getData().get("rerankDiagnostics").toString())
                .doesNotContain("Product 1", "user-1", "P-1");
        assertThat(AutonomousAgentLoopService.rerankTraceDiagnostics(observation))
                .containsEntry("firstFailureReason", "empty_rerank_selection")
                .doesNotContainKey("products");
    }

    @Test
    void rerankFingerprintBlocksTheSameCandidateSnapshotButAllowsPromptRelevantChangesWithinBudget() {
        ProductRecAgent productRecAgent = mock(ProductRecAgent.class);
        RecommendationPipelineExecutor executor = new RecommendationPipelineExecutor(
                mock(UserProfileAgent.class), productRecAgent, mock(InventoryAgent.class),
                mock(MarketingCopyAgent.class), Runnable::run, List.of());
        when(productRecAgent.runAsync(anyMap(), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(AgentResult.builder()
                        .agentName("product_rec").success(true).data(Map.of("products", List.of())).build()));
        RecommendationRequest request = RecommendationRequest.builder()
                .userId("private-user").scene("homepage").numItems(2).build();
        UserProfile profile = UserProfile.builder().userId("private-user").build();
        Product first = Product.builder().productId("PRIVATE-P-1").build();
        Product second = Product.builder().productId("PRIVATE-P-2").build();
        Product replacement = Product.builder().productId("PRIVATE-P-3").build();
        Product updatedFirst = Product.builder().productId("PRIVATE-P-1").price(149).stock(2)
                .deliveryDays(5).currency("SGD").supportedRegions(List.of("SG")).build();
        LlmCallBudget budget = new LlmCallBudget(4);

        executor.rerankAsync(request, profile, List.of(first, second), budget).join();
        executor.rerankAsync(request, profile, List.of(first, second), budget).join();
        executor.rerankAsync(request, profile, List.of(second, first), budget).join();
        executor.rerankAsync(request, profile, List.of(first, replacement), budget).join();
        executor.rerankAsync(request, profile, List.of(updatedFirst, second), budget).join();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(productRecAgent, times(5)).runAsync(params.capture(), any(Executor.class));
        List<String> fingerprints = params.getAllValues().stream()
                .map(value -> String.valueOf(value.get("llmFingerprint"))).toList();
        assertThat(fingerprints.get(0)).isEqualTo(fingerprints.get(1));
        assertThat(fingerprints.get(2)).isNotEqualTo(fingerprints.get(0));
        assertThat(fingerprints.get(3)).isNotEqualTo(fingerprints.get(0));
        assertThat(fingerprints.get(4)).isNotEqualTo(fingerprints.get(0));
        assertThat(fingerprints).allSatisfy(value -> assertThat(value)
                .doesNotContain("PRIVATE-P-1", "PRIVATE-P-2", "PRIVATE-P-3"));

        assertThat(budget.reserve("product_rerank", fingerprints.get(0)).acquired()).isTrue();
        assertThat(budget.reserve("product_rerank", fingerprints.get(1)).reason())
                .isEqualTo("duplicate_call_blocked");
        assertThat(budget.reserve("product_rerank", fingerprints.get(2)).acquired()).isTrue();
        assertThat(budget.reserve("product_rerank", fingerprints.get(3)).acquired()).isTrue();
        assertThat(budget.reserve("product_rerank", fingerprints.get(4)).acquired()).isTrue();
        assertThat(budget.reserve("product_rerank", "another-candidate-digest").reason())
                .isEqualTo("llm_budget_exhausted");
        assertThat(budget.snapshot()).containsEntry("llmCallCount", 4)
                .containsEntry("duplicateCallsBlocked", 1)
                .containsEntry("budgetBlockedCalls", 1);
    }

    @Test
    void rerankFingerprintCoversEveryCandidateFieldRenderedIntoThePrompt() {
        ProductRecAgent productRecAgent = mock(ProductRecAgent.class);
        RecommendationPipelineExecutor executor = new RecommendationPipelineExecutor(
                mock(UserProfileAgent.class), productRecAgent, mock(InventoryAgent.class),
                mock(MarketingCopyAgent.class), Runnable::run, List.of());
        when(productRecAgent.runAsync(anyMap(), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(AgentResult.builder()
                        .agentName("product_rec").success(true).data(Map.of("products", List.of())).build()));
        RecommendationRequest request = RecommendationRequest.builder()
                .userId("private-user").scene("homepage").numItems(1).build();
        UserProfile profile = UserProfile.builder().userId("private-user").build();
        Product baseline = completeProduct("PRIVATE-P-1");
        List<Product> variants = List.of(
                completeProduct("PRIVATE-P-2"),
                changedProduct(product -> product.setName("Updated name")),
                changedProduct(product -> product.setCategory("updated-category")),
                changedProduct(product -> product.setCurrency("THB")),
                changedProduct(product -> product.setPrice(100.50)),
                changedProduct(product -> product.setWarehouseRegion("TH")),
                changedProduct(product -> product.setDeliveryDays(7)),
                changedProduct(product -> product.setPlatform("tiktok-shop")),
                changedProduct(product -> product.setSupportedRegions(List.of("SG", "TH"))),
                changedProduct(product -> product.setCrossBorderEligible(false)),
                changedProduct(product -> product.setStock(8)));
        LlmCallBudget budget = new LlmCallBudget(variants.size() + 1);

        executor.rerankAsync(request, profile, List.of(baseline), budget).join();
        variants.forEach(product -> executor.rerankAsync(request, profile, List.of(product), budget).join());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(productRecAgent, times(variants.size() + 1)).runAsync(params.capture(), any(Executor.class));
        List<String> fingerprints = params.getAllValues().stream()
                .map(value -> String.valueOf(value.get("llmFingerprint"))).toList();
        assertThat(fingerprints).doesNotHaveDuplicates();
        assertThat(fingerprints).allSatisfy(value -> assertThat(value)
                .doesNotContain("PRIVATE-P-1", "PRIVATE-P-2", "Updated name", "updated-category"));
    }

    private Product completeProduct(String productId) {
        return Product.builder()
                .productId(productId)
                .name("Baseline name")
                .category("baseline-category")
                .currency("SGD")
                .price(99.50)
                .warehouseRegion("SG")
                .deliveryDays(3)
                .platform("shopify")
                .supportedRegions(List.of("SG"))
                .crossBorderEligible(true)
                .stock(9)
                .build();
    }

    private Product changedProduct(Consumer<Product> change) {
        Product product = completeProduct("PRIVATE-P-1");
        change.accept(product);
        return product;
    }

    private static class RecordingHook implements RecommendationPipelineHook {
        private final List<String> beforeTools = new ArrayList<>();
        private final List<String> afterTools = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void beforeTool(String toolName, RecommendationPipelineState state, Map<String, Object> trustedArguments) {
            beforeTools.add(toolName);
            assertThat(trustedArguments).containsKeys("userId", "country", "currency");
        }

        @Override
        public void afterTool(String toolName, RecommendationPipelineState state, ToolObservation observation, double latencyMs) {
            afterTools.add(toolName);
            assertThat(latencyMs).isGreaterThanOrEqualTo(0.0);
        }

        @Override
        public void onToolError(String toolName, RecommendationPipelineState state, Exception error, double latencyMs) {
            errors.add(error.getMessage());
            assertThat(latencyMs).isGreaterThanOrEqualTo(0.0);
        }
    }
}
