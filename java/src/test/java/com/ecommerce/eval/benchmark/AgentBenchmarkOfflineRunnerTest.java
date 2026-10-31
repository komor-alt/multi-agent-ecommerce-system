package com.ecommerce.eval.benchmark;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.service.AgentEvaluationProfile;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.RecommendationPipelineHook;
import com.ecommerce.service.ScenePathEnforcer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "agent.recommend.mode=RULES",
        "agent.recommend.live-enabled=false",
        "agent.recommend.max-llm-calls=0",
        "agent.supervisor.max-llm-calls=0",
        "agent.embedding.live-enabled=false",
        "agent.embedding.max-calls=0",
        "agent.recovery.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:agent_benchmark;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Import(AgentBenchmarkOfflineRunnerTest.FaultConfiguration.class)
class AgentBenchmarkOfflineRunnerTest {
    private static final long SEED = 20261030L;
    private static final int MAX_STEPS = 20;

    @Autowired AutonomousAgentLoopService agent;

    @BeforeAll
    static void enableEvaluationProfiles() {
        System.setProperty(AgentEvaluationProfile.ENABLE_PROPERTY, "true");
    }

    @AfterAll
    static void disableEvaluationProfiles() {
        System.clearProperty(AgentEvaluationProfile.ENABLE_PROPERTY);
    }

    @Test
    void executesAllSupportedArmsAndWritesReportsFromObservedRuns() {
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.loadAll();
        String toolsetHash = AgentBenchmarkDataset.sha256(String.join("\n",
                ScenePathEnforcer.DEFAULT_WHITELIST.stream().sorted().toList())
                .getBytes(StandardCharsets.UTF_8));
        List<AgentBenchmark.Variant> variants = new ArrayList<>(AgentBenchmark.variants().stream()
                .filter(item -> item.status() == AgentBenchmark.VariantStatus.SUPPORTED).toList());
        Collections.shuffle(variants, new Random(SEED));
        List<AgentBenchmarkDataset.Case> orderedCases = new ArrayList<>(dataset.cases());
        Collections.shuffle(orderedCases, new Random(SEED));
        AgentBenchmark.Controls controls = new AgentBenchmark.Controls(
                "offline-rules", "deterministic-rules", dataset.sha256(), dataset.suiteHashes(),
                toolsetHash, dataset.faultPlanHash(), 0, 0, MAX_STEPS, SEED,
                "single=false;multi=true");
        AgentBenchmark.requireFairControls(Collections.nCopies(variants.size(), controls));

        Map<String, List<AgentBenchmark.CaseObservation>> observationsByVariant = new LinkedHashMap<>();
        variants.forEach(variant -> observationsByVariant.put(variant.id(), new ArrayList<>()));
        for (AgentBenchmarkDataset.Case testCase : orderedCases) {
            for (AgentBenchmark.Variant variant : variants) {
                observationsByVariant.get(variant.id()).add(execute(variant, testCase));
            }
        }

        List<AgentBenchmark.VariantRun> results = variants.stream().map(variant -> {
            List<AgentBenchmark.CaseObservation> observations = observationsByVariant.get(variant.id());
            return new AgentBenchmark.VariantRun(variant.id(), variant.label(), variant.status(),
                    AgentBenchmark.ExecutionKind.OFFLINE_SIMULATION, false,
                    controls.provider(), controls.model(), controls.datasetHash(), controls.toolsetHash(),
                    controls.faultPlanHash(), controls.maxCallsPerCase(), controls.maxTotalCalls(),
                    controls.maxSteps(), controls.seed(), variant.topology(), variant.pathConstraint(),
                    variant.vetoEnabled(), variant.parallelScheduling(), null,
                    AgentBenchmark.summarize(observations, null),
                    AgentBenchmark.groupMetrics(observations, null), List.copyOf(observations));
        }).toList();

        AgentBenchmark.Report report = new AgentBenchmark.Report("java-recommendation-agent-ablation-v3",
                "OFFLINE_FULL", java.time.Instant.now().toString(), "working-tree", controls, dataset.references(),
                List.copyOf(results), true,
                "Offline RULES results compare executable topology/VETO/path-guard arms; they are not live LLM scores.");
        new AgentBenchmarkReportWriter().write(report,
                Path.of(System.getProperty("user.dir")).resolve(AgentBenchmarkReportWriter.REPORT_DIR));

        assertThat(dataset.cases()).hasSize(102);
        assertThat(dataset.cases()).filteredOn(item -> "regression".equals(item.suite())).hasSize(66);
        assertThat(dataset.cases()).filteredOn(item -> "complex-business".equals(item.suite())).hasSize(36);
        List<String> expectedCaseOrder = orderedCases.stream().map(AgentBenchmarkDataset.Case::id).toList();
        assertThat(results).hasSize(5).allSatisfy(result -> {
            assertThat(result.metrics().caseCount()).isEqualTo(dataset.cases().size());
            assertThat(result.metrics().averageProviderCalls()).isZero();
            assertThat(result.metrics().p95ProviderCalls()).isZero();
            assertThat(result.cases()).hasSize(dataset.cases().size());
            assertThat(result.cases()).allSatisfy(item -> {
                assertThat(item.llmCallCount()).isZero();
                assertThat(item.providerCallCount()).isZero();
            });
            assertThat(result.cases().stream().map(AgentBenchmark.CaseObservation::caseId).toList())
                    .containsExactlyElementsOf(expectedCaseOrder);
        });
    }

