package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
import com.ecommerce.aftersales.entity.TicketMessageEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真库（H2）验证消息/附件持久化：消息按时间升序返回、附件按 messageId 关联、图片附件判定。
 */
@DataJpaTest
class TicketMessageRepositoryTest {

    @Autowired
    private TicketMessageRepository messageRepository;

    @Autowired
    private TicketAttachmentRepository attachmentRepository;

    @Test
    void messagesPersistOrderedByCreatedAtWithAttachmentsLinkedByMessageId() {
        TicketMessageEntity initial = messageRepository.save(TicketMessageEntity.builder()
                .id("msg-1")
                .ticketId("ticket-1")
                .role(AfterSalesTypes.MessageRole.CUSTOMER)
                .content("my parcel arrived damaged")
                .createdAt(Instant.parse("2026-08-01T01:00:00Z"))
                .build());
        messageRepository.save(TicketMessageEntity.builder()
                .id("msg-2")
                .ticketId("ticket-1")
                .runId("run-parent")
                .role(AfterSalesTypes.MessageRole.CUSTOMER)
                .content("photo attached")
                .createdAt(Instant.parse("2026-08-11T01:00:00Z"))
                .build());
        // 另一工单的消息不得混入。
        messageRepository.save(TicketMessageEntity.builder()
                .id("msg-3")
                .ticketId("ticket-2")
                .role(AfterSalesTypes.MessageRole.CUSTOMER)
                .content("other ticket")
                .createdAt(Instant.parse("2026-08-02T01:00:00Z"))
                .build());

        TicketAttachmentEntity image = attachmentRepository.save(TicketAttachmentEntity.builder()
                .id("att-1")
                .ticketId("ticket-1")
                .messageId("msg-2")
                .fileName("damage.jpg")
                .contentType("image/jpeg")
                .storageKey("objects/att-1")
                .checksum("sha256:abc")
                .metadataJson("{\"width\":1080}")
                .createdAt(Instant.parse("2026-08-11T01:00:01Z"))
                .build());
        attachmentRepository.save(TicketAttachmentEntity.builder()
                .id("att-2")
                .ticketId("ticket-1")
                .messageId("msg-1")
                .fileName("receipt.pdf")
                .contentType("application/pdf")
                .storageKey("objects/att-2")
                .createdAt(Instant.parse("2026-08-01T01:00:01Z"))
                .build());

        List<TicketMessageEntity> messages = messageRepository.findByTicketIdOrderByCreatedAtAsc("ticket-1");
        assertThat(messages).extracting(TicketMessageEntity::getId)
                .containsExactly("msg-1", "msg-2");
        assertThat(messages.get(1).getRunId()).isEqualTo("run-parent");

        List<TicketAttachmentEntity> attachments = attachmentRepository.findByMessageIdOrderByCreatedAtAsc("msg-2");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0).getId()).isEqualTo("att-1");

        // 图片附件判定：image/jpeg + 文件名 + 存储键 = 有效；PDF 不是。
        List<TicketAttachmentEntity> latestByTicket = attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-1");
        assertThat(latestByTicket).extracting(TicketAttachmentEntity::getId)
                .containsExactly("att-1", "att-2");
        assertThat(latestByTicket.get(0).isImage()).isTrue();
        assertThat(latestByTicket.get(1).isImage()).isFalse();
        assertThat(image.getUrl()).isNull();
        assertThat(image.getStorageKey()).isEqualTo("objects/att-1");
    }
}
