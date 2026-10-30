package com.ecommerce.eval.live;

import com.ecommerce.MultiAgentApplication;
import com.ecommerce.eval.benchmark.AgentBenchmark;
import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import com.ecommerce.eval.benchmark.AgentBenchmarkReportWriter;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.RecommendationPlan;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.persistence.RecommendationRunRepository;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.RecommendationPipelineExecutor;
import com.ecommerce.service.ScenePathEnforcer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Paid benchmark runner. Default Maven tests exclude this class; use the dedicated opt-in profile. */
@EnabledIfEnvironmentVariable(named = LiveAgentBenchmarkGuard.RUN_ENV, matches = "(?i)true")
class LiveAgentBenchmarkRunnerTest {

    @Test
    void benchmarksOnlyTheSupportedGuardedJavaMainline() throws Exception {
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.load();
        LiveAgentBenchmarkGuard.Settings settings = LiveAgentBenchmarkGuard.requireConfiguration(
                System.getenv(), dataset.cases().size());
        LiveRecommendationE2EGuard.verifyPostgres(settings.live());
        AgentBenchmark.Variant variant = AgentBenchmark.variants().stream()
                .filter(item -> AgentBenchmark.RUNNABLE_VARIANT.equals(item.id())).findFirst().orElseThrow();
        AgentBenchmark.requireRunnable(variant);

        String toolsetHash = AgentBenchmarkDataset.sha256(String.join("\n",
                ScenePathEnforcer.DEFAULT_WHITELIST.stream().sorted().toList()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AgentBenchmark.Controls controls = new AgentBenchmark.Controls(settings.provider(), settings.model(),
                dataset.sha256(), toolsetHash, settings.maxCallsPerCase(), settings.maxTotalCalls(), settings.seed());
        AgentBenchmark.requireFairControls(List.of(controls));

        List<AgentBenchmark.CaseObservation> observations = new ArrayList<>();
        List<AgentBenchmarkDataset.Case> orderedCases = new ArrayList<>(dataset.cases());
        Collections.shuffle(orderedCases, new java.util.Random(settings.seed()));
        String runPrefix = "agent-benchmark-" + UUID.randomUUID().toString().replace("-", "");

        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(MultiAgentApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of(
                        "agent.recommend.mode", "LLM",
                        "agent.recommend.live-enabled", "true",
                        "agent.recommend.max-llm-calls", settings.maxCallsPerCase(),
                        "agent.supervisor.max-llm-calls", settings.maxCallsPerCase(),
                        "agent.embedding.live-enabled", "false",
                        "agent.embedding.max-calls", "0",
                        "agent.recommend.llm-timeout-ms", "15000",
                        "spring.datasource.driver-class-name", "org.postgresql.Driver"))
                .run()) {
            AutonomousAgentLoopService agent = app.getBean(AutonomousAgentLoopService.class);
            RecommendationRunRepository runs = app.getBean(RecommendationRunRepository.class);
            RecommendationRunEventService events = app.getBean(RecommendationRunEventService.class);
            for (AgentBenchmarkDataset.Case testCase : orderedCases) {
                String runId = runPrefix + "-" + testCase.id();
                RecommendationRequest request = RecommendationRequest.builder().userId(testCase.userId())
                        .scene("auto").numItems(testCase.numItems()).country(testCase.country())
                        .region(testCase.region()).locale(testCase.locale()).currency(testCase.currency())
                        .platform(testCase.platform()).context(Map.of("intentText", testCase.intentText())).build();
                long start = System.nanoTime();
                AgentLoopResponse response = agent.run(ToolLoopRequest.builder().runId(runId).request(request)
                        .config(ToolLoopConfig.builder().maxSteps(12)
                                .toolWhitelist(ScenePathEnforcer.DEFAULT_WHITELIST).build()).build());
                double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
                Map<String, Object> llm = response.getLlmMetrics() == null ? Map.of() : response.getLlmMetrics();
                List<Map<String, Object>> runEvents = events.history(runId);
                TraceAudit trace = audit(runEvents, settings.model());
                boolean providerInvoked = trace.providerInvoked();
                boolean noFallback = number(llm.get("fallbackCount")) == 0
                        && number(llm.get("llmCallCount")) > 0
                        && number(llm.get("llmCallCount")) == number(llm.get("providerCallCount"))
                        && trace.everyCallSucceededOnConfiguredModel();
                boolean violation = hasRuleViolation(response, llm, testCase);
                boolean persisted = runs.findById(runId).isPresent() && hasPersistedExecutionTrace(runEvents);
                int toolCalls = response.getToolCalls() == null ? 0 : (int) response.getToolCalls().stream()
                        .filter(call -> !RecommendationPipelineExecutor.FINAL_ACTION.equals(call.getToolName())).count();
                RecommendationPlan plan = response.getPlan();
                boolean completed = "completed".equals(response.getStatus()) && persisted;
                boolean decisionFromLlm = plan != null && plan.getStrategy() != null
                        && plan.getStrategy().get("businessDecision") instanceof Map<?, ?> decision
                        && "llm".equals(decision.get("source"));
                boolean taskSuccess = completed && decisionFromLlm && providerInvoked && noFallback && !violation
                        && toolCalls > 0
                        && plan.getScene() != null && plan.getScene().equals(testCase.expectedScene())
                        && plan.getMarket() != null && testCase.country().equalsIgnoreCase(plan.getMarket().getCountry())
                        && plan.getProducts() != null && !plan.getProducts().isEmpty();
                String failure = failureReason(response, providerInvoked, noFallback, persisted,
                        plan, testCase, violation, decisionFromLlm);
                if (failure == null && toolCalls == 0) failure = "no_tool_call_observed";
                Long promptTokens = longValue(llm.get("promptTokens"));
                Long completionTokens = longValue(llm.get("completionTokens"));
                Long totalTokens = longValue(llm.get("totalTokens"));
                String usageStatus = String.valueOf(llm.getOrDefault("usageStatus", "unavailable"));
                Double cost = settings.prices().costUsd(promptTokens, completionTokens, usageStatus);
                observations.add(new AgentBenchmark.CaseObservation(testCase.id(), completed, taskSuccess,
                        violation, toolCalls, latencyMs, providerInvoked, usageStatus,
                        promptTokens, completionTokens, totalTokens, cost, failure));
            }
        }

        AgentBenchmark.Metrics metrics = AgentBenchmark.summarize(observations, settings.prices());
        AgentBenchmark.VariantRun liveResult = new AgentBenchmark.VariantRun(variant.id(), variant.label(),
                variant.status(), AgentBenchmark.ExecutionKind.LIVE_LLM,
                observations.stream().anyMatch(AgentBenchmark.CaseObservation::providerInvoked),
                settings.provider(), settings.model(), dataset.sha256(), toolsetHash,
                settings.maxCallsPerCase(), settings.maxTotalCalls(), settings.seed(),
                variant.pathConstraint(), variant.vetoEnabled(), null, metrics, List.copyOf(observations));
        List<AgentBenchmark.VariantRun> allVariants = new ArrayList<>(List.of(liveResult));
        allVariants.addAll(AgentBenchmark.unexecutedVariants(controls));
        AgentBenchmark.Report report = new AgentBenchmark.Report("java-recommendation-agent-ablation-v1",
                java.time.Instant.now().toString(), revision(), controls, dataset.references(), List.copyOf(allVariants),
                false, "Only the current guarded Java Multi-Agent executor is runnable. Unsupported ablations are fail-closed; do not infer causal effects from this single arm.");
        new AgentBenchmarkReportWriter().write(report,
                Path.of(System.getProperty("user.dir")).resolve(AgentBenchmarkReportWriter.REPORT_DIR));
        assertThat(observations).hasSize(dataset.cases().size());
        assertThat(liveResult.executionKind()).isEqualTo(AgentBenchmark.ExecutionKind.LIVE_LLM);
        assertThat(liveResult.providerInvoked()).isTrue();
        assertThat(metrics.caseCount()).isEqualTo(dataset.cases().size());
        assertThat(observations).allSatisfy(item -> assertThat(item.providerInvoked())
                .as("provider invocation must be proven for case %s", item.caseId()).isTrue());
    }

    private static TraceAudit audit(List<Map<String, Object>> events, String expectedModel) {
        List<? extends Map<?, ?>> calls = events.stream().filter(event -> "llm.call".equals(event.get("name")))
                .map(event -> event.get("data")).filter(Map.class::isInstance).map(value -> (Map<?, ?>) value).toList();
        boolean invoked = calls.stream().anyMatch(data -> Boolean.TRUE.equals(data.get("providerInvoked")));
        boolean everyCallSucceeded = !calls.isEmpty() && calls.stream().allMatch(data ->
                Boolean.TRUE.equals(data.get("providerInvoked")) && "success".equals(data.get("status"))
                        && expectedModel.equals(data.get("model")));
        return new TraceAudit(invoked, everyCallSucceeded);
    }

    private static boolean hasPersistedExecutionTrace(List<Map<String, Object>> events) {
        List<String> names = events.stream().map(event -> String.valueOf(event.get("name"))).toList();
        return names.contains("run.started") && names.contains("tool.started")
                && names.contains("tool.completed") && names.contains("run.completed");
    }

    private static boolean hasRuleViolation(AgentLoopResponse response, Map<String, Object> metrics,
                                            AgentBenchmarkDataset.Case testCase) {
        if (number(metrics.get("invalidAgentSelections")) > 0 || number(metrics.get("deniedBlackboardWrites")) > 0) return true;
        if (response.getToolCalls() != null && response.getToolCalls().stream().anyMatch(LiveAgentBenchmarkRunnerTest::isGuardRejection)) return true;
        RecommendationPlan plan = response.getPlan();
        return plan == null || plan.getMarket() == null
                || !testCase.country().equalsIgnoreCase(plan.getMarket().getCountry());
    }

    private static boolean isGuardRejection(ToolCallRecord call) {
        return "blocked".equals(call.getStatus()) || "stale_rejected".equals(call.getStatus())
                || (call.getErrorMessage() != null && (call.getErrorMessage().contains("not_whitelisted")
                || call.getErrorMessage().contains("scope_violation") || call.getErrorMessage().contains("duplicate_tool_call")));
    }

    private static String failureReason(AgentLoopResponse response, boolean providerInvoked, boolean noFallback,
                                        boolean persisted, RecommendationPlan plan,
                                        AgentBenchmarkDataset.Case testCase, boolean violation, boolean decisionFromLlm) {
        if (!providerInvoked) return "provider_not_invoked";
        if (!noFallback) return "llm_fallback_detected";
        if (!persisted) return "run_not_persisted";
        if (!"completed".equals(response.getStatus())) return "run_" + safe(response.getStatus());
        if (!decisionFromLlm) return "final_decision_not_from_llm";
        if (plan == null || !testCase.expectedScene().equals(plan.getScene())) return "expected_scene_mismatch";
        if (plan.getMarket() == null || !testCase.country().equalsIgnoreCase(plan.getMarket().getCountry())) return "market_mismatch";
        if (violation) return "guard_or_market_violation";
        if (plan.getProducts() == null || plan.getProducts().isEmpty()) return "empty_recommendation";
        return null;
    }

    private static long number(Object value) { return value instanceof Number n ? n.longValue() : 0; }
    private static Long longValue(Object value) { return value instanceof Number n ? n.longValue() : null; }
    private static String safe(String value) { return value == null ? "unknown" : value.replaceAll("[^a-zA-Z0-9_-]", "_"); }

    private record TraceAudit(boolean providerInvoked, boolean everyCallSucceededOnConfiguredModel) {}

    private static String revision() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
            String value = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !value.isBlank() ? value : "unknown";
        } catch (Exception ignored) { return "unknown"; }
    }
}
