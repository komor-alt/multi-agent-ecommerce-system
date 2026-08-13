package com.ecommerce.aftersales.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(
        name = "after_sales_run_events",
        uniqueConstraints = @UniqueConstraint(name = "uk_after_sales_run_sequence", columnNames = {"run_id", "sequence"})
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSalesRunEventEntity {
    @Id
    private String id;

    @Column(name = "run_id", nullable = false)
    private String runId;

    @Column(nullable = false)
    private int sequence;

    @Column(nullable = false)
    private String type;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String status;

    @Lob
    private String summary;

    @Lob
    @Column(nullable = false)
    private String dataJson;

    @Column(nullable = false)
    private Instant createdAt;
}
