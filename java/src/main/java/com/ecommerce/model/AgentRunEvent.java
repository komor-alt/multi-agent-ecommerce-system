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
public class AgentRunEvent {
    @Builder.Default
    private String eventId = UUID.randomUUID().toString();
    private String requestId;
    private int sequence;
    private String type;
    private String name;
    private String status;
    private String summary;
    @Builder.Default
    private Map<String, Object> data = Map.of();
    private double elapsedMs;
    @Builder.Default
    private Instant timestamp = Instant.now();
}
