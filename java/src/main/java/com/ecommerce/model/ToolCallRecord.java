package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallRecord {
    @Builder.Default
    private String id = UUID.randomUUID().toString();
    private int sequence;
    private String toolName;
    @Builder.Default
    private Map<String, Object> arguments = Map.of();
    private String status;
    private String resultSummary;
    private String errorMessage;
    private double latencyMs;
    @Builder.Default
    private Instant createdAt = Instant.now();
}
