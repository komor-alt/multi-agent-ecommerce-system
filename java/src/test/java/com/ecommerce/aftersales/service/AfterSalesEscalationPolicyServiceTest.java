package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.DecisionRoute;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 3 升级政策服务的确定性触发测试：每个触发条件、状态区分、阈值边界与
 * 「真实降级」判定（只有 RULES_MODE 不计入；业务规则拒绝与严格解析拒绝都计入）。
 */
class AfterSalesEscalationPolicyServiceTest {

    private final AfterSalesEscalationPolicyService policy = new AfterSalesEscalationPolicyService();
    private static final Instant NOW = Instant.parse("2026-08-14T00:00:00Z");

    private AfterSalesAgentState state(String orderId, String currency, String amount,
                                       String trackingNumber, DecisionRoute route) {
        AfterSalesTicketEntity ticket = AfterSalesTicketEntity.builder()
                .id("ticket-1")
                .ticketNo("AS-1")
                .orderId(orderId)
                .issueType("SHIPMENT_DELAY")
                .customerMessage("msg")
                .status(AfterSalesTypes.TicketStatus.ANALYZING)
                .createdAt(Instant.parse("2026-08-01T00:00:00Z"))
                .build();
        AfterSalesAgentState state = new AfterSalesAgentState("run-1", ticket);
        state.setRoute(route);
        state.setOrder(new AfterSalesTypes.OrderSnapshot(
                orderId, "user-1", "shopify", countryOf(orderId), currency, "SG",
                new BigDecimal(amount), true, "shipping", 7, trackingNumber));
        return state;
    }

    private static String countryOf(String orderId) {
        return orderId.substring(2, 4);
    }

    // ------------------------------------------------------------------
    // HIGH_VALUE_ORDER：既有种子保持非高价值，O-MY-2001 为高价值演示
    // ------------------------------------------------------------------

    @Test
    void existingSeedsStayBelowHighValueThresholds() {
        // 固定仿真阈值（见策略服务 javadoc）：SGD 1000 / MYR 1500 / THB 5000 /
        // IDR 10,000,000 / VND 5,000,000；以下既有种子全部保持非高价值。
        assertThat(policy.assess(state("O-SG-1001", "SGD", "588.00", "SF-SG-1001",
                DecisionRoute.COMPENSATION_EVALUATION), NOW).escalation()).isNull();
        assertThat(policy.assess(state("O-VN-5002", "VND", "1899000.00", "SF-VN-5002",
                DecisionRoute.COMPENSATION_EVALUATION), NOW).escalation()).isNull();
        assertThat(policy.assess(state("O-VN-5003", "VND", "459000.00", "SF-VN-5003",
                DecisionRoute.COMPENSATION_EVALUATION), NOW).escalation()).isNull();
        assertThat(policy.assess(state("O-ID-4001", "IDR", "2410000.00", "SF-ID-4001",
                DecisionRoute.COMPENSATION_EVALUATION), NOW).escalation()).isNull();
    }

    @Test
    void my2001CrossesMyrThresholdAndEscalatesHighValueOrder() {
        AfterSalesEscalationPolicyService.Assessment assessment = policy.assess(
                state("O-MY-2001", "MYR", "1833.00", "SF-MY-2001",
                        DecisionRoute.COMPENSATION_EVALUATION), NOW);

        assertThat(assessment.escalation()).isNotNull();
        assertThat(assessment.escalation().code()).isEqualTo(AfterSalesEscalationPolicyService.CODE_HIGH_VALUE_ORDER);
        assertThat(assessment.escalation().category()).isEqualTo(AfterSalesEscalationPolicyService.CATEGORY_ORDER_RISK);
        assertThat(assessment.escalation().detectedAt()).isEqualTo(NOW);
        assertThat(assessment.waiting()).isNull();
    }

    @Test
    void highValueOrderOnlyEscalatesOnCompensationEvaluationRoute() {
        // 纯查询（ANSWER_ONLY）不涉及自动决策补偿：高价值订单也只答复物流状态，不升级。
        AfterSalesEscalationPolicyService.Assessment assessment = policy.assess(
                state("O-MY-2001", "MYR", "1833.00", "SF-MY-2001", DecisionRoute.ANSWER_ONLY), NOW);
        assertThat(assessment.escalation()).isNull();
    }

    // ------------------------------------------------------------------
    // EVIDENCE_CONFLICT：运单号矛盾 / 承运商确认丢失与已签收交付并存
    // ------------------------------------------------------------------

