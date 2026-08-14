package com.ecommerce.aftersales.connector;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.data.DemoFulfillmentDataFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MockShopifyAfterSalesConnector {

    /** 可信订单号格式：O-<国家>-<数字>，国家为两位大写代码。 */
    private static final Pattern TRUSTED_ORDER_ID_PATTERN = Pattern.compile("^O-([A-Z]{2})-(\\d+)$");

    /** 目的地城市按订单国家确定：轨迹最后节点必须落在订单国家，与订单快照保持一致。 */
    private static final Map<String, String> DESTINATION_CITIES = Map.of(
            "SG", "Singapore SG",
            "MY", "Kuala Lumpur MY",
            "TH", "Bangkok TH",
            "ID", "Jakarta ID",
            "VN", "Hanoi VN"
    );

    /**
     * 运单号由可信订单号确定性派生：SF-<国家>-<数字>，不再硬编码越南运单。
     * 先整体校验订单号格式再提取数字段，避免按索引截取残留国家段导致重复（如 SF-ID-ID-4001）；
     * 格式不符或订单号国家段与快照国家不一致时失败关闭，不静默拼接。
     */
    static String deriveTrackingNumber(String orderId, String country) {
        Matcher matcher = TRUSTED_ORDER_ID_PATTERN.matcher(orderId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("UNTRUSTED_ORDER_ID_FORMAT");
        }
        if (!matcher.group(1).equals(country)) {
            throw new IllegalArgumentException("ORDER_ID_COUNTRY_MISMATCH");
        }
        return "SF-" + country + "-" + matcher.group(2);
    }

    public AfterSalesTypes.OrderSnapshot getOrder(String orderId) {
        Map<String, Object> order = DemoFulfillmentDataFactory.createOrders().stream()
                .filter(candidate -> orderId.equals(candidate.get("order_id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ORDER_NOT_FOUND"));

        String country = String.valueOf(order.get("country"));
        return new AfterSalesTypes.OrderSnapshot(
                orderId,
                String.valueOf(order.get("user_id")),
                String.valueOf(order.get("platform")),
                country,
                String.valueOf(order.get("currency")),
                String.valueOf(order.get("warehouse_region")),
                BigDecimal.valueOf(((Number) order.get("order_value")).doubleValue()),
                "paid".equals(order.get("payment_status")),
                String.valueOf(order.get("fulfillment_status")),
                ((Number) order.get("promised_delivery_days")).intValue(),
                deriveTrackingNumber(orderId, country)
        );
    }

    public AfterSalesTypes.ShipmentSnapshot getShipment(AfterSalesTypes.OrderSnapshot order) {
        // 演示行为保留：物流已 10 天未更新；轨迹时间点相对 lastUpdated 固定偏移，目的地随国家变化。
        Instant lastUpdated = Instant.now().minus(10, ChronoUnit.DAYS);
        String destination = DESTINATION_CITIES.getOrDefault(order.country(), "SEA regional hub");
        List<AfterSalesTypes.ShipmentCheckpoint> timeline = List.of(
                new AfterSalesTypes.ShipmentCheckpoint(lastUpdated.minus(2, ChronoUnit.DAYS), "PICKED_UP", "Shenzhen CN", "Parcel collected by carrier"),
                new AfterSalesTypes.ShipmentCheckpoint(lastUpdated.minus(1, ChronoUnit.DAYS), "EXPORT_CLEARANCE", "Shenzhen CN", "Export clearance completed"),
                new AfterSalesTypes.ShipmentCheckpoint(lastUpdated, "CUSTOMS_DOCUMENT_REQUIRED", destination, "Additional customs document required")
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
