package com.ecommerce.aftersales.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public final class AfterSalesTypes {

    private AfterSalesTypes() {
    }

    public enum TicketStatus {
        OPEN,
        ANALYZING,
        PENDING_APPROVAL,
        RESOLVED,
        FAILED
    }

    public enum ProposalStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    public enum ExecutionStatus {
        PENDING,
        RUNNING,
        RETRY_WAIT,
        SUCCEEDED,
        DEAD_LETTER
    }

    public record OrderSnapshot(
            String orderId,
            String userId,
            String platform,
            String country,
            String currency,
            String warehouseRegion,
            BigDecimal paidAmount,
            boolean paid,
            String fulfillmentStatus,
            int promisedDeliveryDays,
            String trackingNumber
    ) {
    }

    public record ShipmentCheckpoint(Instant occurredAt, String status, String location, String description) {
    }

    public record ShipmentSnapshot(
            String trackingNumber,
            String status,
            Instant lastUpdatedAt,
            int inactiveDays,
            int delayDays,
            List<ShipmentCheckpoint> timeline
    ) {
    }

    public record PolicyEvidence(
            String evidenceId,
            String policyId,
            String version,
            String country,
            String issueType,
            Instant effectiveFrom,
            int minimumInactiveDays,
            BigDecimal compensationRate,
            BigDecimal maximumCompensation,
            String actionType,
            String section,
            String summary
    ) {
    }

    public record CompensationResult(
            boolean eligible,
            String actionType,
            BigDecimal amount,
            String currency,
            String reason
    ) {
    }

    /**
     * Intake Agent 的不可变结构化分类结果。字段不可变（record），
     * 只承载分类数据，不承载任何可执行内容；决策端从不读取模型生成的金额/工具参数/批准结果。
     */
    public record IntakeResult(
            String issueType,
            List<String> intents,
            String urgency,
            Map<String, Object> entities,
            List<String> missingInfo,
            List<String> requiredEvidence,
            String source,
            String fallbackReason,
            long latencyMs
    ) {
        /** MVP 固定问题类型：当前只受理物流延迟。 */
        public static final String SHIPMENT_DELAY = "SHIPMENT_DELAY";
        /** 服务端重建的必需证据基线：模型输出不能减少。 */
        public static final List<String> REQUIRED_EVIDENCE = List.of("ORDER", "SHIPMENT", "POLICY");

        /** 规则兜底构造（source=RULE_FALLBACK）。fallbackReason 只允许安全错误码，见 AfterSalesIntakeService。 */
        public static IntakeResult rulesFallback(String fallbackReason, long latencyMs) {
            return new IntakeResult(SHIPMENT_DELAY, List.of("TRACK_SHIPMENT"), "LOW", Map.of(),
                    List.of(), REQUIRED_EVIDENCE, "RULE_FALLBACK", fallbackReason, latencyMs);
        }
    }

    public record ToolResult(String summary, Object data, List<String> evidenceIds) {
    }

    public record ExecutionResult(String externalReference, String status, Instant executedAt) {
    }
}
