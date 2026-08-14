package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.DecisionRoute;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 确定性升级政策服务（纯 Java 规则，绝不 LLM 驱动）：在每次新证据收集后、任何不安全动作之前
 * 评估工单是否必须转人工升级（ESCALATED），或应等待外部调查（WAITING_EXTERNAL）。
 * 输入只有服务端可信状态（AfterSalesAgentState 内的不可变证据快照与决策路线），
 * 输出是不可变结构化原因（AfterSalesTypes.EscalationReason），模型绝不能提供或修改。
 *
 * 触发规则（评估优先级从高到低，命中即返回；外部等待只在没有任何升级时评估）：
 * 1. EVIDENCE_CONFLICT（证据矛盾，任何路线都触发）：
 *    - 订单快照运单号与物流/交付快照运单号不一致（至少两种在场快照的矛盾）；
 *    - 承运商已确认丢失（LOST_CONFIRMED）但同一订单存在已签收交付记录（DELIVERED）。
 * 2. CARRIER_INVESTIGATION_STALE（承运商调查停滞）：LOST_IN_TRANSIT 承运商案件 outcome 为
 *    UNDER_INVESTIGATION 且自案件开启（openedAt；缺失时以 lastUpdatedAt 计）起超过 7 天
 *    （CARRIER_INVESTIGATION_SLA_DAYS）。
 * 3. HIGH_VALUE_ORDER（高价值订单，仅补偿评估路线）：订单已付款金额超过按币种取值的固定仿真阈值。
 *
 * 承运商调查仍在 SLA 内（≤ 7 天，UNDER_INVESTIGATION）→ 不升级，返回外部等待原因
 * （CARRIER_INVESTIGATION_ACTIVE）：调查进行中且未超时，工单等待外部结果而不是失败或升级。
 * 两条承运商调查规则（规则 2 与外部等待）都只对 LOST_IN_TRANSIT 问题类型生效：intake 缺失或
 * issueType 不是 LOST_IN_TRANSIT 时，UNDER_INVESTIGATION 案件快照一律忽略。
 *
 * 高价值订单阈值（固定仿真值，按 SEA 币种档位；文档化以便调整）：
 * SGD 1,000 / MYR 1,500 / THB 5,000 / IDR 10,000,000 / VND 5,000,000；
 * 未覆盖币种取保守默认 1,000（任何币种的大额订单都不得由 Agent 自动决策补偿）。
 * 既有演示种子校验：O-SG-1001（SGD 588）、O-VN-5002（VND 1,899,000）、O-VN-5003（VND 459,000）、
 * O-ID-4001（IDR 2,410,000）均低于各自阈值；O-MY-2001（MYR 1,833）高于 MYR 阈值，作为高价值演示。
 *
 * PLANNER_CONSECUTIVE_FALLBACK 的判定见 {@link #isTrueDegradedPlan}：任何 RULE_FALLBACK
 * （fallbackReason 非空且非 RULES_MODE）都算真实降级 —— 包括模型调用本身失败/不可用、
 * 严格解析拒绝（LLM_INVALID_JSON / LLM_INVALID_OUTPUT）与被业务规则拒绝的 LLM 规划
 * （LLM_INVALID_PLAN）；只有 RULES_MODE 是主动配置、永不视为降级。
 */
@Service
public class AfterSalesEscalationPolicyService {

    /** 承运商调查 SLA：UNDER_INVESTIGATION 且自最近更新起 ≤ 7 天 → WAITING_EXTERNAL；> 7 天 → 升级。 */
    public static final int CARRIER_INVESTIGATION_SLA_DAYS = 7;

    /** 升级原因码（run.stopReason 与 EscalationReason.code 直接使用）。 */
    public static final String CODE_UNSUPPORTED_ISSUE_TYPE = "UNSUPPORTED_ISSUE_TYPE";
    public static final String CODE_HIGH_VALUE_ORDER = "HIGH_VALUE_ORDER";
    public static final String CODE_EVIDENCE_CONFLICT = "EVIDENCE_CONFLICT";
    public static final String CODE_POLICY_NOT_COVERED = "POLICY_NOT_COVERED";
    public static final String CODE_CARRIER_INVESTIGATION_STALE = "CARRIER_INVESTIGATION_STALE";
    public static final String CODE_PLANNER_CONSECUTIVE_FALLBACK = "PLANNER_CONSECUTIVE_FALLBACK";
    /** 外部等待原因码（WAITING_EXTERNAL 终态的 stopReason 固定使用）。 */
    public static final String CODE_CARRIER_INVESTIGATION_ACTIVE = "CARRIER_INVESTIGATION_ACTIVE";

    /** 原因类别（EscalationReason.category）。 */
    public static final String CATEGORY_ISSUE_UNSUPPORTED = "ISSUE_UNSUPPORTED";
    public static final String CATEGORY_ORDER_RISK = "ORDER_RISK";
    public static final String CATEGORY_EVIDENCE_INTEGRITY = "EVIDENCE_INTEGRITY";
    public static final String CATEGORY_POLICY_COVERAGE = "POLICY_COVERAGE";
    public static final String CATEGORY_CARRIER_INVESTIGATION = "CARRIER_INVESTIGATION";
    public static final String CATEGORY_PLANNING_DEGRADED = "PLANNING_DEGRADED";

    /** 高价值订单固定仿真阈值（按 SEA 币种）：超过即视为高价值，仅补偿评估路线生效。 */
    static final Map<String, BigDecimal> HIGH_VALUE_THRESHOLDS = Map.of(
            "SGD", new BigDecimal("1000.00"),
            "MYR", new BigDecimal("1500.00"),
            "THB", new BigDecimal("5000.00"),
            "IDR", new BigDecimal("10000000.00"),
            "VND", new BigDecimal("5000000.00"));
    /** 未覆盖币种的保守默认阈值：任何币种的大额订单都不得由 Agent 自动决策补偿。 */
    static final BigDecimal DEFAULT_HIGH_VALUE_THRESHOLD = new BigDecimal("1000.00");

    /** 评估结果：升级原因与外部等待原因最多出现其一（外部等待只在没有任何升级时返回）。 */
    public record Assessment(
            AfterSalesTypes.EscalationReason escalation,
            AfterSalesTypes.ExternalWaitReason waiting
    ) {
    }

    /**
     * 评估入口（当前时间由调用方提供，测试可注入固定时刻）。任何升级命中即返回升级原因；
     * 没有升级但承运商调查进行中且仍在 SLA 内 → 返回外部等待原因；否则两者都为空。
     */
    public Assessment assess(AfterSalesAgentState state, Instant now) {
        if (state == null || now == null) {
            return new Assessment(null, null);
        }
        AfterSalesTypes.EscalationReason escalation = evidenceConflict(state, now)
                .or(() -> carrierInvestigationStale(state, now))
                .or(() -> highValueOrder(state, now))
                .orElse(null);
        AfterSalesTypes.ExternalWaitReason waiting = escalation == null
                ? carrierInvestigationActive(state, now).orElse(null)
                : null;
        return new Assessment(escalation, waiting);
    }

    /**
     * 评估入口（生产调用）：以当前时刻评估，任何升级命中即返回升级原因；
     * 没有升级但承运商调查进行中且仍在 SLA 内 → 返回外部等待原因；否则两者都为空。
     */
    public Assessment assess(AfterSalesAgentState state) {
        return assess(state, Instant.now());
    }

    /**
     * 真实降级的 LLM 规划判定：任何规则兜底（RULE_FALLBACK、fallbackReason 非空且非
     * RULES_MODE）都算真实降级 —— 包括模型调用本身失败/不可用（API key 缺失、超时、执行器
     * 繁忙、空响应、模型异常）、严格解析拒绝（LLM_INVALID_JSON / LLM_INVALID_OUTPUT）与被
     * 业务规则拒绝的 LLM 规划（LLM_INVALID_PLAN）；这些情况下模型从未产生可用规划。
     * RULES_MODE 是主动配置，永不视为降级。Agent 循环用它统计连续真实降级次数
     * （≥ 2 → PLANNER_CONSECUTIVE_FALLBACK 升级）。
     */
    public static boolean isTrueDegradedPlan(AfterSalesTypes.PlanningResult plan) {
        if (plan == null || !"RULE_FALLBACK".equals(plan.source()) || plan.fallbackReason() == null) {
            return false;
        }
        return !"RULES_MODE".equals(plan.fallbackReason());
    }

    /** EVIDENCE_CONFLICT：在场可信快照自相矛盾（运单号不一致，或承运商确认丢失与已签收交付并存）。
     *  运单号比较按 intake issueType 裁剪：DAMAGED_ITEM 只比较订单与交付快照，SHIPMENT_DELAY 与
     *  LOST_IN_TRANSIT 只比较订单与物流快照；intake 缺失时保守地比较全部在场快照。
     *  承运商 LOST_CONFIRMED 与已签收交付（DELIVERED）并存的矛盾只在 LOST_IN_TRANSIT 时评估。 */
    private static Optional<AfterSalesTypes.EscalationReason> evidenceConflict(
            AfterSalesAgentState state, Instant now) {
        Set<String> trackingNumbers = new LinkedHashSet<>();
        boolean shipmentPresent = state.getShipment() != null;
        boolean deliveryPresent = state.getDelivery() != null;
        if (state.getOrder() != null) {
            trackingNumbers.add(state.getOrder().trackingNumber());
        }
        String issueType = state.getIntake() == null ? null : state.getIntake().issueType();
        if (AfterSalesTypes.IntakeResult.DAMAGED_ITEM.equals(issueType)) {
            // DAMAGED_ITEM：运单号只与交付快照比较。
            if (deliveryPresent) {
                trackingNumbers.add(state.getDelivery().trackingNumber());
            }
        } else if (AfterSalesTypes.IntakeResult.SHIPMENT_DELAY.equals(issueType)
                || AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT.equals(issueType)) {
            // SHIPMENT_DELAY / LOST_IN_TRANSIT：运单号只与物流快照比较。
            if (shipmentPresent) {
                trackingNumbers.add(state.getShipment().trackingNumber());
            }
        } else {
            // intake 缺失或其他 issueType：保守地比较全部在场快照。
            if (shipmentPresent) {
                trackingNumbers.add(state.getShipment().trackingNumber());
            }
            if (deliveryPresent) {
                trackingNumbers.add(state.getDelivery().trackingNumber());
            }
        }
        if (trackingNumbers.size() > 1) {
            return Optional.of(reason(CODE_EVIDENCE_CONFLICT, CATEGORY_EVIDENCE_INTEGRITY,
                    "Trusted evidence contradicts itself: the compared snapshots carry different "
                            + "tracking numbers, so no decision can be made safely.",
                    state, now));
        }
        if (AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT.equals(issueType)
                && state.getCarrierCase() != null && deliveryPresent
                && "LOST_CONFIRMED".equals(state.getCarrierCase().outcome())
                && "DELIVERED".equals(state.getDelivery().status())) {
            return Optional.of(reason(CODE_EVIDENCE_CONFLICT, CATEGORY_EVIDENCE_INTEGRITY,
                    "The carrier confirmed the parcel lost, but a delivered shipment is recorded "
                            + "for the same order; the evidence contradicts itself.",
                    state, now));
        }
        return Optional.empty();
    }

    /** CARRIER_INVESTIGATION_STALE：仅 LOST_IN_TRANSIT（且 intake 在场）时评估；承运商调查未关闭且
     *  自案件开启（openedAt；缺失时以 lastUpdatedAt 计）起超过 SLA 天数 → 升级。 */
    private static Optional<AfterSalesTypes.EscalationReason> carrierInvestigationStale(
            AfterSalesAgentState state, Instant now) {
        if (!isLostInTransitIntake(state)) {
            return Optional.empty();
        }
        AfterSalesTypes.CarrierCaseSnapshot carrierCase = state.getCarrierCase();
        if (carrierCase == null || !"UNDER_INVESTIGATION".equals(carrierCase.outcome())) {
            return Optional.empty();
        }
        if (investigationAgeDays(carrierCase, now) > CARRIER_INVESTIGATION_SLA_DAYS) {
            return Optional.of(reason(CODE_CARRIER_INVESTIGATION_STALE, CATEGORY_CARRIER_INVESTIGATION,
                    "The carrier investigation has been open for more than " + CARRIER_INVESTIGATION_SLA_DAYS
                            + " days with no resolution; the ticket must be escalated to an operator.",
                    state, now));
        }
        return Optional.empty();
    }

    /** 外部等待：仅 LOST_IN_TRANSIT（且 intake 在场）时评估；承运商调查进行中且仍在 SLA 内（≤ 7 天）
     *  → WAITING_EXTERNAL，不升级、不失败。 */
    private static Optional<AfterSalesTypes.ExternalWaitReason> carrierInvestigationActive(
            AfterSalesAgentState state, Instant now) {
        if (!isLostInTransitIntake(state)) {
            return Optional.empty();
        }
        AfterSalesTypes.CarrierCaseSnapshot carrierCase = state.getCarrierCase();
        if (carrierCase == null || !"UNDER_INVESTIGATION".equals(carrierCase.outcome())) {
            return Optional.empty();
        }
        if (investigationAgeDays(carrierCase, now) <= CARRIER_INVESTIGATION_SLA_DAYS) {
            // 复查时刻确定性计算：案件开启时刻 + 调查 SLA（openedAt 缺失时以 lastUpdatedAt 计）。
            Instant anchor = carrierCase.openedAt() != null ? carrierCase.openedAt() : carrierCase.lastUpdatedAt();
            Instant nextReviewAt = anchor == null
                    ? null
                    : anchor.plus(CARRIER_INVESTIGATION_SLA_DAYS, ChronoUnit.DAYS);
            return Optional.of(new AfterSalesTypes.ExternalWaitReason(
                    CODE_CARRIER_INVESTIGATION_ACTIVE,
                    "The carrier investigation is active and within the " + CARRIER_INVESTIGATION_SLA_DAYS
                            + "-day SLA; the ticket waits for the external investigation result.",
                    carrierCase.caseId(),
                    nextReviewAt,
                    List.copyOf(state.getEvidenceIds())));
        }
        return Optional.empty();
    }

    /** 承运商调查规则（STALE / ACTIVE）只对 LOST_IN_TRANSIT 问题类型生效：
     *  intake 缺失或 issueType 不是 LOST_IN_TRANSIT 时，UNDER_INVESTIGATION 案件快照一律忽略。 */
    private static boolean isLostInTransitIntake(AfterSalesAgentState state) {
        return state.getIntake() != null
                && AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT.equals(state.getIntake().issueType());
    }

    /** 调查年龄：自案件开启时刻（openedAt；缺失时以 lastUpdatedAt 计）至现在的天数；未来时刻返回非正数（视为在 SLA 内）。 */
    private static long investigationAgeDays(AfterSalesTypes.CarrierCaseSnapshot carrierCase, Instant now) {
        Instant anchor = carrierCase.openedAt() != null ? carrierCase.openedAt() : carrierCase.lastUpdatedAt();
        return anchor == null ? Long.MAX_VALUE : ChronoUnit.DAYS.between(anchor, now);
    }

    /** HIGH_VALUE_ORDER：仅补偿评估路线（涉及自动决策补偿）；订单金额超过按币种取值的阈值。 */
    private static Optional<AfterSalesTypes.EscalationReason> highValueOrder(
            AfterSalesAgentState state, Instant now) {
        if (state.getRoute() != DecisionRoute.COMPENSATION_EVALUATION || state.getOrder() == null) {
            return Optional.empty();
        }
        BigDecimal threshold = HIGH_VALUE_THRESHOLDS.getOrDefault(
                state.getOrder().currency(), DEFAULT_HIGH_VALUE_THRESHOLD);
        if (state.getOrder().paidAmount().compareTo(threshold) > 0) {
            return Optional.of(reason(CODE_HIGH_VALUE_ORDER, CATEGORY_ORDER_RISK,
                    "The paid order value exceeds the high-value threshold for its currency; "
                            + "compensation decisions on high-value orders require an operator.",
                    state, now));
        }
        return Optional.empty();
    }

    /** 统一原因构造：携带触发时刻已收集的证据 ID（复制为不可变清单）。 */
    private static AfterSalesTypes.EscalationReason reason(
            String code, String category, String summary, AfterSalesAgentState state, Instant detectedAt) {
        List<String> evidenceIds = new ArrayList<>(state.getEvidenceIds());
        return new AfterSalesTypes.EscalationReason(
                code, category, summary, List.copyOf(evidenceIds), detectedAt);
    }

    /** UNSUPPORTED_ISSUE_TYPE 升级原因（确定性模板）：超范围诉求（取消/退换/滥用）零工具转人工。 */
    public static AfterSalesTypes.EscalationReason unsupportedIssue(
            List<String> evidenceIds, Instant detectedAt) {
        return new AfterSalesTypes.EscalationReason(
                CODE_UNSUPPORTED_ISSUE_TYPE, CATEGORY_ISSUE_UNSUPPORTED,
                "The customer request is outside the supported after-sales scope "
                        + "(cancel order, exchange/return, account/payment abuse) and "
                        + "must be handled by an operator.",
                List.copyOf(evidenceIds), detectedAt);
    }

    /** POLICY_NOT_COVERED 升级原因（确定性模板）：政策查找无适用版本（POLICY_NOT_FOUND）。 */
    public static AfterSalesTypes.EscalationReason policyNotCovered(
            List<String> evidenceIds, Instant detectedAt) {
        return new AfterSalesTypes.EscalationReason(
                CODE_POLICY_NOT_COVERED, CATEGORY_POLICY_COVERAGE,
                "No applicable after-sales policy covers this country and issue at the ticket "
                        + "occurrence time; the ticket must be escalated to an operator.",
                List.copyOf(evidenceIds), detectedAt);
    }

    /** PLANNER_CONSECUTIVE_FALLBACK 升级原因（确定性模板）：连续 2 次真实 LLM 规划降级。 */
    public static AfterSalesTypes.EscalationReason plannerConsecutiveFallback(
            List<String> evidenceIds, Instant detectedAt) {
        return new AfterSalesTypes.EscalationReason(
                CODE_PLANNER_CONSECUTIVE_FALLBACK, CATEGORY_PLANNING_DEGRADED,
                "The LLM evidence planner has been degraded for 2 consecutive planning cycles; "
                        + "the run can no longer rely on model planning and must be escalated.",
                List.copyOf(evidenceIds), detectedAt);
    }
}
