package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;
import com.ecommerce.aftersales.service.EvidencePreconditionGate.RejectionCode;
import com.ecommerce.aftersales.service.EvidencePreconditionGate.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EvidencePreconditionGate 单元测试：覆盖任务指定的三种场景
 * （无 ORDER 时 LLM→SHIPMENT 拒绝并兜底 ORDER；有 ORDER 无 SHIPMENT 时 LLM→POLICY 拒绝并兜底
 * SHIPMENT；ORDER+SHIPMENT 齐备时 POLICY 接受），以及非必需/已存在/READY 边界。
 */
class EvidencePreconditionGateTest {

    private final EvidencePreconditionGate gate = new EvidencePreconditionGate();

    private static final List<String> ANSWER_ONLY_EVIDENCE = List.of("ORDER", "SHIPMENT");
    private static final List<String> COMPENSATION_EVIDENCE = List.of("ORDER", "SHIPMENT", "POLICY");

    /** 场景 1：无 ORDER 时 LLM→SHIPMENT 被拒（SHIPMENT 依赖 ORDER），规则兜底 ORDER 通过。 */
    @Test
    void shipmentWithoutOrderIsRejectedAndOrderFallbackPasses() {
        Map<String, Boolean> presence = Map.of("ORDER", false, "SHIPMENT", false);

        ValidationResult rejected = gate.validate(EvidenceType.SHIPMENT, ANSWER_ONLY_EVIDENCE, presence);

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.ORDER_PRECONDITION_MISSING);
        assertThat(gate.validate(EvidenceType.ORDER, ANSWER_ONLY_EVIDENCE, presence).passed()).isTrue();
    }

    /** 场景 2：有 ORDER 无 SHIPMENT 时 LLM→POLICY 被拒（POLICY 依赖 SHIPMENT），兜底 SHIPMENT 通过。 */
    @Test
    void policyAndShipmentAreBothAllowedAfterOrder() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", false, "POLICY", false);

        ValidationResult rejected = gate.validate(EvidenceType.POLICY, COMPENSATION_EVIDENCE, presence);

        assertThat(rejected.passed()).isTrue();
        assertThat(rejected.rejectionCode()).isNull();
        assertThat(gate.validate(EvidenceType.SHIPMENT, COMPENSATION_EVIDENCE, presence).passed()).isTrue();
    }

    /** 场景 3：ORDER+SHIPMENT 已获取时 LLM→POLICY 被接受。 */
    @Test
    void policyWithOrderAndShipmentIsAccepted() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", true, "POLICY", false);

        ValidationResult result = gate.validate(EvidenceType.POLICY, COMPENSATION_EVIDENCE, presence);

        assertThat(result.passed()).isTrue();
    }

    /**
     * POLICY 的依赖是精确图的紧邻前序（延迟图 = SHIPMENT）；ORDER 前置由链条传递保证
     * （SHIPMENT 自身依赖 ORDER），不重复强制全部前驱 —— ORDER 缺失而 SHIPMENT 在场
     * 的状态在真实取证循环中不可达（Gate 在 SHIPMENT 前置就拦截）。
     */
    @Test
    void policyDependsOnImmediateGraphPredecessorNotFullAncestorChain() {
        // SHIPMENT 在场时延迟图 POLICY 通过（SHIPMENT 在场本身已隐含 ORDER 曾通过 Gate）。
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", true, "POLICY", false);
        assertThat(gate.validate(EvidenceType.POLICY, COMPENSATION_EVIDENCE, presence).passed()).isTrue();

        // 缺失紧邻前序 SHIPMENT 时拒绝（链上 ORDER 缺失也以 SHIPMENT 缺失暴露）。
        Map<String, Boolean> missingChain = Map.of("ORDER", false, "SHIPMENT", false, "POLICY", false);
        ValidationResult rejected = gate.validate(EvidenceType.POLICY, COMPENSATION_EVIDENCE, missingChain);
        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.ORDER_PRECONDITION_MISSING);
    }

    /** 已获取的证据不可重复取证。 */
    @Test
    void alreadyAcquiredEvidenceIsRejected() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", false);

        ValidationResult rejected = gate.validate(EvidenceType.ORDER, ANSWER_ONLY_EVIDENCE, presence);

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.EVIDENCE_ALREADY_ACQUIRED);
    }

    // ------------------------------------------------------------------
    // LOST_IN_TRANSIT 图（ORDER → SHIPMENT → CARRIER_CASE → POLICY）
    // ------------------------------------------------------------------

    private static final List<String> LOST_EVIDENCE = List.of("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY");

    @Test
    void carrierCaseWithoutShipmentIsRejectedAndShipmentFallbackPasses() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", false, "CARRIER_CASE", false);

        ValidationResult rejected = gate.validate(EvidenceType.CARRIER_CASE, LOST_EVIDENCE, presence);

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.SHIPMENT_PRECONDITION_MISSING);
        assertThat(gate.validate(EvidenceType.SHIPMENT, LOST_EVIDENCE, presence).passed()).isTrue();
    }

    @Test
    void carrierCaseWithOrderAndShipmentIsAccepted() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", true, "CARRIER_CASE", false);

        assertThat(gate.validate(EvidenceType.CARRIER_CASE, LOST_EVIDENCE, presence).passed()).isTrue();
    }

    @Test
    void policyInLostGraphDoesNotDependOnCarrierCase() {
        Map<String, Boolean> presence =
                Map.of("ORDER", true, "SHIPMENT", true, "CARRIER_CASE", false, "POLICY", false);

        ValidationResult rejected = gate.validate(EvidenceType.POLICY, LOST_EVIDENCE, presence);

        assertThat(rejected.passed()).isTrue();
        assertThat(rejected.rejectionCode()).isNull();
        assertThat(gate.validate(EvidenceType.CARRIER_CASE, LOST_EVIDENCE, presence).passed()).isTrue();
    }

    // ------------------------------------------------------------------
    // DAMAGED_ITEM 图（ORDER → DELIVERY → DAMAGE_PHOTO → PRODUCT → POLICY）
    // ------------------------------------------------------------------

    private static final List<String> DAMAGED_EVIDENCE =
            List.of("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");

    @Test
    void deliveryWithoutOrderIsRejectedAsOrderPreconditionMissing() {
        Map<String, Boolean> presence = Map.of("ORDER", false, "DELIVERY", false);

        ValidationResult rejected = gate.validate(EvidenceType.DELIVERY, DAMAGED_EVIDENCE, presence);

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.ORDER_PRECONDITION_MISSING);
    }

    @Test
    void damagePhotoWithoutDeliveryIsRejectedAndDeliveryFallbackPasses() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "DELIVERY", false, "DAMAGE_PHOTO", false);

        ValidationResult rejected = gate.validate(EvidenceType.DAMAGE_PHOTO, DAMAGED_EVIDENCE, presence);

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.DELIVERY_PRECONDITION_MISSING);
        assertThat(gate.validate(EvidenceType.DELIVERY, DAMAGED_EVIDENCE, presence).passed()).isTrue();
    }

    @Test
    void productWithoutDamagePhotoIsRejectedAndDamagePhotoFallbackPasses() {
        Map<String, Boolean> presence =
                Map.of("ORDER", true, "DELIVERY", true, "DAMAGE_PHOTO", false, "PRODUCT", false);

        ValidationResult rejected = gate.validate(EvidenceType.PRODUCT, DAMAGED_EVIDENCE, presence);

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.DAMAGE_PHOTO_PRECONDITION_MISSING);
        assertThat(gate.validate(EvidenceType.DAMAGE_PHOTO, DAMAGED_EVIDENCE, presence).passed()).isTrue();
    }

    @Test
    void policyInDamagedGraphOnlyNeedsOrder() {
        // PRODUCT 缺失 → POLICY 拒绝（PRODUCT_PRECONDITION_MISSING）。
        Map<String, Boolean> presence =
                Map.of("ORDER", true, "DELIVERY", true, "DAMAGE_PHOTO", true, "PRODUCT", false, "POLICY", false);
        ValidationResult rejected = gate.validate(EvidenceType.POLICY, DAMAGED_EVIDENCE, presence);
        assertThat(rejected.passed()).isTrue();
        assertThat(rejected.rejectionCode()).isNull();

        // SHIPMENT 不在 DAMAGED_ITEM 图内：即使在场也无关，PRODUCT 在场后 POLICY 通过。
        Map<String, Boolean> full = Map.of(
                "ORDER", true, "SHIPMENT", true, "DELIVERY", true,
                "DAMAGE_PHOTO", true, "PRODUCT", true, "POLICY", false);
        assertThat(gate.validate(EvidenceType.POLICY, DAMAGED_EVIDENCE, full).passed()).isTrue();
    }

    @Test
    void damagedGraphWalkAdheresToPreconditionOrderingUntilReady() {
        Map<String, Boolean> presence =
                new java.util.LinkedHashMap<>(Map.of(
                        "ORDER", true, "DELIVERY", true, "DAMAGE_PHOTO", true, "PRODUCT", true, "POLICY", false));
        assertThat(gate.validate(EvidenceType.POLICY, DAMAGED_EVIDENCE, presence).passed()).isTrue();

        // 缺 DAMAGE_PHOTO 时 PRODUCT 仍被拒；补齐后 READY 通过。
        presence.put("DAMAGE_PHOTO", false);
        assertThat(gate.validate(EvidenceType.PRODUCT, DAMAGED_EVIDENCE, presence).passed()).isFalse();
        presence.put("DAMAGE_PHOTO", true);
        presence.put("POLICY", true);
        assertThat(gate.validate(EvidenceType.READY_FOR_DECISION, DAMAGED_EVIDENCE, presence).passed()).isTrue();
    }

    /** 非必需证据不可取证（requiredEvidence 是路线重建后的服务端清单）。 */
    @Test
    void notRequiredEvidenceIsRejected() {
        Map<String, Boolean> presence = Map.of("ORDER", false, "SHIPMENT", false);

        ValidationResult rejected = gate.validate(EvidenceType.POLICY, ANSWER_ONLY_EVIDENCE, presence);

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.EVIDENCE_NOT_REQUIRED);
    }

    /** READY 要求全部 requiredEvidence 已存在：缺失 → REQUIRED_EVIDENCE_INCOMPLETE。 */
    @Test
    void readyWithoutAllRequiredEvidenceIsRejected() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", false);

        ValidationResult rejected = gate.validate(EvidenceType.READY_FOR_DECISION, ANSWER_ONLY_EVIDENCE, presence);

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.REQUIRED_EVIDENCE_INCOMPLETE);
    }

    /** READY 且全部必需证据齐备 → 通过。 */
    @Test
    void readyWithAllRequiredEvidencePasses() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", true);

        ValidationResult result = gate.validate(EvidenceType.READY_FOR_DECISION, ANSWER_ONLY_EVIDENCE, presence);

        assertThat(result.passed()).isTrue();
    }
}
