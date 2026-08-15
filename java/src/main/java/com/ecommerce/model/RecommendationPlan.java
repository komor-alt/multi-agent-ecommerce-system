package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * The actual final answer of the recommendation agent loop: scene, market,
 * userSegment, products, strategy, fulfillment, marketingCopies, evidenceIds,
 * and execution metrics, with explicit fit / market / fulfillment reasons.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationPlan {
    private String scene;
    private MarketContext market;
    private String userSegment;
    private List<Product> products;
    private Map<String, Object> strategy;
    private FulfillmentContext fulfillment;
    @Builder.Default
    private List<Map<String, String>> marketingCopies = List.of();
    private List<String> evidenceIds;
    /** Why these products fit this user (profile/rerank). */
    private List<String> fitReasons;
    /** Why these products are (or are not) eligible for this market. */
    private List<String> marketReasons;
    /** Warehouse/fulfillment/delivery explanation. */
    private List<String> fulfillmentReasons;
    private PlanMetrics metrics;
}
