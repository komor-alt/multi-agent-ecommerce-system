package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Actual execution metrics of the agent loop that produced the plan. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanMetrics {
    private int toolCalls;
    /** Number of planner decisions, including the final_answer step. */
    private int steps;
    private double latencyMs;
    /** Actual model reservations made by this run; provider token usage may be unavailable. */
    private int llmCallCount;
    private Integer promptTokens;
    private Integer completionTokens;
    private Double estimatedCost;
    private String usageStatus;
}