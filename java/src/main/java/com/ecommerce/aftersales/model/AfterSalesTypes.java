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
     * requiredEvidence 在此只是「模型/规则的不可信建议」：Agent 循环在分类后用
     * DecisionRouteResolver 的服务端路线证据重建它（withRequiredEvidence），建议既不能降低
     * 也不能抬高服务端路线要求。
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
        /** 规则兜底的必需证据占位基线（COMPENSATION_EVALUATION 全量）；最终由路线解析器重建。 */
        public static final List<String> REQUIRED_EVIDENCE = List.of("ORDER", "SHIPMENT", "POLICY");

        /** 规则兜底构造（source=RULE_FALLBACK）。fallbackReason 只允许安全错误码，见 AfterSalesIntakeService。 */
        public static IntakeResult rulesFallback(String fallbackReason, long latencyMs) {
            return new IntakeResult(SHIPMENT_DELAY, List.of("TRACK_SHIPMENT"), "LOW", Map.of(),
                    List.of(), REQUIRED_EVIDENCE, "RULE_FALLBACK", fallbackReason, latencyMs);
        }

        /** 服务端重建必需证据：分类字段原样保留，只替换 requiredEvidence（不可信建议 → 路线要求）。 */
        public IntakeResult withRequiredEvidence(List<String> trustedEvidence) {
            return new IntakeResult(issueType, intents, urgency, entities, missingInfo,
                    trustedEvidence, source, fallbackReason, latencyMs);
        }
    }

    public record ToolResult(String summary, Object data, List<String> evidenceIds) {
    }

    public record ExecutionResult(String externalReference, String status, Instant executedAt) {
    }

    /**
     * 不可变证据类型：Evidence Planner 只允许在这四个值之间规划。
     * ORDER/SHIPMENT/POLICY 对应只读取证工具；READY_FOR_DECISION 表示证据齐备、离开取证循环。
     * 模型输出中出现这四个值之外的任何证据名都视为非法。
     */
    public enum EvidenceType {
        ORDER,
        SHIPMENT,
        POLICY,
        READY_FOR_DECISION
    }

    /**
     * Evidence Planner 的不可变规划结果。字段不可变（record），只承载规划数据：
     * - nextEvidence：下一步要收集的证据（或 READY_FOR_DECISION；invalidInput 时为占位值，不可执行）；
     * - reasonCode：安全理由码（ORDER_CONTEXT_REQUIRED / SHIPMENT_STATUS_REQUIRED / POLICY_REQUIRED /
     *   EVIDENCE_COMPLETE / INVALID_REQUIRED_EVIDENCE），模型给出的理由码经过白名单与精确配对校验；
     * - source：LLM 或 RULE_FALLBACK；
     * - fallbackReason：仅允许安全错误码（见 AfterSalesEvidencePlannerService），非降级结果为 null；
     * - latencyMs：本次规划耗时（毫秒）；
     * - invalidInput：服务端输入非法（如 requiredEvidence 含未知证据）时为 true，调用方必须拒绝
     *   该规划（Agent 循环以 PLANNER_INVALID_REQUIRED_EVIDENCE 失败），不得执行也不得声称 READY。
     */
    public record PlanningResult(
            EvidenceType nextEvidence,
            String reasonCode,
            String source,
            String fallbackReason,
            long latencyMs,
            boolean invalidInput
    ) {
        /** 便捷构造：正常（非无效输入）规划结果。 */
        public PlanningResult(
                EvidenceType nextEvidence,
                String reasonCode,
                String source,
                String fallbackReason,
                long latencyMs) {
            this(nextEvidence, reasonCode, source, fallbackReason, latencyMs, false);
        }
    }
}
