package com.ecommerce.data;

import com.ecommerce.model.Product;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class DemoFulfillmentDataFactory {

    private DemoFulfillmentDataFactory() {
    }

    public static List<Map<String, Object>> createOrders() {
        List<Map<String, Object>> orders = new ArrayList<>();
        orders.add(order("O-SG-1001", "sea_sg_001", "shopify", "SG", "SGD", "SG", List.of("P001", "P002"), 588.0, "paid", "ready_to_ship", 3, "low"));
        orders.add(order("O-SG-1002", "sea_sg_002", "shopify", "SG", "SGD", "MY", List.of("P004"), 149.0, "paid", "cross_border_shipping", 6, "medium"));
        orders.add(order("O-MY-2001", "sea_my_001", "shopify", "MY", "MYR", "MY", List.of("P005", "P015"), 1833.0, "paid", "ready_to_ship", 4, "low"));
        orders.add(order("O-TH-3001", "sea_th_001", "shopify", "TH", "THB", "TH", List.of("P022"), 172.0, "paid", "low_stock_review", 5, "medium"));
        orders.add(order("O-ID-4001", "sea_id_001", "shopee", "ID", "IDR", "ID", List.of("P009", "P034"), 241.0, "paid", "cross_border_shipping", 8, "medium"));
        orders.add(order("O-VN-5001", "sea_vn_001", "shopify", "VN", "VND", "VN", List.of("P010"), 28.0, "paid", "delivered", 2, "low"));
        orders.add(order("O-VN-5002", "sea_vn_002", "shopify", "VN", "VND", "CN", List.of("P007"), 1899.0, "paid", "customs_document_required", 10, "high"));
        orders.add(order("O-SG-1003", "sea_sg_003", "shopify", "SG", "SGD", "CN", List.of("P008"), 59.0, "paid", "blocked_restricted_item", 10, "high"));
        return List.copyOf(orders);
    }

    public static Map<String, Object> summarize(List<Product> catalog, List<Map<String, Object>> orders) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("dataset_type", "deterministic_demo_fulfillment_seed");
        summary.put("production_data", false);
        summary.put("total_orders", orders.size());
        summary.put("orders_by_country", countBy(orders, order -> String.valueOf(order.get("country"))));
        summary.put("orders_by_platform", countBy(orders, order -> String.valueOf(order.get("platform"))));
        summary.put("orders_by_fulfillment_status", countBy(orders, order -> String.valueOf(order.get("fulfillment_status"))));
        summary.put("orders_by_risk_level", countBy(orders, order -> String.valueOf(order.get("risk_level"))));
        summary.put("cross_border_orders", orders.stream()
                .filter(order -> !String.valueOf(order.get("country")).equals(String.valueOf(order.get("warehouse_region"))))
                .count());
        summary.put("sla_watch_orders", orders.stream()
                .filter(order -> ((Number) order.get("promised_delivery_days")).intValue() >= 8)
                .map(order -> order.get("order_id"))
                .collect(Collectors.toList()));
        summary.put("inventory_risk_products", catalog.stream()
                .filter(product -> product.getStock() <= 50 || !product.isCrossBorderEligible())
                .sorted(Comparator.comparing(Product::getProductId))
                .limit(12)
                .map(product -> Map.of(
                        "product_id", product.getProductId(),
                        "stock", product.getStock(),
                        "cross_border_eligible", product.isCrossBorderEligible(),
                        "warehouse_region", product.getWarehouseRegion()
                ))
                .collect(Collectors.toList()));
        summary.put("note", "Demo order and fulfillment data is used for interview demos, regression checks, and capacity discussion. Replace it with OMS/WMS/logistics feeds in production.");
        return summary;
    }

    private static Map<String, Object> order(String orderId, String userId, String platform, String country,
                                             String currency, String warehouseRegion, List<String> productIds,
                                             double orderValue, String paymentStatus, String fulfillmentStatus,
                                             int promisedDeliveryDays, String riskLevel) {
        Map<String, Object> order = new LinkedHashMap<>();
        order.put("order_id", orderId);
        order.put("user_id", userId);
        order.put("platform", platform);
        order.put("region", "SEA");
        order.put("country", country);
        order.put("currency", currency);
        order.put("warehouse_region", warehouseRegion);
        order.put("product_ids", productIds);
        order.put("order_value", orderValue);
        order.put("payment_status", paymentStatus);
        order.put("fulfillment_status", fulfillmentStatus);
        order.put("promised_delivery_days", promisedDeliveryDays);
        order.put("risk_level", riskLevel);
        return order;
    }

    private static Map<String, Long> countBy(List<Map<String, Object>> orders, Function<Map<String, Object>, String> classifier) {
        return orders.stream()
                .collect(Collectors.groupingBy(classifier, LinkedHashMap::new, Collectors.counting()));
    }
}
