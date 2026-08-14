package com.ecommerce.aftersales.entity;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 工单消息（客户原话 / 系统回复）的时间线记录。只存文本，不存附件二进制；
 * 附件独立落库（TicketAttachmentEntity），通过 messageId 关联。
 */
@Entity
@Table(
        name = "after_sales_ticket_messages",
        indexes = @Index(name = "idx_as_message_ticket", columnList = "ticket_id")
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketMessageEntity {
    @Id
    private String id;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    /** 产生该消息的 run（等待补充信息时指请求该信息的父 run），首次创建工单时为 null。 */
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AfterSalesTypes.MessageRole role;

    @Lob
    @Column(nullable = false)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;
}
