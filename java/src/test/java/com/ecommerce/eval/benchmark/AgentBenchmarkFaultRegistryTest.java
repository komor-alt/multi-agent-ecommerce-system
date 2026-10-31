package com.ecommerce.eval.benchmark;

import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.service.RecommendationPipelineState;
import com.ecommerce.service.RunDeadlineExceededException;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBenchmarkFaultRegistryTest {
    private final AgentBenchmarkFaultHook hook = new AgentBenchmarkFaultHook();

    @Test
    void injectsFaultsByRunIdAcrossThreadsWithoutCrossCaseLeakage() {
        RecommendationPipelineState first = state("run-a");
        RecommendationPipelineState second = state("run-b");
        AgentBenchmarkDataset.FaultPlan failure = new AgentBenchmarkDataset.FaultPlan(
                "tool_failure", "search_products", 1);
        AgentBenchmarkDataset.FaultPlan timeout = new AgentBenchmarkDataset.FaultPlan(
                "tool_timeout", "check_inventory", 1);

        try (var ignoredA = AgentBenchmarkFaultRegistry.bind(first.getRunId(), failure);
             var ignoredB = AgentBenchmarkFaultRegistry.bind(second.getRunId(), timeout)) {
            CompletableFuture<Throwable> firstResult = CompletableFuture.supplyAsync(() ->
                    thrownBy(() -> hook.beforeTool("search_products", first, Map.of())));
            CompletableFuture<Throwable> secondResult = CompletableFuture.supplyAsync(() ->
                    thrownBy(() -> hook.beforeTool("check_inventory", second, Map.of())));

            assertThat(firstResult.join()).isInstanceOf(IllegalStateException.class)
                    .hasMessage("benchmark_tool_failure");
            assertThat(secondResult.join()).isInstanceOf(RunDeadlineExceededException.class);
            assertThatCode(() -> hook.beforeTool("check_inventory", first, Map.of())).doesNotThrowAnyException();
            assertThatCode(() -> hook.beforeTool("search_products", second, Map.of())).doesNotThrowAnyException();
            assertThat(AgentBenchmarkFaultRegistry.activeBindings()).isEqualTo(2);
        }

        assertThat(AgentBenchmarkFaultRegistry.activeBindings()).isZero();
        assertThatCode(() -> hook.beforeTool("search_products", first, Map.of())).doesNotThrowAnyException();
    }

    @Test
    void rejectsDuplicateRunBindingAndCleansAfterExceptionalScope() {
        AgentBenchmarkDataset.FaultPlan plan = new AgentBenchmarkDataset.FaultPlan(
                "tool_failure", "search_products", 1);
        assertThatThrownBy(() -> {
            try (var ignored = AgentBenchmarkFaultRegistry.bind("same-run", plan)) {
                AgentBenchmarkFaultRegistry.bind("same-run", plan);
            }
        }).hasMessageContaining("BENCHMARK_FAULT_SCOPE_ALREADY_BOUND");
        assertThat(AgentBenchmarkFaultRegistry.activeBindings()).isZero();
    }

    private static RecommendationPipelineState state(String runId) {
        return new RecommendationPipelineState(runId, RecommendationRequest.builder()
                .userId("u-1").scene("homepage").context(Map.of("intentText", "homepage recommendation"))
                .build());
    }

    private static Throwable thrownBy(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable error) {
            return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        }
    }
}
