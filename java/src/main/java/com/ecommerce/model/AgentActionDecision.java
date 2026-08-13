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
public class AgentActionDecision {
    private String thought;
    private String action;
    @Builder.Default
    private Map<String, Object> arguments = Map.of();
    private String finalAnswer;
    @Builder.Default
    private List<String> evidenceIds = List.of();
}
