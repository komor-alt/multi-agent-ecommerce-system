package com.ecommerce.eval.live;

import com.ecommerce.MultiAgentApplication;
import com.ecommerce.eval.benchmark.AgentBenchmark;
import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import com.ecommerce.eval.benchmark.AgentBenchmarkEvaluator;
import com.ecommerce.eval.benchmark.AgentBenchmarkExecutionInput;
import com.ecommerce.eval.benchmark.AgentBenchmarkFaultHook;
import com.ecommerce.eval.benchmark.AgentBenchmarkFaultRegistry;
import com.ecommerce.eval.benchmark.AgentBenchmarkReportWriter;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.runtime.persistence.RecommendationRunRepository;
import com.ecommerce.service.AgentEvaluationProfile;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.RecommendationPipelineHook;
import com.ecommerce.service.ScenePathEnforcer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Paid benchmark runner. Default Maven tests exclude this class; use the dedicated opt-in profile. */
@EnabledIfEnvironmentVariable(named = LiveAgentBenchmarkGuard.RUN_ENV, matches = "(?i)true")
class LiveAgentBenchmarkRunnerTest {
    private static final int MAX_STEPS = 20;

    @Test
    void runsTheSameFairMatrixWithoutSamplingOrSharedBudgets() {
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.loadAll();
        List<AgentBenchmark.Variant> variants = LiveAgentBenchmarkGuard.selectedVariants(System.getenv());
        int pilotCount = (int) dataset.cases().stream().filter(AgentBenchmarkDataset.Case::pilot).count();
        int smokeCount = AgentBenchmarkDataset.selectSmoke(dataset.cases()).size();
        LiveAgentBenchmarkGuard.Settings settings = LiveAgentBenchmarkGuard.requireConfiguration(
                System.getenv(), dataset.cases().size(), pilotCount, smokeCount, variants.size());
        List<AgentBenchmarkDataset.Case> selectedCases = selectedCases(dataset, settings.scope());
        LiveRecommendationE2EGuard.verifyPostgres(settings.live());
        String toolsetHash = AgentBenchmarkDataset.sha256(String.join("\n",
                ScenePathEnforcer.DEFAULT_WHITELIST.stream().sorted().toList()).getBytes(StandardCharsets.UTF_8));
        String selectedHash = selectedDatasetHash(dataset.sha256(), selectedCases, settings.scope());
        Map<String, String> suiteHashes = settings.scope() == LiveAgentBenchmarkGuard.Scope.FULL
                ? dataset.suiteHashes()
                : Map.of("recommendation-" + settings.scope().name().toLowerCase(), selectedHash);
        AgentBenchmark.Controls controls = new AgentBenchmark.Controls(settings.provider(), settings.model(),
                selectedHash, suiteHashes, toolsetHash, selectedFaultPlanHash(selectedCases),
                settings.maxCallsPerCase(), settings.maxTotalCalls(), MAX_STEPS, settings.seed(),
                "variant-recorded;single-topology=false");
        AgentBenchmark.requireFairControls(Collections.nCopies(variants.size(), controls));

        System.setProperty(AgentEvaluationProfile.ENABLE_PROPERTY, "true");
        Map<String, List<AgentBenchmark.CaseObservation>> observationsByVariant = new LinkedHashMap<>();
        variants.forEach(variant -> observationsByVariant.put(variant.id(), new ArrayList<>()));
        List<AgentBenchmarkDataset.Case> orderedCases = new ArrayList<>(selectedCases);
        Collections.shuffle(orderedCases, new Random(settings.seed()));
        String runPrefix = "live-bench-" + UUID.randomUUID().toString().substring(0, 8);
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(
                MultiAgentApplication.class, FaultConfiguration.class)
                .web(WebApplicationType.NONE)
                .properties(applicationProperties(settings.maxCallsPerCase()))
                .run()) {
            AutonomousAgentLoopService agent = app.getBean(AutonomousAgentLoopService.class);
            RecommendationRunRepository runs = app.getBean(RecommendationRunRepository.class);
            RecommendationRunEventService eventStore = app.getBean(RecommendationRunEventService.class);
            for (AgentBenchmarkDataset.Case testCase : orderedCases) {
                List<AgentBenchmark.Variant> armsForCase = new ArrayList<>(variants);
                Collections.shuffle(armsForCase, new Random(settings.seed() ^ testCase.id().hashCode()));
                for (AgentBenchmark.Variant variant : armsForCase) {
                    String variantKey = Integer.toUnsignedString(variant.id().hashCode(), 36);
                    String runId = (runPrefix + "-" + variantKey + "-" + testCase.id())
                            .replaceAll("[^A-Za-z0-9_-]", "_");
                    List<AgentRunEvent> emitted = new ArrayList<>();
                    AgentLoopResponse response = null;
                    RuntimeException failure = null;
                    long start = System.nanoTime();
                    try (AgentBenchmarkFaultRegistry.Scope ignored =
                                 AgentBenchmarkFaultRegistry.bind(runId, testCase.faultPlan())) {
                        AgentEvaluationProfile.Settings profile = new AgentEvaluationProfile.Settings(
                                "single".equals(variant.topology()) ? AgentEvaluationProfile.Topology.SINGLE
                                        : AgentEvaluationProfile.Topology.MULTI,
                                variant.vetoEnabled(), variant.pathConstraint(),
                                variant.parallelScheduling(), settings.seed());
                        response = AgentEvaluationProfile.run(profile, () -> agent.run(
                                request(runId, testCase), emitted::add));
                    } catch (RuntimeException error) {
                        failure = error;
                    }
                    double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
                    List<Map<String, Object>> persisted = eventStore.history(runId);
                    boolean durable = runs.findById(runId).isPresent()
                            && hasPersistedExecutionTrace(persisted, testCase)
                            && configuredModelOnly(persisted, settings.model());
                    observationsByVariant.get(variant.id()).add(AgentBenchmarkEvaluator.evaluate(
                            testCase, response, failure,
                            emitted, latencyMs, true, durable, settings.prices()));
                }
            }
        } finally {
            System.clearProperty(AgentEvaluationProfile.ENABLE_PROPERTY);
        }

