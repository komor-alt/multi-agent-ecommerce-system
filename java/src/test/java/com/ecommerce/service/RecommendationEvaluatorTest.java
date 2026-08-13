package com.ecommerce.service;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.EvaluationReport;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationEvaluatorTest {

    private final RecommendationEvaluator evaluator = new RecommendationEvaluator();

    @Test
    void passesValidRecommendationResponse() {
        RecommendationRequest request = RecommendationRequest.builder()
                .userId("user_001")
                .numItems(2)
                .build();
        RecommendationResponse response = RecommendationResponse.builder()
                .requestId("req_001")
                .userId("user_001")
                .products(List.of(
                        Product.builder().productId("P001").name("Phone").category("phone").price(1000).currency("SGD").stock(10).supportedRegions(List.of("SEA", "SG")).platform("shopify").crossBorderEligible(true).build(),
                        Product.builder().productId("P002").name("Pods").category("earbuds").price(500).currency("SGD").stock(20).supportedRegions(List.of("SEA", "SG")).platform("shopify").crossBorderEligible(true).build()
                ))
                .marketingCopies(List.of(
                        Map.of("product_id", "P001", "copy", "Everyday phone pick for Singapore shoppers.", "locale", "en-SG"),
                        Map.of("product_id", "P002", "copy", "Easy earbuds for commute and workouts.", "locale", "en-SG")
                ))
                .agentResults(Map.of(
                        "user_profile", AgentResult.builder().agentName("user_profile").success(true).build(),
                        "rerank", AgentResult.builder().agentName("product_rec").success(true).build(),
                        "fulfillment_inventory", AgentResult.builder().agentName("inventory").success(true).data(Map.of("delivery_estimates", Map.of("P001", Map.of("delivery_days", 2)), "blocked_products", List.of(), "fulfillment_warnings", List.of())).build(),
                        "localized_marketing_copy", AgentResult.builder().agentName("marketing_copy").success(true).build()
                ))
                .totalLatencyMs(1200)
                .build();

        EvaluationReport report = evaluator.evaluate(request, response);

        assertThat(report.isPassed()).isTrue();
        assertThat(report.getScore()).isEqualTo(1.0);
    }

    @Test
    void flagsRiskyRecommendationResponse() {
        RecommendationRequest request = RecommendationRequest.builder()
                .userId("user_001")
                .numItems(2)
                .build();
        RecommendationResponse response = RecommendationResponse.builder()
                .requestId("req_001")
                .userId("user_001")
                .products(List.of(
                        Product.builder().productId("P001").name("Phone").category("phone").price(1000).currency("SGD").stock(0).supportedRegions(List.of("SEA", "SG")).platform("shopee").crossBorderEligible(true).build()
                ))
                .marketingCopies(List.of(
                        Map.of("product_id", "P999", "copy", "这是最好用的产品，100%满意。", "locale", "zh-CN")
                ))
                .agentResults(Map.of(
                        "rerank", AgentResult.builder().agentName("product_rec").success(false).error("llm failed").build()
                ))
                .totalLatencyMs(5000)
                .build();

        EvaluationReport report = evaluator.evaluate(request, response);

        assertThat(report.isPassed()).isFalse();
        assertThat(report.getChecks())
                .filteredOn(check -> !check.isPassed())
                .extracting("name")
                .contains("platform_match", "inventory_filter", "copy_coverage", "copy_compliance", "agent_success", "latency_budget");
    }
}


