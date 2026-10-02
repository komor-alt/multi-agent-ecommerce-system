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

/** Transactional outbox record. A Kafka transport can replace the local transport without changing writers. */
@Entity
@Table(
        name = "recommendation_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uk_recommendation_outbox_dedup", columnNames = "dedup_key"),
        indexes = {
                @Index(name = "idx_recommendation_outbox_dispatch", columnList = "status,next_attempt_at,created_at"),
                @Index(name = "idx_recommendation_outbox_lease", columnList = "status,lease_until")
        })
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationOutboxEntity {
    @Id
    private String id;

    @Column(name = "dedup_key", nullable = false)
    private String dedupKey;

    @Column(nullable = false)
    private String aggregateType;

    @Column(nullable = false)
    private String aggregateId;

    @Column(nullable = false)
    private String eventType;

    @Column(nullable = false, columnDefinition = "text")
    private String payloadJson;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int attemptCount;

    private Instant nextAttemptAt;

    @Column(columnDefinition = "text")
    private String lastError;

    /** Unique for each claim, including retries by the same JVM. Used to fence stale acknowledgements. */
    private String claimToken;

    private Instant leaseUntil;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant publishedAt;
}
