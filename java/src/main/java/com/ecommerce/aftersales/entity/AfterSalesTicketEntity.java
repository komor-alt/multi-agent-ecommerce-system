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

    /**
     * 结构化升级/外部等待原因（EscalationReason JSON CLOB）：转人工升级（ESCALATED）或
     * 外部等待（WAITING_EXTERNAL）终态写入，工单详情原样输出；其他状态为 null。
     */
    @Lob
    private String escalationReasonJson;

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
