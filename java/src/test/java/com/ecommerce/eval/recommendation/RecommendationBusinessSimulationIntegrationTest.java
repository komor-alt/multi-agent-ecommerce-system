package com.ecommerce.eval.recommendation;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.orchestrator.SupervisorOrchestrator;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.RecommendationPipelineExecutor;
import com.ecommerce.service.RecommendationPipelineState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "agent.recommend.mode=RULES",
        "spring.datasource.url=jdbc:h2:mem:recommendation_business_sim;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.data.redis.timeout=50ms"
})
class RecommendationBusinessSimulationIntegrationTest {

    @Autowired AutonomousAgentLoopService agentLoop;
    @Autowired RecommendationPipelineExecutor pipeline;
    @Autowired SupervisorOrchestrator supervisor;

    @Test
    void comparesDynamicAgentWithFixedSupervisorWorkflow() throws Exception {
        List<RecommendationRequest> cases = List.of(
                request("homepage", "user_001"),
                request("campaign", "user_208"),
                request("retention", "user_889")
        );
        List<CaseMetric> agentMetrics = new ArrayList<>();
        List<CaseMetric> baselineMetrics = new ArrayList<>();

        // Exclude one-time Spring/Redis connection warm-up from both steady-state measurements.
        runAgent(cases.get(0));
        runFixedBaseline(cases.get(0));

        for (RecommendationRequest request : cases) {
            agentMetrics.add(runAgent(request));
            baselineMetrics.add(runFixedBaseline(request));
        }

        Summary agent = summarize("Autonomous Agent Loop", agentMetrics);
        Summary baseline = summarize("Fixed Supervisor Workflow", baselineMetrics);
        writeReport(agentMetrics, baselineMetrics, agent, baseline);

        assertThat(agent.taskSuccessRate()).isEqualTo(1.0);
        assertThat(agent.completionRate()).isEqualTo(1.0);
        assertThat(agent.invalidToolCallRate()).isZero();
        assertThat(agent.averageToolCalls()).isLessThan(baseline.averageToolCalls());
        assertThat(agent.averageSteps()).isLessThan(baseline.averageSteps());
        assertThat(agentMetrics).extracting(CaseMetric::toolPath).containsExactly(
                List.of("get_user_profile", "search_products", "check_inventory", "rerank"),
                List.of("load_campaign_constraints", "search_products", "check_fulfillment",
                        "check_inventory", "rerank", "generate_localized_copy"),
                List.of("get_user_profile", "get_recent_orders", "search_products",
                        "check_inventory", "rerank", "generate_retention_copy"));
    }

    private CaseMetric runAgent(RecommendationRequest request) {
        long started = System.nanoTime();
        AgentLoopResponse response = agentLoop.run(ToolLoopRequest.builder()
                .runId("eval-agent-" + request.getScene())
                .request(request).build());
        double latency = (System.nanoTime() - started) / 1_000_000.0;
        List<ToolCallRecord> calls = response.getToolCalls();
        List<String> path = calls.stream()
                .filter(call -> !RecommendationPipelineExecutor.FINAL_ACTION.equals(call.getToolName()))
                .map(ToolCallRecord::getToolName).toList();
        long invalid = calls.stream().filter(call -> "blocked".equals(call.getStatus())).count();
        boolean marketValid = response.getPlan() != null && productsMatchMarket(response.getPlan().getProducts(), request);
        return new CaseMetric(request.getScene(), "agent", "completed".equals(response.getStatus()) && marketValid,
                "completed".equals(response.getStatus()), path.size(), calls.size(), latency, invalid, path);
    }

    private CaseMetric runFixedBaseline(RecommendationRequest request) {
        RecommendationPipelineState state = new RecommendationPipelineState("eval-fixed-" + request.getScene(), request);
        List<String> path = new ArrayList<>();
        long invalid = 0;
        long started = System.nanoTime();
        for (String tool : supervisor.fixedWorkflowToolPath()) {
            try {
                pipeline.executeTool(tool, state);
                path.add(tool);
            } catch (RuntimeException error) {
                invalid += 1;
            }
        }
        double latency = (System.nanoTime() - started) / 1_000_000.0;
        boolean completed = state.getFinalProducts() != null;
        boolean marketValid = completed && productsMatchMarket(state.getFinalProducts(), request);
        return new CaseMetric(request.getScene(), "fixed", completed && marketValid, completed,
                path.size(), path.size() + 1, latency, invalid, path);
    }

    private RecommendationRequest request(String scene, String userId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("campaign_id", "SEA-2026");
        context.put("campaign_objective", "conversion");
        return RecommendationRequest.builder().userId(userId).scene(scene).numItems(3)
                .platform("shopify").region("SEA").country("SG").locale("en-SG").currency("SGD")
                .context(context).build();
    }

    private boolean productsMatchMarket(List<Product> products, RecommendationRequest request) {
        return products != null && !products.isEmpty() && products.stream().allMatch(product ->
                request.platformOrDefault().equalsIgnoreCase(product.getPlatform())
                        && request.currencyOrDefault().equalsIgnoreCase(product.getCurrency())
                        && product.getSupportedRegions().stream().anyMatch(request.countryOrDefault()::equalsIgnoreCase));
    }

    private Summary summarize(String name, List<CaseMetric> values) {
        double total = values.size();
        double toolCalls = values.stream().mapToInt(CaseMetric::toolCalls).average().orElse(0);
        double steps = values.stream().mapToInt(CaseMetric::steps).average().orElse(0);
        double latency = values.stream().mapToDouble(CaseMetric::latencyMs).average().orElse(0);
        long attempts = values.stream().mapToInt(CaseMetric::toolCalls).sum();
        long invalid = values.stream().mapToLong(CaseMetric::invalidCalls).sum();
        return new Summary(name,
                values.stream().filter(CaseMetric::taskSuccess).count() / total,
                toolCalls, steps, latency,
                attempts == 0 ? 0 : (double) invalid / attempts,
                values.stream().filter(CaseMetric::completed).count() / total);
    }

    private void writeReport(List<CaseMetric> agentCases, List<CaseMetric> baselineCases,
                             Summary agent, Summary baseline) throws Exception {
        Path directory = Path.of("target", "recommendation-business-simulation");
        Files.createDirectories(directory);
        Map<String, Object> report = Map.of(
                "label", "Warmed local Business Simulation - no production benefit claims",
                "agent", agent,
                "fixedWorkflow", baseline,
                "agentCases", agentCases,
                "fixedCases", baselineCases);
        new ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("report.json").toFile(), report);
    }

    record CaseMetric(String scene, String implementation, boolean taskSuccess, boolean completed,
                      int toolCalls, int steps, double latencyMs, long invalidCalls, List<String> toolPath) {}
    record Summary(String implementation, double taskSuccessRate, double averageToolCalls,
                   double averageSteps, double averageLatencyMs, double invalidToolCallRate,
                   double completionRate) {}
}