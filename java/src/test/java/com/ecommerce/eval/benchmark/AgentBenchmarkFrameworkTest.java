package com.ecommerce.eval.benchmark;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentBenchmarkFrameworkTest {
    @TempDir Path temporaryDirectory;

    @Test
    void datasetAndExternalReferencesAreHashPinnedAndNeverMixed() {
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.load();

        assertThat(dataset.cases()).hasSize(66).extracting(AgentBenchmarkDataset.Case::expectedScene)
                .contains("homepage", "campaign", "retention");
        assertThat(dataset.sha256()).hasSize(64);
        assertThat(dataset.faultPlanHash())
                .isEqualTo("13dc50f4fdba833470851ae2e11317c655dadac53232f313411fbc479256c9f9");
        assertThat(dataset.cases()).extracting(AgentBenchmarkDataset.Case::category)
                .contains("normal", "constraint", "inventory", "failure");
        assertThat(dataset.references()).filteredOn(ref -> ref.id().startsWith("tau3"))
                .allSatisfy(ref -> {
                    assertThat(ref.executionKind()).isEqualTo(AgentBenchmark.ExecutionKind.EXTERNAL_REFERENCE);
                    assertThat(ref.includedInJavaMetrics()).isFalse();
                    assertThat(ref.path()).contains("tau3-retail/published");
                });
        assertThat(dataset.references()).filteredOn(ref -> ref.id().equals("java-after-sales-offline"))
                .singleElement().satisfies(ref -> {
                    assertThat(ref.executionKind()).isEqualTo(AgentBenchmark.ExecutionKind.OFFLINE_SIMULATION);
                    assertThat(ref.includedInJavaMetrics()).isFalse();
                });
    }

    @Test
    void textDatasetHashIsStableAcrossWindowsAndUnixLineEndings() {
        assertThat(AgentBenchmarkDataset.textSha256("case-a\ncase-b\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(AgentBenchmarkDataset.textSha256("case-a\r\ncase-b\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void advertisesOnlyRealEvaluationProfilesAsRunnable() {
        List<AgentBenchmark.Variant> supported = AgentBenchmark.variants().stream()
                .filter(variant -> variant.status() == AgentBenchmark.VariantStatus.SUPPORTED).toList();

        assertThat(supported).extracting(AgentBenchmark.Variant::id)
                .containsExactly("java-single-agent-veto-on-path-on", AgentBenchmark.RUNNABLE_VARIANT,
                        "java-guarded-multi-agent-veto-off", "java-guarded-multi-agent-path-off",
                        "java-guarded-multi-agent-veto-off-path-off");
    }

    @Test
    void computesLatencyUsageAndCostOnlyFromCompleteProviderUsage() {
        AgentBenchmark.PriceConfig prices = new AgentBenchmark.PriceConfig(new BigDecimal("2"), new BigDecimal("4"));
        List<AgentBenchmark.CaseObservation> cases = List.of(
                observation("a", true, false, 1, 10, 100, 50, 150, prices),
                observation("b", false, true, 2, 20, 200, 100, 300, prices),
                observation("c", true, false, 3, 30, 300, 150, 450, prices),
                observation("d", false, false, 4, 80, 400, 200, 600, prices));

        AgentBenchmark.Metrics metrics = AgentBenchmark.summarize(cases, prices);
        assertThat(metrics.taskSuccessRate()).isEqualTo(0.5);
        assertThat(metrics.ruleViolationRate()).isEqualTo(0.25);
        assertThat(metrics.averageToolCalls()).isEqualTo(2.5);
        assertThat(metrics.p50LatencyMs()).isEqualTo(20);
        assertThat(metrics.p95LatencyMs()).isEqualTo(80);
        assertThat(metrics.totalTokens()).isEqualTo(1500);
        assertThat(metrics.costPerTaskUsd()).isNotNull();

        List<AgentBenchmark.CaseObservation> incomplete = List.of(cases.get(0),
                new AgentBenchmark.CaseObservation("no-usage", "normal", "completed", "completed",
                        true, true, false, 1, 9, true, "unavailable",
                        null, null, null, null, List.of(), List.of(), null, null));
        AgentBenchmark.Metrics noGuess = AgentBenchmark.summarize(incomplete, prices);
        assertThat(noGuess.totalTokens()).isNull();
        assertThat(noGuess.totalCostUsd()).isNull();
        assertThat(noGuess.costStatus()).isEqualTo("unavailable_usage_incomplete");
        assertThat(AgentBenchmark.summarize(cases, new AgentBenchmark.PriceConfig(null, null)).totalCostUsd()).isNull();
    }

    @Test
    void rejectsUnfairControlSettingsAcrossArms() {
        AgentBenchmark.Controls baseline = controls("model-a", 4);
        AgentBenchmark.Controls changedModel = controls("model-b", 4);
        AgentBenchmark.Controls changedBudget = controls("model-a", 5);
        AgentBenchmark.Controls changedDataset = new AgentBenchmark.Controls("test-provider", "model-a",
                "other-dataset", "toolset-hash", "fault-hash", 4, 24, 12, 7L);
        AgentBenchmark.Controls changedSeed = new AgentBenchmark.Controls("test-provider", "model-a",
                "dataset-hash", "toolset-hash", "fault-hash", 4, 24, 12, 8L);
        assertThatThrownBy(() -> AgentBenchmark.requireFairControls(List.of(baseline, changedModel)))
                .hasMessageContaining("BENCHMARK_UNFAIR_CONTROLS");
        assertThatThrownBy(() -> AgentBenchmark.requireFairControls(List.of(baseline, changedBudget)))
                .hasMessageContaining("BENCHMARK_UNFAIR_CONTROLS");
        assertThatThrownBy(() -> AgentBenchmark.requireFairControls(List.of(baseline, changedDataset)))
                .hasMessageContaining("BENCHMARK_UNFAIR_CONTROLS");
        assertThatThrownBy(() -> AgentBenchmark.requireFairControls(List.of(baseline, changedSeed)))
                .hasMessageContaining("BENCHMARK_UNFAIR_CONTROLS");
        assertThatCode(() -> AgentBenchmark.requireFairControls(List.of(baseline, baseline))).doesNotThrowAnyException();
    }

    @Test
    void writesExecutionKindAndAllRequiredVariantProvenance() throws Exception {
        AgentBenchmark.Controls controls = controls("offline-fixture", 4);
        AgentBenchmark.Variant variant = AgentBenchmark.variants().stream()
                .filter(v -> AgentBenchmark.RUNNABLE_VARIANT.equals(v.id())).findFirst().orElseThrow();
        AgentBenchmark.CaseObservation observation = new AgentBenchmark.CaseObservation("fixture", "normal",
                "completed", "failed", false, false, false, 2, 12, false, "unavailable",
                null, null, null, null, List.of(), List.of(), "tool_failed", "offline_only");
        AgentBenchmark.VariantRun offline = new AgentBenchmark.VariantRun(variant.id(), variant.label(),
                variant.status(), AgentBenchmark.ExecutionKind.OFFLINE_SIMULATION, false,
                controls.provider(), controls.model(), controls.datasetHash(), controls.toolsetHash(),
                controls.faultPlanHash(), controls.maxCallsPerCase(), controls.maxTotalCalls(),
                controls.maxSteps(), controls.seed(), variant.topology(), true, true,
                null, AgentBenchmark.summarize(List.of(observation), null), List.of(observation));
        AgentBenchmark.Report report = new AgentBenchmark.Report("test", "fixed", "test-revision", controls,
                AgentBenchmarkDataset.load().references(), List.of(offline), false, "No paired live arms.");

        new AgentBenchmarkReportWriter().write(report, temporaryDirectory);
        String json = Files.readString(temporaryDirectory.resolve("agent-benchmark-report.json"));
        String markdown = Files.readString(temporaryDirectory.resolve("agent-benchmark-report.md"));
        assertThat(json).contains("OFFLINE_SIMULATION", "providerInvoked", "datasetHash", "toolsetHash",
                "faultPlanHash", "maxCallsPerCase", "maxTotalCalls", "maxSteps", "seed",
                "topology", "pathConstraint", "vetoEnabled");
        assertThat(markdown).contains("Failure Analysis", "Per-Case Results", "EXTERNAL_REFERENCE", "false")
                .contains("No paired live arms");
    }

    private static AgentBenchmark.CaseObservation observation(String id, boolean success, boolean violation,
                                                              int calls, double latency, long prompt, long completion,
                                                              long total, AgentBenchmark.PriceConfig prices) {
        return new AgentBenchmark.CaseObservation(id, "normal", "completed", "completed",
                true, success, violation, calls, latency, true, "available", prompt, completion, total,
                prices.costUsd(prompt, completion, "available"), List.of(), List.of(), null, null);
    }

    private static AgentBenchmark.Controls controls(String model, int budget) {
        return new AgentBenchmark.Controls("test-provider", model, "dataset-hash", "toolset-hash",
                "fault-hash", budget, 24, 12, 7L);
    }

}
