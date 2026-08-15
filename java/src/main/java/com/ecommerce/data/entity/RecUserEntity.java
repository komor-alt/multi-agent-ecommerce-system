package com.ecommerce.data.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicInsert;
import java.time.Instant;

@Entity
@Table(name = "users")
@DynamicInsert
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class RecUserEntity {
    @Id @Column(name = "id", length = 128) private String userId;
    @Column(nullable = false, unique = true) private String email;
    @Column(nullable = false) private String name;
    @Column(nullable = false, length = 32) private String region;
    @Column(nullable = false, length = 16) private String country;
    @Column(nullable = false, length = 16) private String currency;
    @Column(nullable = false, length = 32) private String locale;
    @Column(nullable = false, length = 32) private String platform;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @PrePersist void createTimestamps() { if (createdAt == null) createdAt = Instant.now(); if (updatedAt == null) updatedAt = createdAt; }
    @PreUpdate void updateTimestamp() { updatedAt = Instant.now(); }
}