package com.ecommerce.service;

import com.ecommerce.model.ToolObservation;

@FunctionalInterface
public interface RecommendationPipelineTool {
    ToolObservation execute(String toolName, RecommendationPipelineState state);
}
