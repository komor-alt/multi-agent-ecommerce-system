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
public class ToolObservation {
    private String toolName;
    private String summary;
    @Builder.Default
    private Map<String, Object> data = Map.of();
    @Builder.Default
    private List<String> evidenceIds = List.of();
}
