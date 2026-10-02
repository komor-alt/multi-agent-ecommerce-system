package com.ecommerce.service;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.Product;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Versioned, agent-owned changes produced from one candidate snapshot.
 * Exactly one specialist-owned payload is present in each patch.
 */
public record CandidateStatePatch(
        long baseCandidateVersion,
        AgentId producer,
        List<Product> rankedProducts,
        Set<String> availableIds,
        Map<String, Object> fulfillment) {

    public CandidateStatePatch {
        rankedProducts = rankedProducts == null ? null : List.copyOf(rankedProducts);
        availableIds = availableIds == null ? null : Set.copyOf(availableIds);
        fulfillment = fulfillment == null ? Map.of() : Map.copyOf(fulfillment);
    }

    public static CandidateStatePatch rerank(long version, List<Product> rankedProducts) {
        return new CandidateStatePatch(version, AgentId.PRODUCT, rankedProducts, null, Map.of());
    }

    public static CandidateStatePatch inventory(
            long version,
            Set<String> availableIds,
            Map<String, Object> fulfillment) {
        return new CandidateStatePatch(version, AgentId.INVENTORY, null, availableIds, fulfillment);
    }
}
