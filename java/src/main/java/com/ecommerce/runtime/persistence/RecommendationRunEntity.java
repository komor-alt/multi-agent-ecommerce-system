package com.ecommerce.runtime.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Durable root record for one recommendation Agent run. */
@Entity
@Table(name = "recommendation_agent_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationRunEntity {
    @Id
    private String id;

    @Column(nullable = false)
    private String scene;

    @Column(nullable = false)
    private String status;

    @Column(columnDefinition = "text")
    private String stopReason;

    @Column(nullable = false, columnDefinition = "text")
    private String requestJson;

    @Column(nullable = false)
    private boolean recoverable;

    /** Full normalized ToolLoopRequest, including the server-approved replay tool whitelist. */
    @Column(columnDefinition = "text")
    private String executionRequestJson;

    @Column(nullable = false)
    private int executionAttempt;

    private String executionToken;
    private String executionOwner;
    private Instant executionLeaseUntil;

    /** Monotonic orchestration revision, separate from JPA optimistic locking. */
    @Column(nullable = false)
    private long stateVersion;

    @Version
    private long optimisticLockVersion;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @PrePersist
    void beforeCreate() {
        Instant now = Instant.now();
        createdAt = createdAt == null ? now : createdAt;
        updatedAt = now;
    }

    @PreUpdate
    void beforeUpdate() {
        updatedAt = Instant.now();
    }
}
