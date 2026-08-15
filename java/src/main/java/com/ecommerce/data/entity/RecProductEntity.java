package com.ecommerce.data.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicInsert;
import java.time.Instant;

@Entity
@Table(name = "products")
@DynamicInsert
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecProductEntity {
    @Id @Column(name = "id", length = 64) private String id;
    @Column(name = "product_id", nullable = false, unique = true, length = 64) private String productId;
    @Column(nullable = false) private String name;
    @Column(nullable = false) private String category;
    @Column(nullable = false) private double price;
    @Column(nullable = false, length = 32) private String currency;
    @Column(length = 64) private String brand;
    @Column(name = "seller_id", length = 64) private String sellerId;
    @Column(nullable = false) private int stock;
    @Column(nullable = false) private String description;
    @Column(nullable = false, length = 64) private String platform;
    @Column(name = "warehouse_region", nullable = false, length = 16) private String warehouseRegion;
    @Column(name = "delivery_days", nullable = false) private int deliveryDays;
    @Column(name = "cross_border_eligible", nullable = false) private boolean crossBorderEligible;
    @Column(nullable = false, length = 16) private String status = "ACTIVE";
    @Column(name = "supported_countries", nullable = false, length = 255) private String supportedCountries;
    @Column(name = "recommendation_tags", nullable = false, length = 255) private String tags;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @PrePersist void createTimestamps() { if (id == null) id = java.util.UUID.randomUUID().toString(); if (createdAt == null) createdAt = Instant.now(); if (updatedAt == null) updatedAt = createdAt; }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
}