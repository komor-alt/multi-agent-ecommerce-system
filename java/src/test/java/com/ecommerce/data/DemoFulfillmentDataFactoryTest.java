package com.ecommerce.data;

import com.ecommerce.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DemoFulfillmentDataFactoryTest {

    @Test
    void createsOrdersAndFulfillmentSummaryForCrossBorderInterviewData() {
        List<Product> catalog = DemoCatalogDataFactory.createCatalog();
        List<Map<String, Object>> orders = DemoFulfillmentDataFactory.createOrders();
        Map<String, Object> summary = DemoFulfillmentDataFactory.summarize(catalog, orders);

        assertThat(orders).hasSizeGreaterThanOrEqualTo(8);
        assertThat(orders).anyMatch(order -> !order.get("country").equals(order.get("warehouse_region")));
        assertThat(orders).anyMatch(order -> "blocked_restricted_item".equals(order.get("fulfillment_status")));
        assertThat(summary).containsEntry("dataset_type", "deterministic_demo_fulfillment_seed");
        assertThat(summary).containsEntry("production_data", false);
        assertThat((Long) summary.get("cross_border_orders")).isGreaterThan(0);
        assertThat((List<?>) summary.get("inventory_risk_products")).isNotEmpty();
    }
}
