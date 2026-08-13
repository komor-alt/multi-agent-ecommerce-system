package com.ecommerce.aftersales.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "after_sales_agent_runs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AfterSalesRunEntity {
    @Id
    private String id;

    @Column(nullable = false)
    private String ticketId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int maxSteps;

    private int stepCount;
    private String stopReason;
    private Instant startedAt;
    private Instant completedAt;

    @Lob
    private String finalAnswerJson;
}