        List<AgentBenchmark.VariantRun> results = variants.stream().map(variant -> {
            List<AgentBenchmark.CaseObservation> observations = observationsByVariant.get(variant.id());
            return new AgentBenchmark.VariantRun(variant.id(), variant.label(), variant.status(),
                    AgentBenchmark.ExecutionKind.LIVE_LLM,
                    observations.stream().anyMatch(AgentBenchmark.CaseObservation::providerInvoked),
                    controls.provider(), controls.model(), controls.datasetHash(), controls.toolsetHash(),
                    controls.faultPlanHash(), controls.maxCallsPerCase(), controls.maxTotalCalls(),
                    controls.maxSteps(), controls.seed(), variant.topology(), variant.pathConstraint(),
                    variant.vetoEnabled(), variant.parallelScheduling(), null,
                    AgentBenchmark.summarize(observations, settings.prices()),
                    AgentBenchmark.groupMetrics(observations, settings.prices()), List.copyOf(observations));
        }).toList();

        AgentBenchmark.Report report = new AgentBenchmark.Report("java-recommendation-agent-ablation-v4",
                "LIVE_" + settings.scope() + "_" + settings.armSet(),
                java.time.Instant.now().toString(), revision(), controls,
                scopedReferences(dataset.references(), settings.scope(), selectedCases, selectedHash),
                List.copyOf(results), true,
                "All runnable arms use identical provider/model, dataset, toolset, fault plan, per-case budget, max steps and seed.");
        new AgentBenchmarkReportWriter().write(report,
                AgentBenchmarkReportWriter.liveReportDirectory(Path.of(System.getProperty("user.dir")),
                        settings.scope().name(), settings.armSet().name()));
        assertThat(results).hasSize(variants.size()).allSatisfy(result -> {
            assertThat(result.cases()).hasSize(selectedCases.size());
            assertThat(result.cases().stream().map(AgentBenchmark.CaseObservation::caseId).toList())
                    .containsExactlyElementsOf(orderedCases.stream().map(AgentBenchmarkDataset.Case::id).toList());
            assertThat(result.cases()).allSatisfy(item -> assertThat(item.providerInvoked())
                    .as("provider invocation for %s/%s", result.variantId(), item.caseId()).isTrue());
        });
    }

    static Map<String, Object> applicationProperties(int maxCallsPerCase) {
        return Map.of(
                "agent.recommend.mode", "LLM",
                "agent.recommend.live-enabled", "true",
                "agent.recommend.max-llm-calls", maxCallsPerCase,
                "agent.supervisor.max-llm-calls", maxCallsPerCase,
                "agent.embedding.live-enabled", "false",
                "agent.embedding.max-calls", "0",
                "spring.datasource.driver-class-name", "org.postgresql.Driver");
    }

    static List<AgentBenchmarkDataset.Case> selectedCases(
            AgentBenchmarkDataset.Loaded dataset, LiveAgentBenchmarkGuard.Scope scope) {
        return switch (scope) {
            case SMOKE -> AgentBenchmarkDataset.selectSmoke(dataset.cases());
            case PILOT -> dataset.cases().stream().filter(AgentBenchmarkDataset.Case::pilot).toList();
            case FULL -> dataset.cases();
        };
    }

    private ToolLoopRequest request(String runId, AgentBenchmarkDataset.Case testCase) {
        RecommendationRequest request = RecommendationRequest.builder().userId(testCase.userId())
                .scene("auto").numItems(testCase.numItems()).country(testCase.country())
                .region(testCase.region()).locale(testCase.locale()).currency(testCase.currency())
                .platform(testCase.platform()).context(AgentBenchmarkExecutionInput.context(testCase)).build();
        return ToolLoopRequest.builder().runId(runId).request(request)
                .config(ToolLoopConfig.builder().maxSteps(MAX_STEPS)
                        .toolWhitelist(ScenePathEnforcer.DEFAULT_WHITELIST).build()).build();
    }

    static boolean hasPersistedExecutionTrace(List<Map<String, Object>> events,
                                              AgentBenchmarkDataset.Case testCase) {
        List<String> names = events.stream().map(event -> String.valueOf(event.get("name"))).toList();
        List<? extends Map<?, ?>> llmCalls = events.stream()
                .filter(event -> "llm.call".equals(event.get("name")))
                .map(event -> event.get("data")).filter(Map.class::isInstance)
                .map(value -> (Map<?, ?>) value)
                .toList();
        boolean completeLlmTrace = !llmCalls.isEmpty() && llmCalls.stream().allMatch(data ->
                data.containsKey("phase") && data.containsKey("model")
                        && data.containsKey("providerInvoked") && data.containsKey("latencyMs")
                        && data.containsKey("status") && data.containsKey("usageStatus")
                        && data.containsKey("promptTokens") && data.containsKey("completionTokens")
                        && data.containsKey("totalTokens") && data.containsKey("fallbackReason"));
        boolean modelPlanned = llmCalls.stream().anyMatch(data ->
                "supervisor".equals(data.get("phase")) || "single_agent".equals(data.get("phase")));
        boolean finalDecision = llmCalls.stream()
                .anyMatch(data -> "final_decision".equals(data.get("phase")));
        boolean terminal = names.contains("run.completed") || names.contains("run.failed");
        boolean finalTraceSatisfied = !testCase.expectedOutcome().startsWith("completed") || finalDecision;
        boolean failureReasonPresent = testCase.expectedOutcome().startsWith("completed")
                || events.stream().filter(event -> "run.failed".equals(event.get("name")))
                .map(event -> event.get("data")).filter(Map.class::isInstance)
                .map(value -> (Map<?, ?>) value).anyMatch(data -> nonBlank(data.get("stopReason")));
        return names.contains("run.started") && names.contains("tool.started")
                && completeLlmTrace && modelPlanned && terminal && finalTraceSatisfied && failureReasonPresent;
    }

    private static boolean nonBlank(Object value) {
        return value != null && !String.valueOf(value).isBlank();
    }

    static String selectedDatasetHash(String fullHash, List<AgentBenchmarkDataset.Case> cases,
                                      LiveAgentBenchmarkGuard.Scope scope) {
        if (scope == LiveAgentBenchmarkGuard.Scope.FULL) return fullHash;
        String manifest = cases.stream().map(AgentBenchmarkDataset.Case::id)
                .collect(java.util.stream.Collectors.joining("\n", scope.name() + "\n" + fullHash + "\n", "\n"));
        return AgentBenchmarkDataset.sha256(manifest.getBytes(StandardCharsets.UTF_8));
    }

    private static String selectedFaultPlanHash(List<AgentBenchmarkDataset.Case> cases) {
        String manifest = cases.stream().map(item -> item.id() + ":" + item.faultPlan().type() + ":"
                        + item.faultPlan().tool() + ":" + item.faultPlan().triggerCount())
                .collect(java.util.stream.Collectors.joining("\n"));
        return AgentBenchmarkDataset.sha256(manifest.getBytes(StandardCharsets.UTF_8));
    }

    static List<AgentBenchmark.DataReference> scopedReferences(
            List<AgentBenchmark.DataReference> references, LiveAgentBenchmarkGuard.Scope scope,
            List<AgentBenchmarkDataset.Case> selectedCases, String selectedHash) {
        if (scope == LiveAgentBenchmarkGuard.Scope.FULL) return references;
        List<AgentBenchmark.DataReference> scoped = new ArrayList<>(references.stream()
                .map(reference -> new AgentBenchmark.DataReference(reference.id(),
                reference.executionKind(), reference.path(), reference.sha256(),
                false, reference.note())).toList());
        String caseIds = selectedCases.stream().map(AgentBenchmarkDataset.Case::id)
                .collect(java.util.stream.Collectors.joining(","));
        boolean smoke = scope == LiveAgentBenchmarkGuard.Scope.SMOKE;
        String selectionName = scope.name().toLowerCase();
        scoped.add(new AgentBenchmark.DataReference(
                "java-recommendation-" + selectionName + "-selection",
                AgentBenchmark.ExecutionKind.LIVE_LLM,
                "java/src/test/resources/" + (smoke ? AgentBenchmarkDataset.RESOURCE
                        : AgentBenchmarkDataset.COMPLEX_RESOURCE) + "#" + selectionName + "-selection",
                selectedHash, true,
                "Selected " + selectedCases.size() + " of " + (smoke ? 66 : 36)
                        + (smoke ? " regression" : " complex-business") + " cases; manifest includes "
                        + "the full-dataset hash and these case IDs: " + caseIds));
        return List.copyOf(scoped);
    }

    static boolean configuredModelOnly(List<Map<String, Object>> events, String expectedModel) {
        List<? extends Map<?, ?>> calls = events.stream().filter(event -> "llm.call".equals(event.get("name")))
                .map(event -> event.get("data")).filter(Map.class::isInstance)
                .map(value -> (Map<?, ?>) value).filter(data -> Boolean.TRUE.equals(data.get("providerInvoked"))).toList();
        return !calls.isEmpty() && calls.stream().allMatch(data -> expectedModel.equals(data.get("model")));
    }

    private static String revision() {
        try {
            Process process = new ProcessBuilder("git", "rev-parse", "--short", "HEAD").start();
            String value = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return process.waitFor() == 0 && !value.isBlank() ? value : "unknown";
        } catch (Exception ignored) { return "unknown"; }
    }

    @Configuration(proxyBeanMethods = false)
    static class FaultConfiguration {
        @Bean
        RecommendationPipelineHook benchmarkFaultHook() {
            return new AgentBenchmarkFaultHook();
        }
    }
}
