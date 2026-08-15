package com.ecommerce.data.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicInsert;
import java.time.Instant;

@Entity
@Table(name = "orders")
@DynamicInsert
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecOrderEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "order_no", nullable = false, unique = true, length = 64) private String orderId;
    @Column(name = "user_id", nullable = false, length = 128) private String userId;
    @Column(nullable = false, length = 32) private String platform;
    @Column(nullable = false, length = 16) private String country;
    @Column(nullable = false, length = 16) private String currency;
    @Column(name = "warehouse_region", nullable = false, length = 16) private String warehouseRegion;
    @Column(name = "product_ids_text", nullable = false, length = 1000) private String productIds;
    @Column(name = "total_amount", nullable = false) private double orderValue;
    @Column(name = "payment_status", nullable = false, length = 32) private String paymentStatus;
    @Column(name = "fulfillment_status", nullable = false, length = 40) private String fulfillmentStatus;
    @Column(name = "promised_delivery_days", nullable = false) private int promisedDeliveryDays;
    @Column(name = "risk_level", nullable = false, length = 16) private String riskLevel;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @PrePersist void createTimestamps() { if (id == null) id = java.util.UUID.randomUUID().toString(); if (createdAt == null) createdAt = Instant.now(); if (updatedAt == null) updatedAt = createdAt; }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
}