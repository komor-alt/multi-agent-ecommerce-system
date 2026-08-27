package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentMessage {
    private AgentMessageType type;
    private AgentId from;
    private AgentId to;
    private String summary;
    @Builder.Default
    private Map<String, Object> payload = new LinkedHashMap<>();
}
