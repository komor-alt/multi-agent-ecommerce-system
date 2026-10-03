package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;
import java.util.List;
import java.util.Map;

/**
 * Fail-closed evidence gate shared by model and rules planning.
 * Required evidence is a set of obligations, not an execution order:
 * ORDER precedes all reads; SHIPMENT precedes CARRIER_CASE;
 * DELIVERY precedes DAMAGE_PHOTO, which precedes PRODUCT.
 * POLICY needs only the authoritative order and can run before logistics.
 */
public final class EvidencePreconditionGate {
    public enum RejectionCode {
        EVIDENCE_NOT_REQUIRED, EVIDENCE_ALREADY_ACQUIRED,
        ORDER_PRECONDITION_MISSING, SHIPMENT_PRECONDITION_MISSING,
        CARRIER_CASE_PRECONDITION_MISSING, DELIVERY_PRECONDITION_MISSING,
        DAMAGE_PHOTO_PRECONDITION_MISSING, PRODUCT_PRECONDITION_MISSING,
        POLICY_PRECONDITION_MISSING, REQUIRED_EVIDENCE_INCOMPLETE
    }

    public record ValidationResult(boolean passed, RejectionCode rejectionCode) {
        public static ValidationResult valid() { return new ValidationResult(true, null); }
        public static ValidationResult invalid(RejectionCode code) { return new ValidationResult(false, code); }
    }

    public ValidationResult validate(EvidenceType next, List<String> required, Map<String, Boolean> presence) {
        if (next == null || required == null || presence == null || !validEvidenceNames(required)) {
            return ValidationResult.invalid(RejectionCode.REQUIRED_EVIDENCE_INCOMPLETE);
        }
        if (next == EvidenceType.READY_FOR_DECISION) {
            return required.stream().allMatch(name -> Boolean.TRUE.equals(presence.get(name)))
                    ? ValidationResult.valid()
                    : ValidationResult.invalid(RejectionCode.REQUIRED_EVIDENCE_INCOMPLETE);
        }
        if (!required.contains(next.name())) {
            return ValidationResult.invalid(RejectionCode.EVIDENCE_NOT_REQUIRED);
        }
        if (Boolean.TRUE.equals(presence.get(next.name()))) {
            return ValidationResult.invalid(RejectionCode.EVIDENCE_ALREADY_ACQUIRED);
        }
        if (next != EvidenceType.ORDER && !Boolean.TRUE.equals(presence.get("ORDER"))) {
            return ValidationResult.invalid(RejectionCode.ORDER_PRECONDITION_MISSING);
        }
        EvidenceType dependency = switch (next) {
            case CARRIER_CASE -> EvidenceType.SHIPMENT;
            case DAMAGE_PHOTO -> EvidenceType.DELIVERY;
            case PRODUCT -> EvidenceType.DAMAGE_PHOTO;
            default -> null;
        };
        if (dependency != null && !Boolean.TRUE.equals(presence.get(dependency.name()))) {
            return ValidationResult.invalid(RejectionCode.valueOf(dependency.name() + "_PRECONDITION_MISSING"));
        }
        return ValidationResult.valid();
    }

    private static boolean validEvidenceNames(List<String> required) {
        for (String name : required) {
            if (name == null) { return false; }
            try {
                if (EvidenceType.valueOf(name) == EvidenceType.READY_FOR_DECISION) { return false; }
            } catch (IllegalArgumentException error) { return false; }
        }
        return true;
    }
}