    @Test
    void conflictingTrackingNumbersEscalateEvidenceConflict() {
        AfterSalesAgentState state = state("O-SG-1001", "SGD", "588.00", "SF-SG-1001",
                DecisionRoute.COMPENSATION_EVALUATION);
        state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                "SF-SG-9999", "shipping", NOW, 10, 3, List.of()));

        AfterSalesEscalationPolicyService.Assessment assessment = policy.assess(state, NOW);

        assertThat(assessment.escalation()).isNotNull();
        assertThat(assessment.escalation().code()).isEqualTo(AfterSalesEscalationPolicyService.CODE_EVIDENCE_CONFLICT);
        assertThat(assessment.escalation().category())
                .isEqualTo(AfterSalesEscalationPolicyService.CATEGORY_EVIDENCE_INTEGRITY);
    }

    @Test
    void matchingTrackingNumbersDoNotEscalate() {
        AfterSalesAgentState state = state("O-SG-1001", "SGD", "588.00", "SF-SG-1001",
                DecisionRoute.COMPENSATION_EVALUATION);
        state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                "SF-SG-1001", "shipping", NOW, 10, 3, List.of()));
        state.setDelivery(new AfterSalesTypes.DeliverySnapshot(
                "delivery:O-SG-1001:2026-08-10", "DL-SG-1001", "SF-SG-1001", NOW,
                "Singapore SG", "RECEIVED", "DELIVERED", "SIGNATURE"));

        assertThat(policy.assess(state, NOW).escalation()).isNull();
    }

    @Test
    void carrierLostConfirmedWithDeliveredShipmentEscalatesEvidenceConflict() {
        AfterSalesAgentState state = state("O-SG-1001", "SGD", "588.00", "SF-SG-1001",
                DecisionRoute.COMPENSATION_EVALUATION);
        state.setIntake(intake(AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT));
        state.setCarrierCase(new AfterSalesTypes.CarrierCaseSnapshot(
                "carrier-case:CC-SG-1001:v1", "CC-SG-1001", "SF Express", "CLOSED",
                NOW.minus(10, ChronoUnit.DAYS), NOW.minus(10, ChronoUnit.DAYS),
                "LOST_CONFIRMED", "carrier confirmed lost"));
        state.setDelivery(new AfterSalesTypes.DeliverySnapshot(
                "delivery:O-SG-1001:2026-08-10", "DL-SG-1001", "SF-SG-1001", NOW,
                "Singapore SG", "RECEIVED", "DELIVERED", "SIGNATURE"));

        AfterSalesEscalationPolicyService.Assessment assessment = policy.assess(state, NOW);

        assertThat(assessment.escalation()).isNotNull();
        assertThat(assessment.escalation().code()).isEqualTo(AfterSalesEscalationPolicyService.CODE_EVIDENCE_CONFLICT);
    }

    // ------------------------------------------------------------------
    // 承运商调查：短调查（≤ 7 天）→ 外部等待；停滞（> 7 天）→ 升级
    // ------------------------------------------------------------------

    /** 指定问题类型的 Intake 分类（规则兜底形态；LOST_IN_TRANSIT 用 LOST_IN_TRANSIT_EVIDENCE 基线）。 */
    private static AfterSalesTypes.IntakeResult intake(String issueType) {
        return new AfterSalesTypes.IntakeResult(
                issueType, List.of("TRACK_SHIPMENT"), "LOW", Map.of(), List.of(),
                AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT.equals(issueType)
                        ? AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT_EVIDENCE
                        : AfterSalesTypes.IntakeResult.REQUIRED_EVIDENCE,
                "RULE_FALLBACK", "RULES_MODE", 0L);
    }

    private AfterSalesAgentState carrierState(String outcome, long ageDays) {
        AfterSalesAgentState state = state("O-VN-5003", "VND", "459000.00", "SF-VN-5003",
                DecisionRoute.COMPENSATION_EVALUATION);
        // 承运商调查规则只对 LOST_IN_TRANSIT 生效：既有调查行为测试都以 LOST_IN_TRANSIT intake 为基线。
        state.setIntake(intake(AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT));
        state.setCarrierCase(new AfterSalesTypes.CarrierCaseSnapshot(
                "carrier-case:CC-VN-5003:v1", "CC-VN-5003", "SF Express",
                "UNDER_INVESTIGATION".equals(outcome) ? "OPEN" : "CLOSED",
                NOW.minus(ageDays, ChronoUnit.DAYS), NOW.minus(ageDays, ChronoUnit.DAYS),
                outcome, "carrier investigation"));
        return state;
    }

    @Test
    void shortInvestigationWithinSlaReturnsWaitingExternalOnly() {
        // 2 天调查（O-VN-5003 演示）：仍在 SLA 内 → 外部等待，绝不升级。
        // 外部等待原因只承载 ExternalWaitReason 字段：code/summary/carrierCaseId/nextReviewAt/evidenceIds。
        AfterSalesAgentState state = carrierState("UNDER_INVESTIGATION", 2);
        state.getEvidenceIds().add("carrier-case:CC-VN-5003:v1");
        AfterSalesEscalationPolicyService.Assessment assessment = policy.assess(state, NOW);

        assertThat(assessment.escalation()).isNull();
        assertThat(assessment.waiting()).isNotNull();
        assertThat(assessment.waiting().code())
                .isEqualTo(AfterSalesEscalationPolicyService.CODE_CARRIER_INVESTIGATION_ACTIVE);
        assertThat(assessment.waiting().summary()).isNotBlank();
        assertThat(assessment.waiting().carrierCaseId()).isEqualTo("CC-VN-5003");
        // nextReviewAt 确定性计算：openedAt + 7 天（本次调查 openedAt = NOW - 2 天 → NOW + 5 天）。
        assertThat(assessment.waiting().nextReviewAt()).isEqualTo(NOW.plus(5, ChronoUnit.DAYS));
        assertThat(assessment.waiting().evidenceIds()).containsExactly("carrier-case:CC-VN-5003:v1");
    }

    @Test
    void sevenDayBoundaryStaysWaitingExternal() {
        AfterSalesEscalationPolicyService.Assessment assessment =
                policy.assess(carrierState("UNDER_INVESTIGATION", 7), NOW);
        assertThat(assessment.escalation()).isNull();
        assertThat(assessment.waiting()).isNotNull();
    }

    @Test
    void staleInvestigationOlderThanSlaEscalates() {
        // 10 天调查（O-ID-4001 演示）：超过 SLA → CARRIER_INVESTIGATION_STALE 升级。
        AfterSalesEscalationPolicyService.Assessment assessment =
                policy.assess(carrierState("UNDER_INVESTIGATION", 10), NOW);

        assertThat(assessment.escalation()).isNotNull();
        assertThat(assessment.escalation().code())
                .isEqualTo(AfterSalesEscalationPolicyService.CODE_CARRIER_INVESTIGATION_STALE);
        assertThat(assessment.waiting()).isNull();
    }

    @Test
    void closedInvestigationIsNeitherStaleNorWaiting() {
        AfterSalesEscalationPolicyService.Assessment assessment =
                policy.assess(carrierState("LOST_CONFIRMED", 10), NOW);
        assertThat(assessment.escalation()).isNull();
        assertThat(assessment.waiting()).isNull();
    }

    // ------------------------------------------------------------------
    // 承运商调查规则只对 LOST_IN_TRANSIT 生效：其他问题类型的案件快照一律忽略
    // ------------------------------------------------------------------

    @Test
    void underInvestigationCarrierSnapshotIgnoredForShipmentDelay() {
        // 10 天调查（> SLA，若生效会 CARRIER_INVESTIGATION_STALE 升级）+ SHIPMENT_DELAY intake：
        // 问题类型不符 → UNDER_INVESTIGATION 案件快照既不升级也不外部等待。
        AfterSalesAgentState state = carrierState("UNDER_INVESTIGATION", 10);
        state.setIntake(intake(AfterSalesTypes.IntakeResult.SHIPMENT_DELAY));

        AfterSalesEscalationPolicyService.Assessment assessment = policy.assess(state, NOW);

        assertThat(assessment.escalation()).isNull();
        assertThat(assessment.waiting()).isNull();
    }

    @Test
    void underInvestigationCarrierSnapshotIgnoredForDamagedItem() {
        // 2 天调查（≤ SLA，若生效会 CARRIER_INVESTIGATION_ACTIVE 外部等待）+ DAMAGED_ITEM intake：
        // 问题类型不符 → UNDER_INVESTIGATION 案件快照既不升级也不外部等待。
        AfterSalesAgentState state = carrierState("UNDER_INVESTIGATION", 2);
        state.setIntake(intake(AfterSalesTypes.IntakeResult.DAMAGED_ITEM));

        AfterSalesEscalationPolicyService.Assessment assessment = policy.assess(state, NOW);

        assertThat(assessment.escalation()).isNull();
        assertThat(assessment.waiting()).isNull();
    }

    @Test
    void underInvestigationCarrierSnapshotIgnoredWithoutIntake() {
        // intake 缺失（状态不完整）：UNDER_INVESTIGATION 案件快照同样不得触发升级或外部等待。
        AfterSalesAgentState state = carrierState("UNDER_INVESTIGATION", 10);
        state.setIntake(null);

        AfterSalesEscalationPolicyService.Assessment assessment = policy.assess(state, NOW);

        assertThat(assessment.escalation()).isNull();
        assertThat(assessment.waiting()).isNull();
    }

    // ------------------------------------------------------------------
    // 真实降级判定：RULES_MODE 与业务规则拒绝不计入连续降级
    // ------------------------------------------------------------------

    @Test
    void rulesModeIsNeverCountedAsTrueDegradation() {
        assertThat(AfterSalesEscalationPolicyService.isTrueDegradedPlan(
                new AfterSalesTypes.PlanningResult(AfterSalesTypes.EvidenceType.ORDER,
                        "ORDER_CONTEXT_REQUIRED", "RULE_FALLBACK", "RULES_MODE", 0))).isFalse();
    }

    @Test
    void businessRejectionsAndStrictParseRejectionsAreTrueDegradation() {
        // LLM_INVALID_PLAN（业务规则拒绝）、LLM_INVALID_JSON / LLM_INVALID_OUTPUT（严格解析拒绝）
        // 都算真实降级：任何非 RULES_MODE 的 RULE_FALLBACK 都破坏对模型规划的依赖，计入连续计数。
        for (String reason : List.of("LLM_INVALID_PLAN", "LLM_INVALID_JSON", "LLM_INVALID_OUTPUT", "INVALID_INPUT")) {
            assertThat(AfterSalesEscalationPolicyService.isTrueDegradedPlan(
                    new AfterSalesTypes.PlanningResult(AfterSalesTypes.EvidenceType.ORDER,
                            "ORDER_CONTEXT_REQUIRED", "RULE_FALLBACK", reason, 0)))
                    .as("reason %s", reason)
                    .isTrue();
        }
    }

    @Test
    void modelUnavailabilityIsTrueDegradation() {
        // 模型调用本身失败/不可用（key 缺失/超时/繁忙/空响应/异常）：真实降级，计入连续计数。
        for (String reason : List.of("LLM_API_KEY_MISSING", "LLM_TIMEOUT", "LLM_BUSY",
                "LLM_EMPTY_RESPONSE", "LLM_ERROR")) {
            assertThat(AfterSalesEscalationPolicyService.isTrueDegradedPlan(
                    new AfterSalesTypes.PlanningResult(AfterSalesTypes.EvidenceType.ORDER,
                            "ORDER_CONTEXT_REQUIRED", "RULE_FALLBACK", reason, 0)))
                    .as("reason %s", reason)
                    .isTrue();
        }
        // LLM 正常规划与非降级来源不计数。
        assertThat(AfterSalesEscalationPolicyService.isTrueDegradedPlan(
                new AfterSalesTypes.PlanningResult(AfterSalesTypes.EvidenceType.ORDER,
                        "ORDER_CONTEXT_REQUIRED", "LLM", null, 0))).isFalse();
        assertThat(AfterSalesEscalationPolicyService.isTrueDegradedPlan(null)).isFalse();
    }

    // ------------------------------------------------------------------
    // 原因结构：code/category/summary/evidenceIds/detectedAt 全部结构化
    // ------------------------------------------------------------------

    @Test
    void escalationReasonCarriesStructuredFieldsAndCollectedEvidenceIds() {
        AfterSalesAgentState state = state("O-MY-2001", "MYR", "1833.00", "SF-MY-2001",
                DecisionRoute.COMPENSATION_EVALUATION);
        state.getEvidenceIds().add("order:O-MY-2001:v1");

        AfterSalesTypes.EscalationReason reason = policy.assess(state, NOW).escalation();

        assertThat(reason.code()).isEqualTo(AfterSalesEscalationPolicyService.CODE_HIGH_VALUE_ORDER);
        assertThat(reason.category()).isEqualTo(AfterSalesEscalationPolicyService.CATEGORY_ORDER_RISK);
        assertThat(reason.summary()).isNotBlank();
        assertThat(reason.evidenceIds()).containsExactly("order:O-MY-2001:v1");
        assertThat(reason.detectedAt()).isEqualTo(NOW);
    }
}
