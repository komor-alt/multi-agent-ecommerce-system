package com.ecommerce.eval.live;

import com.ecommerce.eval.benchmark.AgentBenchmark;
import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LiveAgentBenchmarkTraceTest {
    @Test
    void liveRunnerLeavesLlmTimeoutToApplicationEnvironmentBinding() throws IOException {
        assertThat(LiveAgentBenchmarkRunnerTest.applicationProperties(12, 2048))
                .doesNotContainKey("agent.recommend.llm-timeout-ms")
                .doesNotContainKey("spring.ai.openai.api-key")
                .containsEntry("agent.recommend.max-llm-calls", 12)
                .containsEntry("agent.supervisor.max-llm-calls", 12)
                .containsEntry("spring.ai.openai.chat.options.max-tokens", 2048);

        try (var stream = getClass().getResourceAsStream("/application.yml")) {
            assertThat(stream).isNotNull();
            String applicationYaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertThat(applicationYaml)
                    .contains("llm-timeout-ms: ${ECOM_RECOMMEND_LLM_TIMEOUT_MS:15000}");
        }
    }

    @Test
    void completedOutcomeRequiresPersistedFinalDecisionFromConfiguredProviderModel() {
        AgentBenchmarkDataset.Case completed = AgentBenchmarkDataset.loadComplex().cases().stream()
                .filter(item -> "completed".equals(item.expectedOutcome())).findFirst().orElseThrow();
        List<Map<String, Object>> missingFinal = List.of(event("run.started"), event("tool.started"),
                llm("single_agent", "model-a", true), event("run.completed"));

        assertThat(LiveAgentBenchmarkRunnerTest.hasPersistedExecutionTrace(missingFinal, completed)).isFalse();

        List<Map<String, Object>> complete = List.of(event("run.started"), event("tool.started"),
                llm("single_agent", "model-a", true), llm("final_decision", "model-a", true),
                event("run.completed"));
        assertThat(LiveAgentBenchmarkRunnerTest.hasPersistedExecutionTrace(complete, completed)).isTrue();
        assertThat(LiveAgentBenchmarkRunnerTest.configuredModelOnly(complete, "model-a")).isTrue();
        assertThat(LiveAgentBenchmarkRunnerTest.configuredModelOnly(complete, "model-b")).isFalse();
    }

    @Test
    void expectedTimeoutRequiresPersistedModelToolAndTerminalTraceButNoFinalDecision() {
        AgentBenchmarkDataset.Case timeout = AgentBenchmarkDataset.loadComplex().cases().stream()
                .filter(item -> "timeout".equals(item.expectedOutcome())).findFirst().orElseThrow();
        List<Map<String, Object>> events = List.of(event("run.started"), llm("supervisor", "model-a", true),
                event("tool.started"), event("run.failed", Map.of("stopReason", "run_deadline_exceeded")));

        assertThat(LiveAgentBenchmarkRunnerTest.hasPersistedExecutionTrace(events, timeout)).isTrue();
        assertThat(LiveAgentBenchmarkRunnerTest.configuredModelOnly(events, "model-a")).isTrue();
    }

    @Test
    void rejectsTraceWithoutToolPlanningOrCompleteLlmUsageEnvelope() {
        AgentBenchmarkDataset.Case completed = AgentBenchmarkDataset.loadComplex().cases().stream()
                .filter(item -> "completed".equals(item.expectedOutcome())).findFirst().orElseThrow();
        List<Map<String, Object>> intentOnly = List.of(event("run.started"), event("tool.started"),
                llm("scene_intent", "model-a", true), llm("final_decision", "model-a", true),
                event("run.completed"));
        assertThat(LiveAgentBenchmarkRunnerTest.hasPersistedExecutionTrace(intentOnly, completed)).isFalse();

        Map<String, Object> incomplete = Map.of("name", "llm.call", "data", Map.of(
                "phase", "single_agent", "model", "model-a", "providerInvoked", true));
        List<Map<String, Object>> missingUsage = List.of(event("run.started"), event("tool.started"),
                incomplete, llm("final_decision", "model-a", true), event("run.completed"));
        assertThat(LiveAgentBenchmarkRunnerTest.hasPersistedExecutionTrace(missingUsage, completed)).isFalse();
    }

    @Test
    void pilotUsesSelectionManifestReferenceInsteadOfClaimingTheFullComplexSuite() {
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.loadAll();
        List<AgentBenchmarkDataset.Case> selected = dataset.cases().stream()
                .filter(AgentBenchmarkDataset.Case::pilot).toList();
        String selectedHash = LiveAgentBenchmarkRunnerTest.selectedDatasetHash(
                dataset.sha256(), selected, LiveAgentBenchmarkGuard.Scope.PILOT);

        List<AgentBenchmark.DataReference> references = LiveAgentBenchmarkRunnerTest.scopedReferences(
                dataset.references(), LiveAgentBenchmarkGuard.Scope.PILOT, selected, selectedHash);

        assertThat(selected).hasSize(9);
        assertThat(selectedHash).isNotEqualTo(dataset.sha256());
        assertThat(references).filteredOn(AgentBenchmark.DataReference::includedInJavaMetrics)
                .singleElement().satisfies(reference -> {
                    assertThat(reference.id())
                            .isEqualTo("java-recommendation-pilot-selection");
                    assertThat(reference.sha256()).isEqualTo(selectedHash);
                    assertThat(reference.note()).contains("Selected 9 of 36");
                    selected.forEach(item -> assertThat(reference.note()).contains(item.id()));
                });
        assertThat(references).filteredOn(reference ->
                        reference.id().equals("java-recommendation-complex-business"))
                .singleElement().satisfies(reference ->
                        assertThat(reference.includedInJavaMetrics()).isFalse());
    }

    @Test
    void smokeUsesItsOwnThreeCaseManifestAndDoesNotClaimTheRegressionSuite() {
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.loadAll();
        List<AgentBenchmarkDataset.Case> selected = LiveAgentBenchmarkRunnerTest.selectedCases(
                dataset, LiveAgentBenchmarkGuard.Scope.SMOKE);
        String selectedHash = LiveAgentBenchmarkRunnerTest.selectedDatasetHash(
                dataset.sha256(), selected, LiveAgentBenchmarkGuard.Scope.SMOKE);
        List<AgentBenchmark.DataReference> references = LiveAgentBenchmarkRunnerTest.scopedReferences(
                dataset.references(), LiveAgentBenchmarkGuard.Scope.SMOKE, selected, selectedHash);

        assertThat(selected).extracting(AgentBenchmarkDataset.Case::expectedScene)
                .containsExactly("homepage", "campaign", "retention");
        assertThat(references).filteredOn(AgentBenchmark.DataReference::includedInJavaMetrics)
                .singleElement().satisfies(reference -> {
                    assertThat(reference.id()).isEqualTo("java-recommendation-smoke-selection");
                    assertThat(reference.note()).contains("Selected 3 of 66 regression cases");
                });
    }

    private static Map<String, Object> event(String name) {
        return Map.of("name", name, "data", Map.of());
    }

    private static Map<String, Object> event(String name, Map<String, Object> data) {
        return Map.of("name", name, "data", data);
    }

    private static Map<String, Object> llm(String phase, String model, boolean providerInvoked) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("phase", phase);
        data.put("model", model);
        data.put("providerInvoked", providerInvoked);
        data.put("latencyMs", 12L);
        data.put("status", "success");
        data.put("usageStatus", "available");
        data.put("promptTokens", 10);
        data.put("completionTokens", 5);
        data.put("totalTokens", 15);
        data.put("fallbackReason", null);
        return Map.of("name", "llm.call", "data", data);
    }
}
