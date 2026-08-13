package com.ecommerce.service;

import com.ecommerce.model.ToolObservation;

import java.util.Map;

public interface RecommendationPipelineHook {
    default void beforeTool(String toolName, RecommendationPipelineState state, Map<String, Object> trustedArguments) {
    }

    default void afterTool(String toolName, RecommendationPipelineState state, ToolObservation observation, double latencyMs) {
    }

    default void onToolError(String toolName, RecommendationPipelineState state, Exception error, double latencyMs) {
    }
}
