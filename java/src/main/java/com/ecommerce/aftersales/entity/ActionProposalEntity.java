package com.ecommerce.aftersales.entity;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(name = "after_sales_action_proposals")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionProposalEntity {
    @Id
    private String id;

    @Column(nullable = false)
    private String ticketId;

    @Column(nullable = false)
    private String actionType;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    @Column(nullable = false, length = 8)
    private String currency;

    @Column(nullable = false)
    private String policyVersion;

    @Column(nullable = false)
    private String proposalVersion;

    @Lob
    @Column(nullable = false)
    private String decisionSummary;

    @Lob
    @Column(nullable = false)
    private String evidenceIdsJson;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AfterSalesTypes.ProposalStatus status;

    private String reviewedBy;

    @Lob
    private String reviewComment;

    private Instant reviewedAt;
    private Instant createdAt;

    @PrePersist
    void beforeCreate() {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
