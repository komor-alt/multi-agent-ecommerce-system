package com.ecommerce.agent;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Inventory and fulfillment Agent: stock, country eligibility, warehouse, delivery SLA.
 */
@Component
public class InventoryAgent extends BaseAgent {

    private static final int SAFETY_STOCK_THRESHOLD = 50;
    private static final int LOW_STOCK_THRESHOLD = 100;
    private static final int HOT_ITEM_PURCHASE_LIMIT = 2;
    private static final int CROSS_BORDER_SLA_DAYS = 7;

    public InventoryAgent() {
        super("inventory", 5.0, 2);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected AgentResult execute(Map<String, Object> params) {
        RecommendationRequest request = requestFrom(params);
        List<Product> products = (List<Product>) params.getOrDefault("products", List.of());

        List<String> available = new ArrayList<>();
        List<Map<String, Object>> alerts = new ArrayList<>();
        List<Map<String, Object>> fulfillmentWarnings = new ArrayList<>();
        List<Map<String, Object>> blockedProducts = new ArrayList<>();
        Map<String, Integer> purchaseLimits = new HashMap<>();
        Map<String, Object> deliveryEstimates = new HashMap<>();

        for (Product product : products) {
            List<String> blockReasons = blockReasons(product, request);
            if (!blockReasons.isEmpty()) {
                blockedProducts.add(Map.of(
                        "product_id", product.getProductId(),
                        "name", product.getName(),
                        "reasons", blockReasons
                ));
                continue;
            }

            int stock = checkStock(product);
            available.add(product.getProductId());
            deliveryEstimates.put(product.getProductId(), Map.of(
                    "warehouse_region", product.getWarehouseRegion(),
                    "delivery_days", product.getDeliveryDays(),
                    "country", request.countryOrDefault(),
                    "local_warehouse", request.countryOrDefault().equalsIgnoreCase(product.getWarehouseRegion())
            ));

            if (product.getDeliveryDays() > CROSS_BORDER_SLA_DAYS) {
                fulfillmentWarnings.add(Map.of(
                        "product_id", product.getProductId(),
                        "level", "warning",
                        "message", "delivery exceeds cross-border SLA",
                        "delivery_days", product.getDeliveryDays()
                ));
            }

            if (stock <= SAFETY_STOCK_THRESHOLD) {
                alerts.add(Map.of(
                        "product_id", product.getProductId(),
                        "name", product.getName(),
                        "current_stock", stock,
                        "level", "critical",
                        "action", "urgent_restock"
                ));
            } else if (stock <= LOW_STOCK_THRESHOLD) {
                alerts.add(Map.of(
                        "product_id", product.getProductId(),
                        "name", product.getName(),
                        "current_stock", stock,
                        "level", "warning",
                        "action", "plan_restock"
                ));
            }

            Integer limit = calcPurchaseLimit(product, stock);
            if (limit != null) {
                purchaseLimits.put(product.getProductId(), limit);
            }
        }

        Map<String, Object> data = new HashMap<>();
        data.put("available_products", available);
        data.put("low_stock_alerts", alerts);
        data.put("purchase_limits", purchaseLimits);
        data.put("fulfillment_warnings", fulfillmentWarnings);
        data.put("delivery_estimates", deliveryEstimates);
        data.put("blocked_products", blockedProducts);
        data.put("cross_border_context", Map.of(
                "platform", request.platformOrDefault(),
                "region", request.regionOrDefault(),
                "country", request.countryOrDefault(),
                "currency", request.currencyOrDefault()
        ));
        data.put("total_checked", products.size());
        data.put("available_count", available.size());

        return AgentResult.builder()
                .agentName(name)
                .success(true)
                .data(data)
                .confidence(0.95)
                .build();
    }

    private List<String> blockReasons(Product product, RecommendationRequest request) {
        List<String> reasons = new ArrayList<>();
        if (product.getStock() <= 0) reasons.add("out_of_stock");
        if (!product.isCrossBorderEligible()) reasons.add("cross_border_not_eligible");
        if (!request.platformOrDefault().equalsIgnoreCase(product.getPlatform())) reasons.add("platform_mismatch");
        if (!request.currencyOrDefault().equalsIgnoreCase(product.getCurrency())) reasons.add("currency_mismatch");
        List<String> supported = product.getSupportedRegions() == null ? List.of() : product.getSupportedRegions();
        boolean supports = supported.stream().anyMatch(value -> value.equalsIgnoreCase(request.countryOrDefault()) || value.equalsIgnoreCase(request.regionOrDefault()));
        if (!supports) reasons.add("country_or_region_not_supported");
        return reasons;
    }

    private int checkStock(Product product) {
        return product.getStock();
    }

    private Integer calcPurchaseLimit(Product product, int stock) {
        boolean isHot = product.getTags() != null &&
                (product.getTags().contains("new") || product.getTags().contains("flagship") || product.getTags().contains("premium"));
        if (stock <= SAFETY_STOCK_THRESHOLD) return 1;
        if (stock <= LOW_STOCK_THRESHOLD && isHot) return HOT_ITEM_PURCHASE_LIMIT;
        if (isHot && stock <= 300) return 3;
        return null;
    }

    private RecommendationRequest requestFrom(Map<String, Object> params) {
        Object request = params.get("request");
        if (request instanceof RecommendationRequest recommendationRequest) {
            return recommendationRequest;
        }
        return RecommendationRequest.builder().userId(String.valueOf(params.getOrDefault("userId", "anonymous"))).build();
    }
}
