package com.ecommerce.service;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.EvaluationCheck;
import com.ecommerce.model.EvaluationReport;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationResponse;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class RecommendationEvaluator {

    private static final double LATENCY_BUDGET_MS = 3000.0;
    private static final Set<String> FORBIDDEN_COPY_WORDS = Set.of(
            "最好", "第一", "国家级", "全球首", "绝对", "100%", "永久", "万能",
            "best ever", "number one", "guaranteed", "forever", "miracle"
    );

    public EvaluationReport evaluate(RecommendationRequest request, RecommendationResponse response) {
        List<EvaluationCheck> checks = List.of(
                productCountCheck(request, response),
                crossBorderEligibilityCheck(request, response),
                platformCheck(request, response),
                currencyCheck(request, response),
                inventoryCheck(response),
                fulfillmentDataCheck(response),
                copyCoverageCheck(response),
                copyLocaleCheck(request, response),
                copyComplianceCheck(response),
                agentSuccessCheck(response),
                latencyCheck(response)
        );

        double totalWeight = checks.stream().mapToDouble(EvaluationCheck::getWeight).sum();
        double passedWeight = checks.stream().filter(EvaluationCheck::isPassed).mapToDouble(EvaluationCheck::getWeight).sum();
        long passedCount = checks.stream().filter(EvaluationCheck::isPassed).count();
        double score = totalWeight == 0 ? 0.0 : passedWeight / totalWeight;

        return EvaluationReport.builder()
                .passed(checks.stream().allMatch(EvaluationCheck::isPassed))
                .score(Math.round(score * 1000.0) / 1000.0)
                .summary(passedCount + "/" + checks.size() + " checks passed")
                .checks(checks)
                .build();
    }

    private EvaluationCheck productCountCheck(RecommendationRequest request, RecommendationResponse response) {
        int count = response.getProducts() == null ? 0 : response.getProducts().size();
        boolean passed = count > 0 && count <= request.getNumItems();
        return EvaluationCheck.builder().name("product_count").passed(passed)
                .detail("returned " + count + " products, requested at most " + request.getNumItems()).weight(1.2).build();
    }

    private EvaluationCheck crossBorderEligibilityCheck(RecommendationRequest request, RecommendationResponse response) {
        List<String> violations = response.getProducts() == null ? List.of() : response.getProducts().stream()
                .filter(product -> !product.isCrossBorderEligible() || !supports(product, request))
                .map(Product::getProductId)
                .collect(Collectors.toList());
        return EvaluationCheck.builder().name("cross_border_region_eligibility").passed(violations.isEmpty())
                .detail(violations.isEmpty() ? "all products support " + request.countryOrDefault() + "/" + request.regionOrDefault() : "blocked products leaked: " + violations)
                .weight(1.6).build();
    }

    private EvaluationCheck platformCheck(RecommendationRequest request, RecommendationResponse response) {
        List<String> violations = response.getProducts() == null ? List.of() : response.getProducts().stream()
                .filter(product -> !request.platformOrDefault().equalsIgnoreCase(product.getPlatform()))
                .map(product -> product.getProductId() + ":" + product.getPlatform())
                .collect(Collectors.toList());
        return EvaluationCheck.builder().name("platform_match").passed(violations.isEmpty())
                .detail(violations.isEmpty() ? "all products come from " + request.platformOrDefault() : "platform mismatch: " + violations)
                .weight(1.2).build();
    }
    private EvaluationCheck currencyCheck(RecommendationRequest request, RecommendationResponse response) {
        List<String> violations = response.getProducts() == null ? List.of() : response.getProducts().stream()
                .filter(product -> !request.currencyOrDefault().equalsIgnoreCase(product.getCurrency()))
                .map(product -> product.getProductId() + ":" + product.getCurrency())
                .collect(Collectors.toList());
        return EvaluationCheck.builder().name("currency_match").passed(violations.isEmpty())
                .detail(violations.isEmpty() ? "all products use " + request.currencyOrDefault() : "currency mismatch: " + violations)
                .weight(1.3).build();
    }

    private EvaluationCheck inventoryCheck(RecommendationResponse response) {
        List<String> outOfStock = response.getProducts() == null ? List.of() : response.getProducts().stream()
                .filter(product -> product.getStock() <= 0)
                .map(Product::getProductId)
                .collect(Collectors.toList());
        return EvaluationCheck.builder().name("inventory_filter").passed(outOfStock.isEmpty())
                .detail(outOfStock.isEmpty() ? "all returned products have stock" : "out of stock: " + outOfStock).weight(1.2).build();
    }

    private EvaluationCheck fulfillmentDataCheck(RecommendationResponse response) {
        Map<String, AgentResult> results = response.getAgentResults() == null ? Map.of() : response.getAgentResults();
        AgentResult inventory = results.getOrDefault("fulfillment_inventory", results.get("inventory"));
        boolean passed = inventory != null && inventory.getData() != null
                && inventory.getData().containsKey("delivery_estimates")
                && inventory.getData().containsKey("blocked_products")
                && inventory.getData().containsKey("fulfillment_warnings");
        return EvaluationCheck.builder().name("fulfillment_outputs").passed(passed)
                .detail(passed ? "inventory agent returned delivery estimates, warnings, and blocked products" : "missing fulfillment output fields")
                .weight(1.2).build();
    }

    private EvaluationCheck copyCoverageCheck(RecommendationResponse response) {
        Set<String> productIds = response.getProducts() == null ? Set.of() : response.getProducts().stream().map(Product::getProductId).collect(Collectors.toSet());
        Set<String> copyIds = response.getMarketingCopies() == null ? Set.of() : response.getMarketingCopies().stream().map(item -> item.getOrDefault("product_id", "")).collect(Collectors.toSet());
        Set<String> missing = new HashSet<>(productIds);
        missing.removeAll(copyIds);
        Set<String> extra = new HashSet<>(copyIds);
        extra.removeAll(productIds);
        boolean passed = missing.isEmpty() && extra.isEmpty();
        return EvaluationCheck.builder().name("copy_coverage").passed(passed)
                .detail(passed ? "every returned product has one copy" : "missing=" + missing + ", extra=" + extra).build();
    }

    private EvaluationCheck copyLocaleCheck(RecommendationRequest request, RecommendationResponse response) {
        List<String> violations = response.getMarketingCopies() == null ? List.of() : response.getMarketingCopies().stream()
                .filter(item -> !request.localeOrDefault().equalsIgnoreCase(item.getOrDefault("locale", "")))
                .map(item -> item.getOrDefault("product_id", "unknown") + ":" + item.getOrDefault("locale", "missing"))
                .collect(Collectors.toList());
        return EvaluationCheck.builder().name("copy_locale").passed(violations.isEmpty())
                .detail(violations.isEmpty() ? "all copies use " + request.localeOrDefault() : "locale mismatch: " + violations)
                .weight(1.1).build();
    }

    private EvaluationCheck copyComplianceCheck(RecommendationResponse response) {
        List<String> violations = response.getMarketingCopies() == null ? List.of() : response.getMarketingCopies().stream()
                .flatMap(item -> FORBIDDEN_COPY_WORDS.stream()
                        .filter(word -> item.getOrDefault("copy", "").toLowerCase().contains(word.toLowerCase()))
                        .map(word -> item.getOrDefault("product_id", "-") + ": " + word))
                .collect(Collectors.toList());
        return EvaluationCheck.builder().name("copy_compliance").passed(violations.isEmpty())
                .detail(violations.isEmpty() ? "no forbidden advertising words" : String.join("; ", violations)).weight(1.2).build();
    }

    private EvaluationCheck agentSuccessCheck(RecommendationResponse response) {
        Map<String, AgentResult> results = response.getAgentResults() == null ? Map.of() : response.getAgentResults();
        List<String> failedAgents = results.entrySet().stream().filter(entry -> !entry.getValue().isSuccess()).map(Map.Entry::getKey).collect(Collectors.toList());
        return EvaluationCheck.builder().name("agent_success").passed(failedAgents.isEmpty())
                .detail(failedAgents.isEmpty() ? "all agents succeeded" : "failed agents: " + failedAgents).weight(1.5).build();
    }

    private EvaluationCheck latencyCheck(RecommendationResponse response) {
        double latency = response.getTotalLatencyMs();
        boolean passed = latency <= LATENCY_BUDGET_MS;
        return EvaluationCheck.builder().name("latency_budget").passed(passed)
                .detail(String.format("%.1f ms <= %.1f ms", latency, LATENCY_BUDGET_MS)).build();
    }

    private boolean supports(Product product, RecommendationRequest request) {
        List<String> supported = product.getSupportedRegions() == null ? List.of() : product.getSupportedRegions();
        return supported.stream().anyMatch(value -> value.equalsIgnoreCase(request.countryOrDefault()) || value.equalsIgnoreCase(request.regionOrDefault()));
    }
}