    private AgentBenchmark.CaseObservation execute(
            AgentBenchmark.Variant variant, AgentBenchmarkDataset.Case testCase) {
        RecommendationRequest request = RecommendationRequest.builder().userId(testCase.userId())
                .scene(AgentBenchmarkExecutionInput.offlineScene(testCase)).numItems(testCase.numItems())
                .country(testCase.country())
                .region(testCase.region()).locale(testCase.locale()).currency(testCase.currency())
                .platform(testCase.platform()).context(AgentBenchmarkExecutionInput.context(testCase)).build();
        String runId = ("bench-" + variant.id() + "-" + testCase.id()).replaceAll("[^A-Za-z0-9_-]", "_");
        List<AgentRunEvent> events = new ArrayList<>();
        AgentLoopResponse response = null;
        RuntimeException failure = null;
        long start = System.nanoTime();
        try (AgentBenchmarkFaultRegistry.Scope ignored =
                     AgentBenchmarkFaultRegistry.bind(runId, testCase.faultPlan())) {
            AgentEvaluationProfile.Settings profile = new AgentEvaluationProfile.Settings(
                    "single".equals(variant.topology()) ? AgentEvaluationProfile.Topology.SINGLE
                            : AgentEvaluationProfile.Topology.MULTI,
                    variant.vetoEnabled(), variant.pathConstraint(), variant.parallelScheduling(), SEED);
            response = AgentEvaluationProfile.run(profile, () -> agent.run(
                    ToolLoopRequest.builder().runId(runId).request(request)
                            .config(ToolLoopConfig.builder().maxSteps(MAX_STEPS)
                                    .toolWhitelist(ScenePathEnforcer.DEFAULT_WHITELIST).build()).build(),
                    events::add));
        } catch (RuntimeException error) {
            failure = error;
        }
        assertThat(AgentBenchmarkFaultRegistry.activeBindings()).isZero();
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        return AgentBenchmarkEvaluator.evaluate(testCase, response, failure, events, latencyMs,
                false, true, new AgentBenchmark.PriceConfig(null, null));
    }

    @TestConfiguration
    static class FaultConfiguration {
        @Bean
        RecommendationPipelineHook benchmarkFaultHook() {
            return new AgentBenchmarkFaultHook();
        }
    }
}
