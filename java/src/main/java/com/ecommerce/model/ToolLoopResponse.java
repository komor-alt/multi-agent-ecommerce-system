package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolLoopResponse {
    private String runId;
    private String status;
    private String stopReason;
    private RecommendationResponse response;
    private List<ToolCallRecord> toolCalls;
    private double totalLatencyMs;
}
