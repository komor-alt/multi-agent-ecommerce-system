package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Recomputed from trusted state each turn. Order is only the fallback preference. */
public final class AvailableEvidenceResolver {
    private final EvidencePreconditionGate gate = new EvidencePreconditionGate();

    public List<EvidenceType> resolve(List<String> required, Map<String, Boolean> presence) {
        if (required == null || presence == null) { return List.of(); }
        List<EvidenceType> available = new ArrayList<>();
        for (String name : required) {
            if (name == null) { return List.of(); }
            final EvidenceType type;
            try { type = EvidenceType.valueOf(name); }
            catch (IllegalArgumentException error) { return List.of(); }
            if (type == EvidenceType.READY_FOR_DECISION) { return List.of(); }
            if (!available.contains(type) && gate.validate(type, required, presence).passed()) {
                available.add(type);
            }
        }
        if (gate.validate(EvidenceType.READY_FOR_DECISION, required, presence).passed()) {
            return List.of(EvidenceType.READY_FOR_DECISION);
        }
        return List.copyOf(available);
    }
}
