package com.ecommerce.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

public class RecommendationPipelineToolRegistry {
    private final Map<String, RecommendationPipelineTool> tools = new LinkedHashMap<>();

    public void register(String name, RecommendationPipelineTool tool) {
        register(name, List.of(), tool);
    }

    public void register(String name, List<String> aliases, RecommendationPipelineTool tool) {
        tools.put(name, tool);
        for (String alias : aliases) {
            tools.put(alias, tool);
        }
    }

    public Optional<RecommendationPipelineTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public Set<String> names() {
        return tools.keySet();
    }
}
