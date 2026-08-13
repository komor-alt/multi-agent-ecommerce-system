package com.ecommerce.aftersales.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "after_sales_approval_records")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApprovalRecordEntity {
    @Id
    private String id;

    @Column(nullable = false)
    private String proposalId;

    @Column(nullable = false)
    private String decision;

    @Column(nullable = false)
    private String operatorId;

    @Lob
    private String comment;

    private Instant createdAt;

    @PrePersist
    void beforeCreate() {
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
