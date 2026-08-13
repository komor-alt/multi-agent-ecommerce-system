package com.ecommerce.aftersales.connector;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.data.DemoFulfillmentDataFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

@Component
public class MockShopifyAfterSalesConnector {

    public AfterSalesTypes.OrderSnapshot getOrder(String orderId) {
        Map<String, Object> order = DemoFulfillmentDataFactory.createOrders().stream()
                .filter(candidate -> orderId.equals(candidate.get("order_id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ORDER_NOT_FOUND"));

        return new AfterSalesTypes.OrderSnapshot(
                orderId,
                String.valueOf(order.get("user_id")),
                String.valueOf(order.get("platform")),
                String.valueOf(order.get("country")),
                String.valueOf(order.get("currency")),
                String.valueOf(order.get("warehouse_region")),
                BigDecimal.valueOf(((Number) order.get("order_value")).doubleValue()),
                "paid".equals(order.get("payment_status")),
                String.valueOf(order.get("fulfillment_status")),
                ((Number) order.get("promised_delivery_days")).intValue(),
                "SF-VN-20260801-5002"
        );
    }

    public AfterSalesTypes.ShipmentSnapshot getShipment(AfterSalesTypes.OrderSnapshot order) {
        Instant lastUpdated = Instant.now().minus(10, ChronoUnit.DAYS);
        List<AfterSalesTypes.ShipmentCheckpoint> timeline = List.of(
                new AfterSalesTypes.ShipmentCheckpoint(lastUpdated.minus(2, ChronoUnit.DAYS), "PICKED_UP", "Shenzhen CN", "Parcel collected by carrier"),
                new AfterSalesTypes.ShipmentCheckpoint(lastUpdated.minus(1, ChronoUnit.DAYS), "EXPORT_CLEARANCE", "Shenzhen CN", "Export clearance completed"),
                new AfterSalesTypes.ShipmentCheckpoint(lastUpdated, "CUSTOMS_DOCUMENT_REQUIRED", "Hanoi VN", "Additional customs document required")
        );
        return new AfterSalesTypes.ShipmentSnapshot(
                order.trackingNumber(),
                order.fulfillmentStatus(),
                lastUpdated,
                10,
                Math.max(0, 10 - order.promisedDeliveryDays()),
                timeline
        );
    }

    public AfterSalesTypes.ExecutionResult issueDelayCoupon(
            AfterSalesTypes.OrderSnapshot order,
            BigDecimal amount,
            String idempotencyKey) {
        String reference = "CPN-" + idempotencyKey.substring(0, 12).toUpperCase();
        return new AfterSalesTypes.ExecutionResult(reference, "ISSUED", Instant.now());
    }
}
