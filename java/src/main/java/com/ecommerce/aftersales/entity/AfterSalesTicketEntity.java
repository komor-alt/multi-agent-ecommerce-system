package com.ecommerce.aftersales.entity;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "after_sales_tickets")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSalesTicketEntity {
    @Id
    private String id;

    @Column(nullable = false, unique = true)
    private String ticketNo;

    @Column(nullable = false)
    private String orderId;

    private String userId;

    @Column(nullable = false)
    private String issueType;

    @Lob
    @Column(nullable = false)
    private String customerMessage;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AfterSalesTypes.TicketStatus status;

    private String currentRunId;
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
