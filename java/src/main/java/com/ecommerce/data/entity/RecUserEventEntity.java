package com.ecommerce.data.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "user_events", indexes = @Index(name = "idx_user_events_user_time", columnList = "user_id, created_at"))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecUserEventEntity {
    @Id @Column(length = 64) private String id;
    @Column(name = "user_id", nullable = false, length = 128) private String userId;
    @Column(name = "behavior_type", nullable = false, length = 32) private String behaviorType;
    @Column(name = "product_id", length = 64) private String productId;
    @Column(name = "metadata_json", nullable = false, length = 4000) private String metadataJson;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}