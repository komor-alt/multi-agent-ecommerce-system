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
        /** 等待客户补充信息（如破损照片）：客户消息/附件到达后可以恢复取证。 */
        WAITING_CUSTOMER,
        PENDING_APPROVAL,
        RESOLVED,
        FAILED,
        /** 等待外部调查（承运商调查进行中且仍在 SLA 内）：不是失败，也不是人工升级。 */
        WAITING_EXTERNAL,
        /** 转人工升级：Agent 无法安全决策，必须由运营人员处理，绝不创建方案。 */
        ESCALATED
    }

    /** 工单消息的发送方：CUSTOMER = 客户（创建工单或补充信息），SYSTEM = 系统侧请求/回复。 */
    public enum MessageRole {
        CUSTOMER,
        SYSTEM
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

    /**
     * 承运商理赔/调查案件的不可变快照（LOST_IN_TRANSIT 证据图节点 CARRIER_CASE）。
     * evidenceId 与全部字段在快照构造时固定（确定性派生，见 MockShopifyAfterSalesConnector），
     * 模型绝不能提供或修改；outcome 是承运商调查结论（UNDER_INVESTIGATION / LOST_CONFIRMED），
     * 由补偿规则读取。
     */
    public record CarrierCaseSnapshot(
            String evidenceId,
            String caseId,
            String carrierName,
            String status,
            Instant openedAt,
            Instant lastUpdatedAt,
            String outcome,
            String summary
    ) {
    }

    /**
     * 签收/派送证明的不可变快照（DAMAGED_ITEM 证据图节点 DELIVERY）。
     * evidenceId 确定性派生（delivery:&lt;orderId&gt;:&lt;交付日期&gt;），不可变；模型不能提供。
     */
    public record DeliverySnapshot(
            String evidenceId,
            String deliveryId,
            String trackingNumber,
            Instant deliveredAt,
            String deliveredLocation,
            String recipient,
            String status,
            String proofType
    ) {
    }

    /**
     * 破损照片的不可变快照（DAMAGED_ITEM 证据图节点 DAMAGE_PHOTO）。
     * evidenceId 确定性派生（damage-photo:&lt;photoId&gt;:v1），checksum 来自服务端持久化附件记录
     * （可选），均不可变、由服务端派生；模型不能提供。
     * status 是人工/系统核验结论（PENDING_REVIEW / VERIFIED / REJECTED），由补偿规则读取。
     */
    public record DamagePhotoSnapshot(
            String evidenceId,
            String photoId,
            Instant capturedAt,
            String contentType,
            int width,
            int height,
            long sizeBytes,
            String status,
            String reviewSummary,
            String checksum
    ) {
    }

    /**
     * 受损商品上下文的不可变快照（DAMAGED_ITEM 证据图节点 PRODUCT）。
     * evidenceId 确定性派生（product:&lt;productId&gt;:v1），不可变；模型不能提供。
     */
    public record ProductSnapshot(
            String evidenceId,
            String productId,
            String name,
            String category,
            BigDecimal listPrice,
            String currency,
            String brand,
            boolean crossBorderEligible
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
        /** 受理的问题类型：物流延迟（MVP 基线）。 */
        public static final String SHIPMENT_DELAY = "SHIPMENT_DELAY";
        /** 受理的问题类型：运输丢失（证据图 ORDER → SHIPMENT → CARRIER_CASE → POLICY）。 */
        public static final String LOST_IN_TRANSIT = "LOST_IN_TRANSIT";
        /** 受理的问题类型：商品破损（证据图 ORDER → DELIVERY → DAMAGE_PHOTO → PRODUCT → POLICY）。 */
        public static final String DAMAGED_ITEM = "DAMAGED_ITEM";
        /** 不受理的问题类型（取消订单 / 退换货 / 账号支付滥用等）：立即转人工升级，绝不取证或建方案。 */
        public static final String UNSUPPORTED = "UNSUPPORTED";
        /** 规则兜底的必需证据占位基线（SHIPMENT_DELAY 图全量）；最终由路线解析器按问题类型重建。 */
        public static final List<String> REQUIRED_EVIDENCE = List.of("ORDER", "SHIPMENT", "POLICY");
        /** 规则兜底的必需证据占位基线（LOST_IN_TRANSIT 图全量）；最终由路线解析器重建。 */
        public static final List<String> LOST_IN_TRANSIT_EVIDENCE =
                List.of("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY");
        /** 规则兜底的必需证据占位基线（DAMAGED_ITEM 图全量）；最终由路线解析器重建。 */
        public static final List<String> DAMAGED_ITEM_EVIDENCE =
                List.of("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");
        /** UNSUPPORTED 不需要任何证据：路线解析器重建为空清单（HUMAN_ESCALATION 路线）。 */
        public static final List<String> UNSUPPORTED_EVIDENCE = List.of();

        /** 规则兜底构造（source=RULE_FALLBACK，默认 SHIPMENT_DELAY 基线）。
         *  fallbackReason 只允许安全错误码，见 AfterSalesIntakeService。 */
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
     * 可恢复会话快照（WAITING_CUSTOMER 终态写入 run.resumeStateJson）：客户补充信息后，
     * 新 run 从父 run 还原此快照，继续取证而不重复已收集工具。order/delivery/intake 是不可变
     * 证据快照，恢复时原样还原；route 与 requiredEvidence 在恢复时由 DecisionRouteResolver
     * 重新确定性解析（持久化 JSON 只是数据，路线与必需证据永远由服务端重建）。
     */
    public record ResumeState(
            String parentRunId,
            AfterSalesTypes.OrderSnapshot order,
            AfterSalesTypes.DeliverySnapshot delivery,
            AfterSalesTypes.IntakeResult intake,
            DecisionRoute route,
            List<String> requiredEvidence,
            List<String> evidenceIds
    ) {
    }

    /**
     * 结构化人工升级原因（不可变）：由服务端确定性产生（AfterSalesEscalationPolicyService
     * 或 Agent 循环），模型绝不能提供。字段不可变，只承载结构化原因数据：
     * - code：安全原因码（UNSUPPORTED_ISSUE_TYPE / HIGH_VALUE_ORDER / EVIDENCE_CONFLICT /
     *   POLICY_NOT_COVERED / CARRIER_INVESTIGATION_STALE / PLANNER_CONSECUTIVE_FALLBACK），
     *   升级 run 的 stopReason 直接使用该码；
     * - category：原因类别（ISSUE_UNSUPPORTED / ORDER_RISK / EVIDENCE_INTEGRITY /
     *   POLICY_COVERAGE / CARRIER_INVESTIGATION / PLANNING_DEGRADED）；
     * - summary：安全摘要（确定性模板，不含客户消息原文）；
     * - evidenceIds：触发时刻已收集的可信证据 ID（升级时点）；UNSUPPORTED 为空的不可变清单；
     * - detectedAt：检测时刻（服务端单调时间）。
     * 对应工单状态 ESCALATED（转人工升级）。「外部等待」使用另一记录形态
     * ExternalWaitReason（对应工单状态 WAITING_EXTERNAL）；两者都持久化到工单
     * escalationReasonJson 并在 finalAnswer 输出。
     */
    public record EscalationReason(
            String code,
            String category,
            String summary,
            List<String> evidenceIds,
            Instant detectedAt
    ) {
    }

    /**
     * 结构化外部等待原因（不可变）：由服务端确定性产生（AfterSalesEscalationPolicyService），
     * 模型绝不能提供。与 EscalationReason 的区别：外部等待不是失败也不是人工升级，而是
     * 在外部条件（承运商调查）满足前挂起工单，满足后由后续 run 自动恢复。字段不可变：
     * - code：安全原因码（CARRIER_INVESTIGATION_ACTIVE），等待 run 的 stopReason 直接使用该码；
     * - summary：安全摘要（确定性模板，不含客户消息原文）；
     * - carrierCaseId：被等待的承运商案件 ID（可信快照派生，模型不能提供）；
     * - nextReviewAt：确定性计算的复查时刻（如 openedAt + 调查 SLA 天数）；
     * - evidenceIds：触发时刻已收集的可信证据 ID（等待时点）。
     * 对应工单状态 WAITING_EXTERNAL；持久化到工单 escalationReasonJson 并在 finalAnswer 输出。
     */
    public record ExternalWaitReason(
            String code,
            String summary,
            String carrierCaseId,
            Instant nextReviewAt,
            List<String> evidenceIds
    ) {
    }

    /**
     * 不可变证据类型：Evidence Planner 只允许在这七个取证值 + READY_FOR_DECISION 之间规划。
     * 每种证据对应一个只读取证工具；READY_FOR_DECISION 表示证据齐备、离开取证循环。
     * 模型输出中出现这些值之外的任何证据名都视为非法。
     *
     * 证据图（服务端重建的 requiredEvidence 顺序，见 DecisionRouteResolver）：
     * - SHIPMENT_DELAY：ORDER → SHIPMENT → POLICY；
     * - LOST_IN_TRANSIT：ORDER → SHIPMENT → CARRIER_CASE → POLICY；
     * - DAMAGED_ITEM：ORDER → DELIVERY → DAMAGE_PHOTO → PRODUCT → POLICY。
     */
    public enum EvidenceType {
        ORDER,
        SHIPMENT,
        CARRIER_CASE,
        DELIVERY,
        DAMAGE_PHOTO,
        PRODUCT,
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
