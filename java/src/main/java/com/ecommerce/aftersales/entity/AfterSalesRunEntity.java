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

    /** 恢复会话的父 run（客户补充信息后由 WAITING_CUSTOMER 会话创建的新 run）；首次 run 为 null。 */
    private String parentRunId;

    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private int maxSteps;

    private int stepCount;
    private String stopReason;
    private Instant startedAt;
    private Instant completedAt;

    /** 分析总耗时（毫秒），由单调时钟测得，完成或失败时写入。 */
    private Long durationMs;

    @Lob
    private String finalAnswerJson;

    /** 可恢复会话快照（ResumeState JSON）：WAITING_CUSTOMER 终态写入，供客户补充信息后的新 run 还原。 */
    @Lob
    private String resumeStateJson;
}
