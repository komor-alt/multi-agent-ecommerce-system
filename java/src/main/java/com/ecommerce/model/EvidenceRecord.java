package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceRecord {
    private String evidenceId;
    private String sourceType;
    private String sourceId;
    private String title;
    private String summary;
    @Builder.Default
    private Map<String, Object> metadata = Map.of();
    @Builder.Default
    private Instant createdAt = Instant.now();
}
