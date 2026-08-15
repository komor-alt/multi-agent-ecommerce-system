package com.ecommerce.data.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "inventory", uniqueConstraints = @UniqueConstraint(name = "uq_inventory_product_country", columnNames = {"product_id", "country"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecInventoryEntity {
    @Id @Column(length = 128) private String id;
    @Column(name = "product_id", nullable = false, length = 64) private String productId;
    @Column(nullable = false, length = 16) private String country;
    @Column(name = "warehouse_region", nullable = false, length = 16) private String warehouseRegion;
    @Column(nullable = false) private int stock;
    @Column(name = "fulfillment_status", nullable = false, length = 32) private String fulfillmentStatus;
    @Column(name = "delivery_days", nullable = false) private int deliveryDays;
    @Column(nullable = false) private boolean restricted;
    @Column(name = "restriction_reason", length = 255) private String restrictionReason;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @PrePersist @PreUpdate void timestamp() { updatedAt = Instant.now(); }
}