package com.ecommerce.aftersales.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * 工单消息的附件元数据记录：只存元数据（文件名/类型/地址或存储键/校验和/尺寸等），
 * 绝不存二进制内容。破损照片证据（DAMAGE_PHOTO）只能来自本表属于该工单的图片附件。
 */
@Entity
@Table(
        name = "after_sales_ticket_attachments",
        indexes = {
                @Index(name = "idx_as_attachment_ticket", columnList = "ticket_id"),
                @Index(name = "idx_as_attachment_message", columnList = "message_id")
        }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TicketAttachmentEntity {

    /** 附件元数据键：文件事实（客户可提供，消息 API 白名单键）。 */
    public static final String METADATA_WIDTH = "width";
    public static final String METADATA_HEIGHT = "height";
    public static final String METADATA_SIZE_BYTES = "sizeBytes";
    /** 附件元数据键：服务端核验结论（服务端持有；客户消息 API 白名单之外，不能由客户写入）。 */
    public static final String METADATA_REVIEW_STATUS = "reviewStatus";
    public static final String METADATA_REVIEW_SUMMARY = "reviewSummary";

    @Id
    private String id;

    @Column(name = "ticket_id", nullable = false)
    private String ticketId;

    @Column(name = "message_id", nullable = false)
    private String messageId;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String contentType;

    /** 附件访问地址或对象存储键，二者至少其一（不存二进制内容）。 */
    private String url;
    private String storageKey;

    /** 附件校验和（可选，如 sha256），不存二进制内容。 */
    private String checksum;

    /** 附件元数据（JSON：尺寸/大小/服务端核验结论等），不存二进制内容。 */
    @Lob
    private String metadataJson;

    @Column(nullable = false)
    private Instant createdAt;

    /** 是否是可作破损照片证据的图片附件：图片 MIME + 文件名 + 可访问地址/存储键。 */
    public boolean isImage() {
        return contentType != null && contentType.toLowerCase().startsWith("image/")
                && fileName != null && !fileName.isBlank()
                && (hasUrl() || hasStorageKey());
    }

    private boolean hasUrl() {
        return url != null && !url.isBlank();
    }

    private boolean hasStorageKey() {
        return storageKey != null && !storageKey.isBlank();
    }
}
