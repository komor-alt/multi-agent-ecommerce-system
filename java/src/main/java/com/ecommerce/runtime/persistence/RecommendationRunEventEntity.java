package com.ecommerce.runtime.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Persisted SSE event with a per-run monotonic sequence. */
@Entity
@Table(
        name = "recommendation_run_events",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_recommendation_run_sequence", columnNames = {"run_id", "sequence"}),
        indexes = @Index(name = "idx_recommendation_event_run", columnList = "run_id,sequence"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationRunEventEntity {
    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "text")
    private String summary;

    @Column(nullable = false, columnDefinition = "text")
    private String dataJson;

    @Column(nullable = false)
    private double elapsedMs;

    @Column(nullable = false)
    private Instant createdAt;
}
