package com.ecommerce.eval.recommendation;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationPlan;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.orchestrator.SupervisorOrchestrator;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.RecommendationPipelineExecutor;
import com.ecommerce.service.RecommendationPipelineState;
import com.ecommerce.service.ScenePathEnforcer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "agent.recommend.mode=RULES",
        "agent.embedding.live-enabled=false",
        "agent.embedding.max-calls=0",
        "spring.datasource.url=jdbc:h2:mem:recommendation_business_sim;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.data.redis.timeout=50ms"
})
class RecommendationBusinessSimulationIntegrationTest {

    @Autowired AutonomousAgentLoopService agentLoop;
    @Autowired RecommendationPipelineExecutor pipeline;
    @Autowired SupervisorOrchestrator supervisor;
    @Autowired ScenePathEnforcer scenePathEnforcer;

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

        Summary agent = summarize("Scene-aware Agent Loop", agentMetrics);
        Summary baseline = summarize("Fixed Workflow Baseline", baselineMetrics);
        writeReport(agentMetrics, baselineMetrics, agent, baseline);

        assertThat(agent.taskSuccessRate()).isEqualTo(1.0);
        assertThat(agent.completionRate()).isEqualTo(1.0);
        assertThat(agent.invalidToolCallRate()).isZero();
        assertThat(agent.marketConstraintViolationRate()).isZero();
        assertThat(agent.averageUnnecessaryToolCalls()).isZero();
        assertThat(baseline.averageUnnecessaryToolCalls()).isGreaterThan(0);
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
                .runId("eval-agent-" + request.getScene() + "-" + System.nanoTime())
                .request(request).build());
        double latency = (System.nanoTime() - started) / 1_000_000.0;
        List<ToolCallRecord> calls = response.getToolCalls() == null ? List.of() : response.getToolCalls();
        List<ToolCallRecord> nonFinalCalls = calls.stream()
                .filter(call -> !RecommendationPipelineExecutor.FINAL_ACTION.equals(call.getToolName()))
                .toList();
        List<String> path = nonFinalCalls.stream().map(ToolCallRecord::getToolName).toList();
        long invalid = nonFinalCalls.stream().filter(call -> !"success".equals(call.getStatus())).count();
        RecommendationPlan plan = response.getPlan();
        long marketViolations = plan == null ? 0 : marketConstraintViolations(plan.getProducts(), request);
        List<String> expectedPath = expectedPath(request);
        boolean completed = "completed".equals(response.getStatus()) && plan != null;
        boolean marketValid = completed && marketViolations == 0 && plan.getProducts() != null
                && !plan.getProducts().isEmpty();
        boolean taskSuccess = marketValid && invalid == 0 && path.equals(expectedPath);
        return new CaseMetric(request.getScene(), "agent", taskSuccess, completed,
                path.size(), calls.size(), latency, invalid, marketViolations,
                unnecessaryToolCalls(path, expectedPath), path, expectedPath,
                response.getStatus(), response.getStopReason());
    }

    private CaseMetric runFixedBaseline(RecommendationRequest request) {
        RecommendationPipelineState state = new RecommendationPipelineState(
                "eval-fixed-" + request.getScene() + "-" + System.nanoTime(), request);
        List<String> path = new ArrayList<>();
        long invalid = 0;
        long started = System.nanoTime();
        for (String tool : supervisor.fixedWorkflowToolPath()) {
            path.add(tool);
            try {
                pipeline.executeTool(tool, state);
            } catch (RuntimeException error) {
                invalid += 1;
            }
        }
        double latency = (System.nanoTime() - started) / 1_000_000.0;
        List<Product> products = state.getFinalProducts() == null ? List.of() : state.getFinalProducts();
        long marketViolations = marketConstraintViolations(products, request);
        boolean completed = state.getFinalProducts() != null;
        boolean taskSuccess = completed && !products.isEmpty() && invalid == 0 && marketViolations == 0;
        List<String> expectedPath = expectedPath(request);
        return new CaseMetric(request.getScene(), "fixed", taskSuccess, completed,
                path.size(), path.size() + 1, latency, invalid, marketViolations,
                unnecessaryToolCalls(path, expectedPath), List.copyOf(path), expectedPath,
                completed ? "completed" : "failed", null);
    }

    private RecommendationRequest request(String scene, String userId) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("campaign_id", "SEA-2026");
        context.put("campaign_objective", "conversion");
        context.put("max_delivery_days", 7);
        return RecommendationRequest.builder().userId(userId).scene(scene).numItems(3)
                .platform("shopify").region("SEA").country("SG").locale("en-SG").currency("SGD")
                .context(context).build();
    }

    private List<String> expectedPath(RecommendationRequest request) {
        return scenePathEnforcer.pathFor(request.getScene()).stream()
                .filter(tool -> !ScenePathEnforcer.FINAL_ACTION.equals(tool))
                .toList();
    }

    private long marketConstraintViolations(List<Product> products, RecommendationRequest request) {
        if (products == null) return 0;
        return products.stream().filter(product -> !marketEligible(product, request)).count();
    }

    private boolean marketEligible(Product product, RecommendationRequest request) {
        if (product == null || product.getSupportedRegions() == null) return false;
        boolean supportsMarket = product.getSupportedRegions().stream().anyMatch(value ->
                request.countryOrDefault().equalsIgnoreCase(value)
                        || request.regionOrDefault().equalsIgnoreCase(value));
        boolean deliveryAllowed = true;
        if (ScenePathEnforcer.SCENE_CAMPAIGN.equals(scenePathEnforcer.normalizeScene(request.getScene()))
                && request.getContext() != null
                && request.getContext().get("max_delivery_days") instanceof Number maxDays) {
            deliveryAllowed = product.getDeliveryDays() <= maxDays.intValue();
        }
        return product.isCrossBorderEligible()
                && product.getStock() > 0
                && request.platformOrDefault().equalsIgnoreCase(product.getPlatform())
                && request.currencyOrDefault().equalsIgnoreCase(product.getCurrency())
                && supportsMarket
                && deliveryAllowed;
    }

    private int unnecessaryToolCalls(List<String> path, List<String> expectedPath) {
        return (int) path.stream().filter(tool -> !expectedPath.contains(tool)).count();
    }

    private Summary summarize(String name, List<CaseMetric> values) {
        double total = values.size();
        int totalCalls = values.stream().mapToInt(CaseMetric::toolCalls).sum();
        return new Summary(name,
                values.stream().filter(CaseMetric::taskSuccess).count() / total,
                values.stream().filter(CaseMetric::completed).count() / total,
                values.stream().mapToInt(CaseMetric::toolCalls).average().orElse(0),
                values.stream().mapToInt(CaseMetric::steps).average().orElse(0),
                values.stream().mapToDouble(CaseMetric::latencyMs).average().orElse(0),
                totalCalls == 0 ? 0 : values.stream().mapToLong(CaseMetric::invalidCalls).sum() / (double) totalCalls,
                values.stream().filter(value -> value.marketViolations() > 0).count() / total,
                values.stream().mapToInt(CaseMetric::unnecessaryToolCalls).average().orElse(0));
    }

    private void writeReport(List<CaseMetric> agentCases, List<CaseMetric> baselineCases,
                             Summary agent, Summary baseline) throws Exception {
        Path directory = reportDirectory();
        Files.createDirectories(directory);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("label", "Offline Recommendation Business Simulation; no production performance claim");
        report.put("generatedAt", Instant.now().toString());
        report.put("execution", Map.of(
                "mode", "offline",
                "realModelCalls", false,
                "realEmbeddingCalls", false,
                "caseCount", agentCases.size(),
                "warmupExcluded", true));
        report.put("methodology", Map.of(
                "taskSuccess", "completed output with non-empty recommendations, zero invalid calls, zero market violations, and exact scene path for the agent",
                "completion", "agent response status completed; fixed workflow produced final products",
                "toolCalls", "all attempted non-final tools",
                "steps", "all recorded agent actions including final_answer; fixed workflow tools plus one terminal step",
                "invalidToolCallRate", "invalid non-final tool attempts divided by non-final tool attempts",
                "marketConstraintViolationRate", "cases containing at least one selected product violating market, stock, currency, platform, or campaign delivery constraints",
                "unnecessaryToolCalls", "executed non-final tools outside the scene's server-enforced expected path"));
        report.put("agent", agent);
        report.put("fixedWorkflow", baseline);
        report.put("agentCases", agentCases);
        report.put("fixedCases", baselineCases);
        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("recommendation-business-report.json").toFile(), report);
        Files.writeString(directory.resolve("recommendation-business-report.md"), markdown(agent, baseline,
                agentCases, baselineCases));
    }

    private Path reportDirectory() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path javaRoot = "java".equalsIgnoreCase(String.valueOf(workingDirectory.getFileName()))
                ? workingDirectory : workingDirectory.resolve("java");
        return javaRoot.resolve(Path.of("target", "recommendation-eval"));
    }

    private String markdown(Summary agent, Summary baseline,
                            List<CaseMetric> agentCases, List<CaseMetric> baselineCases) {
        StringBuilder markdown = new StringBuilder();
        markdown.append("# Recommendation Business Simulation\n\n")
                .append("Offline, deterministic Spring Boot execution. No live model or embedding API was called.\n\n")
                .append("## Aggregate metrics\n\n")
                .append("| Implementation | Task Success Rate | Completion Rate | Average Tool Calls | Average Steps | Average Latency (ms) | Invalid Tool Call Rate | Market Constraint Violation Rate | Average Unnecessary Tool Calls |\n")
                .append("|---|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        appendSummary(markdown, agent);
        appendSummary(markdown, baseline);
        markdown.append("\n## Scene-level execution\n\n")
                .append("| Implementation | Scene | Task Success | Completed | Tool Calls | Steps | Latency (ms) | Invalid Calls | Market Violations | Unnecessary Calls | Tool Path |\n")
                .append("|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        agentCases.forEach(value -> appendCase(markdown, value));
        baselineCases.forEach(value -> appendCase(markdown, value));
        markdown.append("\n## Interpretation\n\n")
                .append("The Fixed Workflow Baseline intentionally executes the union of all recommendation tools. "
                        + "The Scene-aware Agent Loop follows the server-enforced homepage, campaign, and retention paths, "
                        + "so unnecessary calls are measured from the actual execution trace rather than estimated.\n");
        return markdown.toString();
    }

    private void appendSummary(StringBuilder markdown, Summary value) {
        markdown.append('|').append(value.implementation()).append('|')
                .append(format(value.taskSuccessRate())).append('|')
                .append(format(value.completionRate())).append('|')
                .append(format(value.averageToolCalls())).append('|')
                .append(format(value.averageSteps())).append('|')
                .append(format(value.averageLatencyMs())).append('|')
                .append(format(value.invalidToolCallRate())).append('|')
                .append(format(value.marketConstraintViolationRate())).append('|')
                .append(format(value.averageUnnecessaryToolCalls())).append('|').append('\n');
    }

    private void appendCase(StringBuilder markdown, CaseMetric value) {
        markdown.append('|').append(value.implementation()).append('|').append(value.scene()).append('|')
                .append(value.taskSuccess()).append('|').append(value.completed()).append('|')
                .append(value.toolCalls()).append('|').append(value.steps()).append('|')
                .append(format(value.latencyMs())).append('|').append(value.invalidCalls()).append('|')
                .append(value.marketViolations()).append('|').append(value.unnecessaryToolCalls()).append('|')
                .append('`').append(String.join(" -> ", value.toolPath())).append('`').append('|').append('\n');
    }

    private String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    record CaseMetric(String scene, String implementation, boolean taskSuccess, boolean completed,
                      int toolCalls, int steps, double latencyMs, long invalidCalls,
                      long marketViolations, int unnecessaryToolCalls, List<String> toolPath,
                      List<String> expectedPath, String status, String stopReason) {
    }

    record Summary(String implementation, double taskSuccessRate, double completionRate,
                   double averageToolCalls, double averageSteps, double averageLatencyMs,
                   double invalidToolCallRate, double marketConstraintViolationRate,
                   double averageUnnecessaryToolCalls) {
    }
}
