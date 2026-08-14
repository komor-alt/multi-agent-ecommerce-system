package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;

import java.util.List;
import java.util.Map;

/**
 * 证据业务前置校验 Gate（纯 Java 组件，无框架依赖）：Agent Loop 对规划结果
 * （LLM 规划与规则兜底一视同仁）执行的不可变校验，决定规划能否让工单推进。
 *
 * 依赖图完全来自路线重建后的 requiredEvidence 顺序（精确图，绝不引用全局固定顺序）：
 * 任意证据的前置依赖 = requiredEvidence 中紧邻其前的一项（SHIPMENT_DELAY =
 * ORDER→SHIPMENT→POLICY，LOST_IN_TRANSIT = ORDER→SHIPMENT→CARRIER_CASE→POLICY，
 * DAMAGED_ITEM = ORDER→DELIVERY→DAMAGE_PHOTO→PRODUCT→POLICY）：
 * - SHIPMENT 依赖 ORDER、CARRIER_CASE 依赖 SHIPMENT、DELIVERY 依赖 ORDER、
 *   DAMAGE_PHOTO 依赖 DELIVERY、PRODUCT 依赖 DAMAGE_PHOTO；
 * - POLICY 只依赖当前图紧邻其前的证据：SHIPMENT_DELAY 图 = SHIPMENT、
 *   LOST_IN_TRANSIT 图 = CARRIER_CASE、DAMAGED_ITEM 图 = PRODUCT —— 绝不强制
 *   与当前图无关或非紧邻的节点（如 DAMAGED_ITEM 图的 POLICY 不要求 SHIPMENT；
 *   前置链条已由各节点的紧邻依赖传递保证，无需重复强制全部前驱）。
 *
 * 校验顺序（任一失败即拒绝）：
 * 1. nextEvidence 必须属于路线重建后的 requiredEvidence（非必需证据不可取证）；
 * 2. 目标证据必须尚未获取（已存在证据不可重复取证）；
 * 3. 目标证据的紧邻前序依赖必须已在场；
 * 4. READY_FOR_DECISION 要求全部 requiredEvidence 已存在，否则不可宣称证据齐备。
 *
 * 与 AfterSalesEvidencePlannerService 的输入合法性校验（invalidInput /
 * PLANNER_INVALID_REQUIRED_EVIDENCE）分层：本 Gate 假设 requiredEvidence 已是
 * 路线重建后的合法清单（未知证据名在 Planner 入口即被拒绝），只负责业务前置。
 */
public final class EvidencePreconditionGate {

    /** 拒绝码（安全错误码）：事件与失败原因只暴露这些码，不暴露内部细节。 */
    public enum RejectionCode {
        EVIDENCE_NOT_REQUIRED,
        EVIDENCE_ALREADY_ACQUIRED,
        ORDER_PRECONDITION_MISSING,
        SHIPMENT_PRECONDITION_MISSING,
        CARRIER_CASE_PRECONDITION_MISSING,
        DELIVERY_PRECONDITION_MISSING,
        DAMAGE_PHOTO_PRECONDITION_MISSING,
        PRODUCT_PRECONDITION_MISSING,
        POLICY_PRECONDITION_MISSING,
        REQUIRED_EVIDENCE_INCOMPLETE
    }

    /** 前置依赖缺失时按前置证据类型映射拒绝码（每种证据一个唯一码）。 */
    private static final Map<EvidenceType, RejectionCode> PRECONDITION_CODES = Map.of(
            EvidenceType.ORDER, RejectionCode.ORDER_PRECONDITION_MISSING,
            EvidenceType.SHIPMENT, RejectionCode.SHIPMENT_PRECONDITION_MISSING,
            EvidenceType.CARRIER_CASE, RejectionCode.CARRIER_CASE_PRECONDITION_MISSING,
            EvidenceType.DELIVERY, RejectionCode.DELIVERY_PRECONDITION_MISSING,
            EvidenceType.DAMAGE_PHOTO, RejectionCode.DAMAGE_PHOTO_PRECONDITION_MISSING,
            EvidenceType.PRODUCT, RejectionCode.PRODUCT_PRECONDITION_MISSING,
            EvidenceType.POLICY, RejectionCode.POLICY_PRECONDITION_MISSING
    );

    /** 校验结果：passed=true 表示规划可推进；否则携带唯一拒绝码。 */
    public record ValidationResult(boolean passed, RejectionCode rejectionCode) {

        public static ValidationResult valid() {
            return new ValidationResult(true, null);
        }

        public static ValidationResult invalid(RejectionCode rejectionCode) {
            return new ValidationResult(false, rejectionCode);
        }
    }

    public ValidationResult validate(
            EvidenceType nextEvidence,
            List<String> requiredEvidence,
            Map<String, Boolean> presence) {
        if (nextEvidence == null || requiredEvidence == null || presence == null) {
            return ValidationResult.invalid(RejectionCode.REQUIRED_EVIDENCE_INCOMPLETE);
        }
        if (nextEvidence == EvidenceType.READY_FOR_DECISION) {
            for (String required : requiredEvidence) {
                if (!Boolean.TRUE.equals(presence.get(required))) {
                    return ValidationResult.invalid(RejectionCode.REQUIRED_EVIDENCE_INCOMPLETE);
                }
            }
            return ValidationResult.valid();
        }
        if (!requiredEvidence.contains(nextEvidence.name())) {
            return ValidationResult.invalid(RejectionCode.EVIDENCE_NOT_REQUIRED);
        }
        if (Boolean.TRUE.equals(presence.get(nextEvidence.name()))) {
            return ValidationResult.invalid(RejectionCode.EVIDENCE_ALREADY_ACQUIRED);
        }
        EvidenceType precondition = immediatePredecessor(requiredEvidence, nextEvidence);
        if (precondition != null && !Boolean.TRUE.equals(presence.get(precondition.name()))) {
            return ValidationResult.invalid(PRECONDITION_CODES.getOrDefault(
                    precondition, RejectionCode.REQUIRED_EVIDENCE_INCOMPLETE));
        }
        return ValidationResult.valid();
    }

    /**
     * 精确图前置依赖：requiredEvidence 中紧邻 nextEvidence 之前的证据
     * （顺序即服务端重建的证据图顺序，POLICY 的紧邻前序随图变化：SHIPMENT_DELAY =
     * SHIPMENT、LOST_IN_TRANSIT = CARRIER_CASE、DAMAGED_ITEM = PRODUCT）。
     * 首元素或未知证据名 → null（无前置依赖）。
     */
    private static EvidenceType immediatePredecessor(List<String> requiredEvidence, EvidenceType nextEvidence) {
        int index = requiredEvidence.indexOf(nextEvidence.name());
        if (index <= 0) {
            return null;
        }
        String predecessor = requiredEvidence.get(index - 1);
        if (predecessor == null || predecessor.isBlank()) {
            return null;
        }
        try {
            return EvidenceType.valueOf(predecessor);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }
}
