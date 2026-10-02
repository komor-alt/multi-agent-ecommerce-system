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

/** Immutable child-Agent output that can be audited after the JVM exits. */
@Entity
@Table(
        name = "recommendation_agent_artifacts",
        indexes = @Index(name = "idx_rec_artifact_run_task", columnList = "run_id,task_id"))
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationArtifactEntity {
    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(name = "task_id", nullable = false)
    private String taskId;

    @Column(nullable = false)
    private String producerRole;

    @Column(nullable = false)
    private String artifactType;

    @Column(nullable = false)
    private long baseCandidateVersion;

    @Column(nullable = false, columnDefinition = "text")
    private String dataJson;

    @Column(nullable = false, columnDefinition = "text")
    private String evidenceIdsJson;

    @Column(nullable = false)
    private Instant createdAt;
}
