package com.ecommerce.runtime.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Durable lifecycle and immutable input snapshot for one dynamically spawned task. */
@Entity
@Table(
        name = "recommendation_agent_tasks",
        indexes = {
                @Index(name = "idx_rec_task_run", columnList = "run_id,created_at"),
                @Index(name = "idx_rec_task_status", columnList = "status,updated_at")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationTaskEntity {
    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(nullable = false)
    private String parentAgentId;

    @Column(nullable = false)
    private String parentRole;

    @Column(nullable = false)
    private String role;

    @Column(nullable = false)
    private String agent;

    @Column(nullable = false, columnDefinition = "text")
    private String goal;

    @Column(nullable = false, columnDefinition = "text")
    private String toolScopesJson;

    private String plannedAction;

    @Column(nullable = false, columnDefinition = "text")
    private String dependenciesJson;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false, columnDefinition = "text")
    private String contextJson;

    @Column(nullable = false, columnDefinition = "text")
    private String artifactIdsJson;

    @Column(columnDefinition = "text")
    private String stopReason;

    @Column(nullable = false)
    private long candidateVersion;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    private Instant startedAt;
    private Instant completedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
