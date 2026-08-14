package com.ecommerce.aftersales.repository;

import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketAttachmentRepository extends JpaRepository<TicketAttachmentEntity, String> {
    List<TicketAttachmentEntity> findByMessageIdOrderByCreatedAtAsc(String messageId);

    /** 工单附件按时间倒序：破损照片取证取最新的有效图片附件。 */
    List<TicketAttachmentEntity> findByTicketIdOrderByCreatedAtDesc(String ticketId);
}
