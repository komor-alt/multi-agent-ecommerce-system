package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentLoopResponse {
    private String runId;
    private String status;
    private String stopReason;
    private RecommendationResponse response;
    private RecommendationPlan plan;
    private List<String> thoughts;
    private List<ToolCallRecord> toolCalls;
    private List<ToolObservation> observations;
    private List<EvidenceRecord> evidences;
    private double totalLatencyMs;
    /** Per-run LLM call and provider-usage observability; token/cost values are null when unavailable. */
    private Map<String, Object> llmMetrics;
}
