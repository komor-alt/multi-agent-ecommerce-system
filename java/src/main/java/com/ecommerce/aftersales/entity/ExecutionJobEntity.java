package com.ecommerce.aftersales.entity;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(
        name = "after_sales_execution_jobs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_execution_proposal", columnNames = "proposal_id"),
                @UniqueConstraint(name = "uk_execution_idempotency", columnNames = "idempotency_key")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExecutionJobEntity {
    @Id
    private String id;

    @Column(name = "proposal_id", nullable = false)
    private String proposalId;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    @Column(name = "idempotency_key", nullable = false, length = 64)
    private String idempotencyKey;

    @Column(nullable = false)
    private String actionType;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AfterSalesTypes.ExecutionStatus status;

    private int attemptCount;
    private Instant nextRetryAt;

    @Lob
    private String lastError;

    @Lob
    private String resultPayload;

    private Instant createdAt;
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
