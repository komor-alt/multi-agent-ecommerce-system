package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;

import java.util.List;
import java.util.Map;

/**
 * 证据业务前置校验 Gate（纯 Java 组件，无框架依赖）：Agent Loop 对规划结果
 * （LLM 规划与规则兜底一视同仁）执行的不可变校验，决定规划能否让工单推进。
 *
 * 校验顺序（任一失败即拒绝）：
 * 1. nextEvidence 必须属于路线重建后的 requiredEvidence（非必需证据不可取证）；
 * 2. 目标证据必须尚未获取（已存在证据不可重复取证）；
 * 3. SHIPMENT 依赖 ORDER：ORDER 缺失时不可取 SHIPMENT；
 * 4. POLICY 依赖 ORDER+SHIPMENT：两者任一缺失时不可取 POLICY；
 * 5. READY_FOR_DECISION 要求全部 requiredEvidence 已存在，否则不可宣称证据齐备。
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
        REQUIRED_EVIDENCE_INCOMPLETE
    }

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
        if (nextEvidence == EvidenceType.SHIPMENT
                && !Boolean.TRUE.equals(presence.get(EvidenceType.ORDER.name()))) {
            return ValidationResult.invalid(RejectionCode.ORDER_PRECONDITION_MISSING);
        }
        if (nextEvidence == EvidenceType.POLICY) {
            if (!Boolean.TRUE.equals(presence.get(EvidenceType.ORDER.name()))) {
                return ValidationResult.invalid(RejectionCode.ORDER_PRECONDITION_MISSING);
            }
            if (!Boolean.TRUE.equals(presence.get(EvidenceType.SHIPMENT.name()))) {
                return ValidationResult.invalid(RejectionCode.SHIPMENT_PRECONDITION_MISSING);
            }
        }
        return ValidationResult.valid();
    }
}
