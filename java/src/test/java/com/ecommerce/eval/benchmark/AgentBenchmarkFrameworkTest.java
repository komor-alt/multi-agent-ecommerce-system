package com.ecommerce.eval.benchmark;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.FulfillmentContext;
import com.ecommerce.model.MarketContext;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationPlan;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.service.RunDeadlineExceededException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

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
        assertThat(dataset.sha256())
                .isEqualTo("3c5560a2555a8d2a512909134cbd235ca8ce6843be42176ae32a9c29ae8670d9");
        assertThat(dataset.suiteHashes()).containsOnlyKeys("regression");
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

        AgentBenchmarkDataset.Loaded complex = AgentBenchmarkDataset.loadComplex();
        assertThat(complex.cases()).hasSize(36).allSatisfy(item -> {
            assertThat(item.suite()).isEqualTo("complex-business");
            assertThat(item.expectedRequiredTools()).isEmpty();
            assertThat(item.requiredCapabilities()).isNotEmpty();
            int minimum = ((Number) item.constraints().get("minProducts")).intValue();
            if ("completed".equals(item.expectedOutcome())) {
                assertThat(minimum).isPositive().isLessThanOrEqualTo(item.numItems());
            }
            if ("completed_empty".equals(item.expectedOutcome())) {
                assertThat(minimum).isZero();
            }
        });
        List<AgentBenchmarkDataset.Case> pilot = complex.cases().stream()
                .filter(AgentBenchmarkDataset.Case::pilot).toList();
        assertThat(pilot).extracting(AgentBenchmarkDataset.Case::id).containsExactly(
                "complex-homepage-001", "complex-homepage-004", "complex-homepage-005",
                "complex-campaign-014", "complex-campaign-017", "complex-campaign-018",
                "complex-retention-025", "complex-retention-028", "complex-retention-030");
        assertThat(pilot).extracting(AgentBenchmarkDataset.Case::expectedScene)
                .contains("homepage", "campaign", "retention");
        assertThat(pilot).extracting(AgentBenchmarkDataset.Case::category)
                .contains("multi_turn", "adversarial", "inventory", "conflict", "failure");
        AgentBenchmarkDataset.Loaded combined = AgentBenchmarkDataset.loadAll();
        assertThat(combined.cases()).hasSize(102);
        assertThat(combined.cases()).allSatisfy(item ->
                assertThat(AgentBenchmarkExecutionInput.offlineScene(item)).isEqualTo(item.expectedScene()));
        assertThat(combined.suiteHashes()).containsOnlyKeys("regression", "complex-business");
        assertThat(dataset.references()).filteredOn(ref -> ref.id().equals("java-after-sales-offline"))
                .singleElement().satisfies(ref -> {
                    assertThat(ref.executionKind()).isEqualTo(AgentBenchmark.ExecutionKind.OFFLINE_SIMULATION);
                    assertThat(ref.includedInJavaMetrics()).isFalse();
                });
    }

    @Test
    void complexDatasetRejectsInvalidCompletedAndCompletedEmptyProductContracts() throws Exception {
        String jsonl;
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                AgentBenchmarkDataset.COMPLEX_RESOURCE)) {
            if (stream == null) throw new IllegalStateException("complex benchmark resource missing");
            jsonl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String completedWithZero = jsonl.replaceFirst("\"minProducts\":2", "\"minProducts\":0");
        String completedAboveRequest = jsonl.replaceFirst("\"minProducts\":2", "\"minProducts\":3");
        String emptyWithProducts = String.join("\n", jsonl.lines().map(line ->
                line.contains("\"id\":\"complex-homepage-006\"")
                        ? line.replace("\"minProducts\":0", "\"minProducts\":1") : line).toList());

        assertThatThrownBy(() -> AgentBenchmarkDataset.parse(
                completedWithZero, "complex-business", 30, 50))
                .hasMessageContaining("COMPLEX_BENCHMARK_INVALID_MIN_PRODUCTS");
        assertThatThrownBy(() -> AgentBenchmarkDataset.parse(
                completedAboveRequest, "complex-business", 30, 50))
                .hasMessageContaining("COMPLEX_BENCHMARK_INVALID_MIN_PRODUCTS");
        assertThatThrownBy(() -> AgentBenchmarkDataset.parse(
                emptyWithProducts, "complex-business", 30, 50))
                .hasMessageContaining("COMPLEX_BENCHMARK_EMPTY_MUST_REQUIRE_ZERO_PRODUCTS");
    }

    @Test
    void complexDatasetRejectsIncompletePilotSelection() throws Exception {
        String jsonl;
        try (var stream = getClass().getClassLoader().getResourceAsStream(
                AgentBenchmarkDataset.COMPLEX_RESOURCE)) {
            if (stream == null) throw new IllegalStateException("complex benchmark resource missing");
            jsonl = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertThatThrownBy(() -> AgentBenchmarkDataset.parse(
                jsonl.replaceFirst("\"pilot\":true,", ""), "complex-business", 30, 50))
                .hasMessageContaining("COMPLEX_BENCHMARK_PILOT_MUST_HAVE_NINE_CASES");
    }

    @Test
    void textDatasetHashIsStableAcrossWindowsAndUnixLineEndings() {
        assertThat(AgentBenchmarkDataset.textSha256("case-a\ncase-b\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .isEqualTo(AgentBenchmarkDataset.textSha256("case-a\r\ncase-b\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    @Test
    void executionInputDoesNotExposeExpectedPathOrEvaluatorOnlyConstraints() {
        AgentBenchmarkDataset.Case testCase = complexCase("complex-campaign-017");

        Map<String, Object> context = AgentBenchmarkExecutionInput.context(testCase);

        assertThat(AgentBenchmarkExecutionInput.offlineScene(testCase)).isEqualTo("campaign");
        assertThat(context).doesNotContainKeys("minProducts", "requireCurrencyMatch",
                "requireInventoryPositive", "requireFulfillmentCompliance", "minimumCapabilityCounts",
                "expectedOutcome", "expectedScene", "requiredCapabilities", "forbiddenCapabilities",
                "benchmarkCaseId", "benchmarkFaultPlan");
        assertThat(context).containsKeys("intentText", "conversationTurns");
    }

    @Test
    void advertisesOnlyRealEvaluationProfilesAsRunnable() {
        List<AgentBenchmark.Variant> supported = AgentBenchmark.variants().stream()
                .filter(variant -> variant.status() == AgentBenchmark.VariantStatus.SUPPORTED).toList();

        assertThat(supported).extracting(AgentBenchmark.Variant::id)
                .containsExactly("java-single-agent-veto-on-path-on", AgentBenchmark.RUNNABLE_VARIANT,
                        "java-guarded-multi-agent-parallel-off",
                        "java-guarded-multi-agent-veto-off", "java-guarded-multi-agent-path-off",
                        "java-guarded-multi-agent-veto-off-path-off");
    }

    @Test
    void parallelOffAblationChangesOnlyParallelSchedulingFromGuardedMulti() {
        AgentBenchmark.Variant guarded = AgentBenchmark.variants().stream()
                .filter(item -> AgentBenchmark.RUNNABLE_VARIANT.equals(item.id())).findFirst().orElseThrow();
        AgentBenchmark.Variant parallelOff = AgentBenchmark.variants().stream()
                .filter(item -> "java-guarded-multi-agent-parallel-off".equals(item.id()))
                .findFirst().orElseThrow();

        assertThat(parallelOff.topology()).isEqualTo(guarded.topology());
        assertThat(parallelOff.vetoEnabled()).isEqualTo(guarded.vetoEnabled()).isTrue();
        assertThat(parallelOff.pathConstraint()).isEqualTo(guarded.pathConstraint()).isTrue();
        assertThat(guarded.parallelScheduling()).isTrue();
        assertThat(parallelOff.parallelScheduling()).isFalse();
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
        assertThat(metrics.businessTaskSuccessRate()).isEqualTo(0.5);
        assertThat(metrics.safeTaskSuccessRate()).isEqualTo(0.5);
        assertThat(metrics.expectedBehaviorMatchRate()).isEqualTo(0.5);
        assertThat(metrics.ruleViolationRate()).isEqualTo(0.25);
        assertThat(metrics.averageProviderCalls()).isEqualTo(2.5);
        assertThat(metrics.p95ProviderCalls()).isEqualTo(4);
        assertThat(metrics.averageToolCalls()).isEqualTo(2.5);
        assertThat(metrics.p50LatencyMs()).isEqualTo(20);
        assertThat(metrics.p95LatencyMs()).isEqualTo(80);
        assertThat(metrics.totalTokens()).isEqualTo(1500);
        assertThat(metrics.costPerTaskUsd()).isNotNull();

        List<AgentBenchmark.CaseObservation> incomplete = List.of(cases.get(0),
                new AgentBenchmark.CaseObservation("no-usage", "regression", "normal", "basic",
                        "completed", "completed", true, true, true, true, false, true, null, 1, 9,
                        true, 1, 1, false, "unavailable", null, null, null, null,
                        List.of(), List.of(), List.of(), null, null));
        AgentBenchmark.Metrics noGuess = AgentBenchmark.summarize(incomplete, prices);
        assertThat(noGuess.totalTokens()).isNull();
        assertThat(noGuess.totalCostUsd()).isNull();
        assertThat(noGuess.costStatus()).isEqualTo("unavailable_usage_incomplete");
        assertThat(AgentBenchmark.summarize(cases, new AgentBenchmark.PriceConfig(null, null)).totalCostUsd()).isNull();
    }

    @Test
    void expectedFailureMatchesBehaviorButNeverCountsAsBusinessSuccess() {
        AgentBenchmarkDataset.Case testCase = AgentBenchmarkDataset.loadComplex().cases().stream()
                .filter(item -> "complex-homepage-007".equals(item.id())).findFirst().orElseThrow();
        List<AgentRunEvent> events = List.of(toolStarted("get_user_profile"), toolStarted("search_products"));

        AgentBenchmark.CaseObservation observation = AgentBenchmarkEvaluator.evaluate(testCase, null,
                new RunDeadlineExceededException(), events, 12.0, false, true,
                new AgentBenchmark.PriceConfig(null, null));

        assertThat(observation.expectedBehaviorMatch()).isTrue();
        assertThat(observation.businessTaskSuccess()).isFalse();
        assertThat(observation.safeTaskSuccess()).isFalse();
        assertThat(observation.completed()).isFalse();
        assertThat(observation.ruleViolation()).isFalse();
    }

    @Test
    void completedBusinessResultRemainsSuccessfulWhenForbiddenCapabilityViolatesBehaviorContract() {
        AgentBenchmarkDataset.Case testCase = complexCase("complex-homepage-004");
        AgentLoopResponse response = completedResponse(testCase, List.of(
                "get_user_profile", "search_products", "check_inventory", "rerank",
                "generate_localized_copy", "final_answer"));

        AgentBenchmark.CaseObservation observation = AgentBenchmarkEvaluator.evaluate(testCase, response,
                null, List.of(), 12.0, false, true, new AgentBenchmark.PriceConfig(null, null));

        assertThat(observation.businessTaskSuccess()).isTrue();
        assertThat(observation.safeTaskSuccess()).isFalse();
        assertThat(observation.expectedBehaviorMatch()).isFalse();
        assertThat(observation.ruleViolation()).isTrue();
        assertThat(observation.expectationMismatch()).isEqualTo("rule_violation");
    }

    @Test
    void missingLiveProvenanceDoesNotChangeBusinessSuccessOrCreateRuleViolation() {
        AgentBenchmarkDataset.Case testCase = complexCase("complex-homepage-001");
        AgentLoopResponse response = completedResponse(testCase, List.of(
                "get_user_profile", "search_products", "check_inventory", "rerank", "final_answer"));

        AgentBenchmark.CaseObservation observation = AgentBenchmarkEvaluator.evaluate(testCase, response,
                null, List.of(), 12.0, true, false, new AgentBenchmark.PriceConfig(null, null));

        assertThat(observation.businessTaskSuccess()).isTrue();
        assertThat(observation.safeTaskSuccess()).isTrue();
        assertThat(observation.expectedBehaviorMatch()).isFalse();
        assertThat(observation.ruleViolation()).isFalse();
        assertThat(observation.expectationMismatch()).isEqualTo("provider_not_invoked");
    }

    @Test
    void completedWithInvalidOutputIsNeitherBusinessNorSafeSuccess() {
        AgentBenchmarkDataset.Case testCase = complexCase("complex-homepage-001");
        AgentLoopResponse response = AgentLoopResponse.builder()
                .status("completed")
                .toolCalls(List.of(ToolCallRecord.builder().toolName("final_answer")
                        .status("completed").build()))
                .build();

        AgentBenchmark.CaseObservation observation = AgentBenchmarkEvaluator.evaluate(testCase, response,
                null, List.of(), 12.0, false, true, new AgentBenchmark.PriceConfig(null, null));

        assertThat(observation.completed()).isTrue();
        assertThat(observation.businessTaskSuccess()).isFalse();
        assertThat(observation.safeTaskSuccess()).isFalse();
        assertThat(observation.ruleViolation()).isTrue();
        assertThat(observation.validExperiment()).isTrue();
    }

    @Test
    void budgetInvalidCasesAreExcludedWhileProviderErrorsRemainValidFailures() {
        AgentBenchmarkDataset.Case testCase = complexCase("complex-homepage-001");
        AgentLoopResponse successful = completedResponse(testCase, List.of(
                "get_user_profile", "search_products", "check_inventory", "rerank", "final_answer"));
        AgentBenchmark.CaseObservation budgetInvalid = AgentBenchmarkEvaluator.evaluate(testCase, successful,
                null, List.of(llmCall(false, "rules_fallback", "llm_budget_exhausted")),
                12.0, false, true, new AgentBenchmark.PriceConfig(null, null));
        AgentBenchmark.CaseObservation providerFailure = AgentBenchmarkEvaluator.evaluate(testCase, null,
                new IllegalStateException("provider_error"),
                List.of(llmCall(true, "provider_error", "provider_error")),
                13.0, false, true, new AgentBenchmark.PriceConfig(null, null));
        AgentBenchmark.CaseObservation validSuccess = AgentBenchmarkEvaluator.evaluate(testCase, successful,
                null, List.of(llmCall(true, "success", null)),
                14.0, false, true, new AgentBenchmark.PriceConfig(null, null));

        assertThat(budgetInvalid.businessTaskSuccess()).isTrue();
        assertThat(budgetInvalid.validExperiment()).isFalse();
        assertThat(budgetInvalid.invalidReason()).isEqualTo("llm_budget_exhausted");
        assertThat(providerFailure.validExperiment()).isTrue();
        assertThat(providerFailure.businessTaskSuccess()).isFalse();

        AgentBenchmark.Metrics metrics = AgentBenchmark.summarize(
                List.of(budgetInvalid, providerFailure, validSuccess), new AgentBenchmark.PriceConfig(null, null));
        assertThat(metrics.caseCount()).isEqualTo(3);
        assertThat(metrics.validCaseCount()).isEqualTo(2);
        assertThat(metrics.invalidCaseCount()).isEqualTo(1);
        assertThat(metrics.invalidCaseRate()).isEqualTo(1.0 / 3.0);
        assertThat(metrics.businessTaskSuccessRate()).isEqualTo(0.5);
        assertThat(metrics.safeTaskSuccessRate()).isEqualTo(0.5);
        assertThat(metrics.expectedBehaviorMatchRate()).isEqualTo(0.5);
    }

    @Test
    void countsLlmAndProviderCallsFromEventFallbackWithoutInventingCalls() {
        AgentBenchmarkDataset.Case testCase = complexCase("complex-homepage-001");
        AgentLoopResponse response = completedResponse(testCase, List.of(
                "get_user_profile", "search_products", "check_inventory", "rerank", "final_answer"));
        List<AgentRunEvent> events = List.of(
                llmCall(true, "success"), llmCall(true, "success"), llmCall(false, "rules_fallback"));

        AgentBenchmark.CaseObservation observation = AgentBenchmarkEvaluator.evaluate(testCase, response,
                null, events, 12.0, false, true, new AgentBenchmark.PriceConfig(null, null));

        assertThat(observation.llmCallCount()).isEqualTo(3);
        assertThat(observation.providerCallCount()).isEqualTo(2);
        assertThat(observation.providerInvoked()).isTrue();
        assertThat(observation.fallbackDetected()).isTrue();
    }

    @Test
    void prefersResponseLlmMetricsCallCountsWhenAvailable() {
        AgentBenchmarkDataset.Case testCase = complexCase("complex-homepage-001");
        AgentLoopResponse response = completedResponse(testCase, List.of(
                "get_user_profile", "search_products", "check_inventory", "rerank", "final_answer"));
        response.setLlmMetrics(Map.of("llmCallCount", 4, "providerCallCount", 3,
                "fallbackCount", 1, "usageStatus", "unavailable"));

        AgentBenchmark.CaseObservation observation = AgentBenchmarkEvaluator.evaluate(testCase, response,
                null, List.of(llmCall(true, "success")), 12.0, false, true,
                new AgentBenchmark.PriceConfig(null, null));

        assertThat(observation.llmCallCount()).isEqualTo(4);
        assertThat(observation.providerCallCount()).isEqualTo(3);
        assertThat(observation.fallbackDetected()).isTrue();
    }

    @Test
    void distinguishesRecoveredRerankFormatRetryFromFinalRulesFallback() {
        AgentBenchmarkDataset.Case testCase = complexCase("complex-homepage-001");
        AgentLoopResponse response = completedResponse(testCase, List.of(
                "get_user_profile", "search_products", "check_inventory", "rerank", "final_answer"));
        response.setLlmMetrics(Map.of("llmCallCount", 2, "providerCallCount", 2,
                "fallbackCount", 1, "usageStatus", "available",
                "promptTokens", 20, "completionTokens", 10, "totalTokens", 30));
        response.setObservations(List.of(ToolObservation.builder().toolName("rerank")
                .data(Map.of("rerankDiagnostics", Map.of(
                        "status", "llm_retry_success", "fallbackUsed", false,
                        "firstFailureReason", "empty_rerank_selection", "retryCount", 1)))
                .build()));
        List<AgentRunEvent> events = List.of(
                llmCall(true, "fallback", "empty_rerank_selection", "product_rerank"),
                llmCall(true, "success", null, "product_rerank"));

        AgentBenchmark.CaseObservation observation = AgentBenchmarkEvaluator.evaluate(testCase, response,
                null, events, 12.0, true, true, new AgentBenchmark.PriceConfig(null, null));

        assertThat(observation.fallbackDetected()).isFalse();
        assertThat(observation.expectedBehaviorMatch()).isTrue();
        assertThat(observation.llmCallCount()).isEqualTo(2);
        assertThat(observation.providerCallCount()).isEqualTo(2);
        assertThat(response.getLlmMetrics()).containsEntry("fallbackCount", 1);
    }

    @Test
    void keepsFinalRerankRulesFallbackVisibleAfterAFailedRetry() {
        AgentBenchmarkDataset.Case testCase = complexCase("complex-homepage-001");
        AgentLoopResponse response = completedResponse(testCase, List.of(
                "get_user_profile", "search_products", "check_inventory", "rerank", "final_answer"));
        response.setLlmMetrics(Map.of("llmCallCount", 2, "providerCallCount", 2,
                "fallbackCount", 2, "usageStatus", "unavailable"));
        response.setObservations(List.of(ToolObservation.builder().toolName("rerank")
                .data(Map.of("rerankDiagnostics", Map.of(
                        "status", "safe_fallback", "fallbackUsed", true,
                        "firstFailureReason", "empty_rerank_selection",
                        "finalFailureReason", "invalid_json", "retryCount", 1)))
                .build()));
        List<AgentRunEvent> events = List.of(
                llmCall(true, "fallback", "empty_rerank_selection", "product_rerank"),
                llmCall(true, "fallback", "invalid_json", "product_rerank"));

        AgentBenchmark.CaseObservation observation = AgentBenchmarkEvaluator.evaluate(testCase, response,
                null, events, 12.0, true, true, new AgentBenchmark.PriceConfig(null, null));

        assertThat(observation.fallbackDetected()).isTrue();
        assertThat(observation.expectedBehaviorMatch()).isFalse();
        assertThat(observation.llmCallCount()).isEqualTo(2);
        assertThat(observation.providerCallCount()).isEqualTo(2);
    }

    @Test
    void rejectsUnfairControlSettingsAcrossArms() {
        AgentBenchmark.Controls baseline = controls("model-a", 4);
        AgentBenchmark.Controls changedModel = controls("model-b", 4);
        AgentBenchmark.Controls changedBudget = controls("model-a", 5);
        AgentBenchmark.Controls changedDataset = new AgentBenchmark.Controls("test-provider", "model-a",
                "other-dataset", java.util.Map.of("regression", "other"), "toolset-hash", "fault-hash",
                4, 24, 2048, 12, 7L, "single=false;multi=true");
        AgentBenchmark.Controls changedSeed = new AgentBenchmark.Controls("test-provider", "model-a",
                "dataset-hash", java.util.Map.of("regression", "suite-hash"), "toolset-hash", "fault-hash",
                4, 24, 2048, 12, 8L, "single=false;multi=true");
        AgentBenchmark.Controls changedOutputCap = new AgentBenchmark.Controls("test-provider", "model-a",
                "dataset-hash", java.util.Map.of("regression", "suite-hash"), "toolset-hash", "fault-hash",
                4, 24, 1024, 12, 7L, "single=false;multi=true");
        assertThatThrownBy(() -> AgentBenchmark.requireFairControls(List.of(baseline, changedModel)))
                .hasMessageContaining("BENCHMARK_UNFAIR_CONTROLS");
        assertThatThrownBy(() -> AgentBenchmark.requireFairControls(List.of(baseline, changedBudget)))
                .hasMessageContaining("BENCHMARK_UNFAIR_CONTROLS");
        assertThatThrownBy(() -> AgentBenchmark.requireFairControls(List.of(baseline, changedDataset)))
                .hasMessageContaining("BENCHMARK_UNFAIR_CONTROLS");
        assertThatThrownBy(() -> AgentBenchmark.requireFairControls(List.of(baseline, changedSeed)))
                .hasMessageContaining("BENCHMARK_UNFAIR_CONTROLS");
        assertThatThrownBy(() -> AgentBenchmark.requireFairControls(List.of(baseline, changedOutputCap)))
                .hasMessageContaining("BENCHMARK_UNFAIR_CONTROLS");
        assertThatCode(() -> AgentBenchmark.requireFairControls(List.of(baseline, baseline))).doesNotThrowAnyException();
    }

    @Test
    void writesExecutionKindAndAllRequiredVariantProvenance() throws Exception {
        AgentBenchmark.Controls controls = controls("offline-fixture", 4);
        AgentBenchmark.Variant variant = AgentBenchmark.variants().stream()
                .filter(v -> AgentBenchmark.RUNNABLE_VARIANT.equals(v.id())).findFirst().orElseThrow();
        AgentBenchmark.CaseObservation observation = new AgentBenchmark.CaseObservation(
                "fixture", "regression", "normal", "basic", "completed", "failed",
                false, false, false, false, false, true, null, 2, 12, false, 0, 0, false, "unavailable",
                null, null, null, null, List.of(), List.of(), List.of(), "tool_failed", "offline_only");
        AgentBenchmark.VariantRun offline = new AgentBenchmark.VariantRun(variant.id(), variant.label(),
                variant.status(), AgentBenchmark.ExecutionKind.OFFLINE_SIMULATION, false,
                controls.provider(), controls.model(), controls.datasetHash(), controls.toolsetHash(),
                controls.faultPlanHash(), controls.maxCallsPerCase(), controls.maxTotalCalls(),
                controls.maxSteps(), controls.seed(), variant.topology(), true, true,
                true, null, AgentBenchmark.summarize(List.of(observation), null),
                AgentBenchmark.groupMetrics(List.of(observation), null), List.of(observation));
        AgentBenchmark.Report report = new AgentBenchmark.Report("test", "OFFLINE_FULL", "fixed",
                "test-revision", controls,
                AgentBenchmarkDataset.load().references(), List.of(offline), false, "No paired live arms.");

        AgentBenchmark.Report liveReport = new AgentBenchmark.Report("test", "LIVE_SMOKE_CORE", "fixed",
                "test-revision", controls,
                AgentBenchmarkDataset.load().references(), List.of(offline), false, "No paired live arms.");
        Path offlineDirectory = AgentBenchmarkReportWriter.offlineReportDirectory(temporaryDirectory);
        Path liveDirectory = AgentBenchmarkReportWriter.liveReportDirectory(temporaryDirectory, "SMOKE", "CORE");
        AgentBenchmarkReportWriter writer = new AgentBenchmarkReportWriter();
        writer.write(report, offlineDirectory);
        writer.write(liveReport, liveDirectory);

        String json = Files.readString(offlineDirectory.resolve("agent-benchmark-report.json"));
        String markdown = Files.readString(offlineDirectory.resolve("agent-benchmark-report.md"));
        String liveJson = Files.readString(liveDirectory.resolve("agent-benchmark-report.json"));
        String liveMarkdown = Files.readString(liveDirectory.resolve("agent-benchmark-report.md"));
        assertThat(offlineDirectory).isEqualTo(temporaryDirectory.resolve("target/agent-benchmark"));
        assertThat(liveDirectory).isEqualTo(offlineDirectory.resolve("live-smoke-core"));
        assertThat(json).contains("\"scope\" : \"OFFLINE_FULL\"").doesNotContain("LIVE_SMOKE_CORE");
        assertThat(liveJson).contains("\"scope\" : \"LIVE_SMOKE_CORE\"").doesNotContain("OFFLINE_FULL");
        assertThat(markdown).contains("- Scope: `OFFLINE_FULL`");
        assertThat(liveMarkdown).contains("- Scope: `LIVE_SMOKE_CORE`");
        assertThat(json).contains("OFFLINE_SIMULATION", "providerInvoked", "datasetHash", "toolsetHash",
                "faultPlanHash", "maxCallsPerCase", "maxTotalCalls", "maxOutputTokensPerCall",
                "maxSteps", "seed",
                "topology", "pathConstraint", "vetoEnabled", "parallelScheduling",
                "businessTaskSuccess", "safeTaskSuccess", "safeTaskSuccessRate",
                "expectedBehaviorMatch", "validExperiment", "invalidReason",
                "invalidCaseCount", "invalidCaseRate", "suiteHashes",
                "providerCallCount", "averageProviderCalls", "p95ProviderCalls");
        assertThat(markdown).contains("Failure Analysis", "Grouped Metrics", "Per-Case Results",
                        "EXTERNAL_REFERENCE", "false", "max output tokens/call `2048`",
                        "not a total-token ceiling")
                .contains("No paired live arms");
    }

    private static AgentBenchmark.CaseObservation observation(String id, boolean success, boolean violation,
                                                              int calls, double latency, long prompt, long completion,
                                                              long total, AgentBenchmark.PriceConfig prices) {
        return new AgentBenchmark.CaseObservation(id, "regression", "normal", "basic",
                "completed", "completed", true, success, success && !violation, success, violation,
                true, null, calls, latency,
                true, calls, calls, false, "available", prompt, completion, total,
                prices.costUsd(prompt, completion, "available"), List.of(), List.of(), List.of(), null, null);
    }

    private static AgentBenchmark.Controls controls(String model, int budget) {
        return new AgentBenchmark.Controls("test-provider", model, "dataset-hash",
                java.util.Map.of("regression", "suite-hash"), "toolset-hash",
                "fault-hash", budget, 24, 2048, 12, 7L, "single=false;multi=true");
    }

    private static AgentRunEvent toolStarted(String tool) {
        return AgentRunEvent.builder().name("tool.started").data(java.util.Map.of("tool", tool)).build();
    }

    private static AgentRunEvent llmCall(boolean providerInvoked, String status) {
        return llmCall(providerInvoked, status, null);
    }

    private static AgentRunEvent llmCall(boolean providerInvoked, String status, String fallbackReason) {
        return llmCall(providerInvoked, status, fallbackReason, "supervisor");
    }

    private static AgentRunEvent llmCall(
            boolean providerInvoked, String status, String fallbackReason, String phase) {
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("phase", phase);
        data.put("providerInvoked", providerInvoked);
        data.put("status", status);
        data.put("fallbackReason", fallbackReason);
        data.put("promptTokens", 10);
        data.put("completionTokens", 5);
        data.put("totalTokens", 15);
        return AgentRunEvent.builder().name("llm.call").data(data).build();
    }

    private static AgentBenchmarkDataset.Case complexCase(String id) {
        return AgentBenchmarkDataset.loadComplex().cases().stream()
                .filter(item -> id.equals(item.id())).findFirst().orElseThrow();
    }

    private static AgentLoopResponse completedResponse(
            AgentBenchmarkDataset.Case testCase, List<String> tools) {
        int minimumProducts = ((Number) testCase.constraints().get("minProducts")).intValue();
        List<Product> products = IntStream.range(0, minimumProducts)
                .mapToObj(index -> Product.builder()
                        .productId("P-TEST-" + index)
                        .name("Test product " + index)
                        .stock(10)
                        .currency(testCase.currency())
                        .deliveryDays(3)
                        .crossBorderEligible(true)
                        .build())
                .toList();
        RecommendationPlan plan = RecommendationPlan.builder()
                .scene(testCase.expectedScene())
                .market(MarketContext.builder()
                        .platform(testCase.platform())
                        .region(testCase.region())
                        .country(testCase.country())
                        .locale(testCase.locale())
                        .currency(testCase.currency())
                        .build())
                .products(products)
                .strategy(Map.of("businessDecision", Map.of("source", "llm")))
                .fulfillment(FulfillmentContext.builder().status("market_eligible").build())
                .build();
        return AgentLoopResponse.builder()
                .status("completed")
                .plan(plan)
                .toolCalls(tools.stream().map(tool -> ToolCallRecord.builder()
                        .toolName(tool).status("completed").build()).toList())
                .build();
    }

}
