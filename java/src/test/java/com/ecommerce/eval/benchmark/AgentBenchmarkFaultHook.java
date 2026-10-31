package com.ecommerce.eval.benchmark;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.BlackboardField;
import com.ecommerce.model.Product;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.model.VetoRecord;
import com.ecommerce.service.RecommendationPipelineHook;
import com.ecommerce.service.RecommendationPipelineState;
import com.ecommerce.service.RunDeadlineExceededException;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Faults are selected from a test-only run-scoped registry and are never model-visible. */
public final class AgentBenchmarkFaultHook implements RecommendationPipelineHook {
    @Override
    public void beforeTool(String toolName, RecommendationPipelineState state, Map<String, Object> trustedArguments) {
        Plan plan = plan(state);
        if (!plan.matches(toolName) || occurrence(state, toolName) != plan.triggerCount()) return;
        switch (plan.type()) {
            case "tool_failure" -> throw new IllegalStateException("benchmark_tool_failure");
            case "tool_timeout" -> throw new RunDeadlineExceededException();
            case "evidence_conflict" -> throw new IllegalStateException("benchmark_evidence_conflict");
            default -> { }
        }
    }

    @Override
    public void afterTool(String toolName, RecommendationPipelineState state,
                          ToolObservation observation, double latencyMs) {
        Plan plan = plan(state);
        if (!plan.matches(toolName) || currentOccurrence(state, toolName) != plan.triggerCount()) return;
        if ("empty_result".equals(plan.type())) {
            state.setRawProducts(List.of());
        } else if ("inventory_shortage".equals(plan.type())) {
            List<Product> candidates = state.getRawProducts() == null ? List.of() : state.getRawProducts();
            if (candidates.isEmpty()) return;
            String rejected = candidates.get(0).getProductId();
            Set<String> available = new LinkedHashSet<>(state.getAvailableIds() == null
                    ? Set.of() : state.getAvailableIds());
            available.remove(rejected);
            state.setAvailableIds(available);
            state.write(AgentId.INVENTORY, BlackboardField.VETOES, VetoRecord.builder()
                    .source(AgentId.INVENTORY).productIds(List.of(rejected))
                    .reason("benchmark_inventory_shortage").handled(false).build());
            Map<String, Object> data = new LinkedHashMap<>(observation.getData() == null
                    ? Map.of() : observation.getData());
            data.put("vetoedProductIds", List.of(rejected));
            data.put("revisionRequested", true);
            data.put("faultInjected", "inventory_shortage");
            observation.setData(data);
        }
    }

    private int occurrence(RecommendationPipelineState state, String tool) {
        AgentBenchmarkFaultRegistry.State fault = AgentBenchmarkFaultRegistry.state(state.getRunId());
        return fault == null ? 0 : fault.nextOccurrence(tool);
    }

    private int currentOccurrence(RecommendationPipelineState state, String tool) {
        AgentBenchmarkFaultRegistry.State fault = AgentBenchmarkFaultRegistry.state(state.getRunId());
        return fault == null ? 0 : fault.currentOccurrence(tool);
    }

    private Plan plan(RecommendationPipelineState state) {
        AgentBenchmarkFaultRegistry.State fault = AgentBenchmarkFaultRegistry.state(state.getRunId());
        if (fault == null) return Plan.none();
        AgentBenchmarkDataset.FaultPlan plan = fault.plan();
        return new Plan(plan.type(), plan.tool(), plan.triggerCount());
    }

    private record Plan(String type, String tool, int triggerCount) {
        static Plan none() { return new Plan("none", "", 1); }
        boolean matches(String candidate) { return !"none".equals(type) && tool.equals(candidate); }
    }
}
