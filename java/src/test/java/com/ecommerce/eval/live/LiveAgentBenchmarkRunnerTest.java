package com.ecommerce.eval.live;

import com.ecommerce.MultiAgentApplication;
import com.ecommerce.eval.benchmark.AgentBenchmark;
import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import com.ecommerce.eval.benchmark.AgentBenchmarkEvaluator;
import com.ecommerce.eval.benchmark.AgentBenchmarkFaultHook;
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
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.load();
        List<AgentBenchmark.Variant> variants = AgentBenchmark.variants().stream()
                .filter(item -> item.status() == AgentBenchmark.VariantStatus.SUPPORTED).toList();
        LiveAgentBenchmarkGuard.Settings settings = LiveAgentBenchmarkGuard.requireConfiguration(
                System.getenv(), dataset.cases().size(), variants.size());
        LiveRecommendationE2EGuard.verifyPostgres(settings.live());
        String toolsetHash = AgentBenchmarkDataset.sha256(String.join("\n",
                ScenePathEnforcer.DEFAULT_WHITELIST.stream().sorted().toList()).getBytes(StandardCharsets.UTF_8));
        AgentBenchmark.Controls controls = new AgentBenchmark.Controls(settings.provider(), settings.model(),
                dataset.sha256(), toolsetHash, dataset.faultPlanHash(), settings.maxCallsPerCase(),
                settings.maxTotalCalls(), MAX_STEPS, settings.seed());
        AgentBenchmark.requireFairControls(Collections.nCopies(variants.size(), controls));

        System.setProperty(AgentEvaluationProfile.ENABLE_PROPERTY, "true");
        Map<String, List<AgentBenchmark.CaseObservation>> observationsByVariant = new LinkedHashMap<>();
        variants.forEach(variant -> observationsByVariant.put(variant.id(), new ArrayList<>()));
        List<AgentBenchmarkDataset.Case> orderedCases = new ArrayList<>(dataset.cases());
        Collections.shuffle(orderedCases, new Random(settings.seed()));
        String runPrefix = "live-bench-" + UUID.randomUUID().toString().substring(0, 8);
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(
                MultiAgentApplication.class, FaultConfiguration.class)
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
                    try {
                        AgentEvaluationProfile.Settings profile = new AgentEvaluationProfile.Settings(
                                "single".equals(variant.topology()) ? AgentEvaluationProfile.Topology.SINGLE
                                        : AgentEvaluationProfile.Topology.MULTI,
                                variant.vetoEnabled(), variant.pathConstraint(), settings.seed());
                        response = AgentEvaluationProfile.run(profile, () -> agent.run(
                                request(runId, testCase), emitted::add));
                    } catch (RuntimeException error) {
                        failure = error;
                    }
                    double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
                    List<Map<String, Object>> persisted = eventStore.history(runId);
                    boolean durable = runs.findById(runId).isPresent()
                            && hasPersistedExecutionTrace(persisted)
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
                    variant.vetoEnabled(), null, AgentBenchmark.summarize(observations, settings.prices()),
                    List.copyOf(observations));
        }).toList();

        AgentBenchmark.Report report = new AgentBenchmark.Report("java-recommendation-agent-ablation-v2",
                java.time.Instant.now().toString(), revision(), controls, dataset.references(),
                List.copyOf(results), true,
                "All runnable arms use identical provider/model, dataset, toolset, fault plan, per-case budget, max steps and seed.");
        new AgentBenchmarkReportWriter().write(report,
                Path.of(System.getProperty("user.dir")).resolve(AgentBenchmarkReportWriter.REPORT_DIR));
        assertThat(results).hasSize(variants.size()).allSatisfy(result -> {
            assertThat(result.cases()).hasSize(dataset.cases().size());
            assertThat(result.cases().stream().map(AgentBenchmark.CaseObservation::caseId).toList())
                    .containsExactlyElementsOf(orderedCases.stream().map(AgentBenchmarkDataset.Case::id).toList());
            assertThat(result.cases()).allSatisfy(item -> assertThat(item.providerInvoked())
                    .as("provider invocation for %s/%s", result.variantId(), item.caseId()).isTrue());
        });
    }

    private ToolLoopRequest request(String runId, AgentBenchmarkDataset.Case testCase) {
        Map<String, Object> context = new LinkedHashMap<>(testCase.constraints());
        context.put("intentText", testCase.intentText());
        context.put("benchmarkCaseId", testCase.id());
        context.put("benchmarkFaultPlan", Map.of("type", testCase.faultPlan().type(),
                "tool", testCase.faultPlan().tool(), "triggerCount", testCase.faultPlan().triggerCount()));
        RecommendationRequest request = RecommendationRequest.builder().userId(testCase.userId())
                .scene("auto").numItems(testCase.numItems()).country(testCase.country())
                .region(testCase.region()).locale(testCase.locale()).currency(testCase.currency())
                .platform(testCase.platform()).context(context).build();
        return ToolLoopRequest.builder().runId(runId).request(request)
                .config(ToolLoopConfig.builder().maxSteps(MAX_STEPS)
                        .toolWhitelist(ScenePathEnforcer.DEFAULT_WHITELIST).build()).build();
    }

    private static boolean hasPersistedExecutionTrace(List<Map<String, Object>> events) {
        List<String> names = events.stream().map(event -> String.valueOf(event.get("name"))).toList();
        return names.contains("run.started") && names.contains("tool.started")
                && (names.contains("run.completed") || names.contains("run.failed"));
    }

    private static boolean configuredModelOnly(List<Map<String, Object>> events, String expectedModel) {
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
