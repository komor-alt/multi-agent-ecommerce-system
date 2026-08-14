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
    void policyWithoutShipmentIsRejectedAndShipmentFallbackPasses() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", false, "POLICY", false);

        ValidationResult rejected = gate.validate(EvidenceType.POLICY, COMPENSATION_EVIDENCE, presence);

        assertThat(rejected.passed()).isFalse();
        assertThat(rejected.rejectionCode()).isEqualTo(RejectionCode.SHIPMENT_PRECONDITION_MISSING);
        assertThat(gate.validate(EvidenceType.SHIPMENT, COMPENSATION_EVIDENCE, presence).passed()).isTrue();
    }

    /** 场景 3：ORDER+SHIPMENT 已获取时 LLM→POLICY 被接受。 */
    @Test
    void policyWithOrderAndShipmentIsAccepted() {
        Map<String, Boolean> presence = Map.of("ORDER", true, "SHIPMENT", true, "POLICY", false);

        ValidationResult result = gate.validate(EvidenceType.POLICY, COMPENSATION_EVIDENCE, presence);

        assertThat(result.passed()).isTrue();
    }

    /** POLICY 也依赖 ORDER：ORDER 缺失时拒绝码为 ORDER_PRECONDITION_MISSING。 */
    @Test
    void policyWithoutOrderIsRejectedAsOrderPreconditionMissing() {
        Map<String, Boolean> presence = Map.of("ORDER", false, "SHIPMENT", true, "POLICY", false);

        ValidationResult rejected = gate.validate(EvidenceType.POLICY, COMPENSATION_EVIDENCE, presence);

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
