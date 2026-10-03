package com.ecommerce.aftersales.connector;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.data.DemoCatalogDataFactory;
import com.ecommerce.data.DemoFulfillmentDataFactory;
import com.ecommerce.model.Product;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class MockShopifyAfterSalesConnector implements AfterSalesConnector {
    // Process-local simulator, NOT a durable external payment ledger.
    private final java.util.concurrent.ConcurrentMap<String, CommittedExecution> executions =
            new java.util.concurrent.ConcurrentHashMap<>();
    private record CommittedExecution(ExecutionCommand command, AfterSalesTypes.ExecutionResult result) {}

    @Override
    public java.util.Optional<AfterSalesTypes.ExecutionResult> findExecution(ExecutionCommand command) {
        CommittedExecution entry = executions.get(command.idempotencyKey());
        if (entry == null) return java.util.Optional.empty();
        if (!entry.command().equals(command)) {
            throw new ConnectorException(ConnectorException.Category.TERMINAL, "IDEMPOTENCY_PAYLOAD_MISMATCH");
        }
        return java.util.Optional.of(entry.result());
    }

    @Override
    public AfterSalesTypes.ExecutionResult execute(AfterSalesTypes.OrderSnapshot order, ExecutionCommand command) {
        return executions.compute(command.idempotencyKey(), (key, existing) -> {
            if (existing != null) {
                if (!existing.command().equals(command)) {
                    throw new ConnectorException(ConnectorException.Category.TERMINAL, "IDEMPOTENCY_PAYLOAD_MISMATCH");
                }
                return existing;
            }
            if (order == null || !command.orderId().equals(order.orderId())
                    || !command.currency().equals(order.currency())
                    || order.fullyRefunded() || !order.paid()) {
                throw new ConnectorException(ConnectorException.Category.TERMINAL, "ORDER_NOT_EXECUTABLE");
            }
            String prefix = switch (command.actionType()) {
                case "DELAY_COMPENSATION_COUPON", "DAMAGE_COMPENSATION_COUPON" -> "CPN-";
                case "LOST_PARCEL_REFUND" -> "RFD-";
                default -> throw new ConnectorException(ConnectorException.Category.TERMINAL, "ACTION_NOT_SUPPORTED");
            };
            String reference = prefix + java.util.UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return new CommittedExecution(command, new AfterSalesTypes.ExecutionResult(
                    reference, "LOST_PARCEL_REFUND".equals(command.actionType()) ? "REFUNDED" : "ISSUED", Instant.now()));
        }).result();
    }

    public int committedEffectCount() { return executions.size(); }

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

    /** 物流未更新天数（演示种子）：仅 O-VN-5002 / O-VN-5003 有专属天数，其余订单保持既有 10 天。 */
    private static final int DEFAULT_INACTIVE_DAYS = 10;
    private static final Map<String, Integer> INACTIVE_DAYS_BY_ORDER = Map.of(
            "O-VN-5002", 10,
            "O-VN-5003", 2
    );

    /** 承运商调查结论（演示种子）：LOST_CONFIRMED = 承运商确认丢失（补偿可达），其余默认调查中。 */
    private static final String DEFAULT_CARRIER_OUTCOME = "UNDER_INVESTIGATION";
    private static final Map<String, String> CARRIER_OUTCOME_BY_ORDER = Map.of(
            "O-VN-5002", "LOST_CONFIRMED",
            "O-SG-1003", "LOST_CONFIRMED",
            "O-ID-4001", "UNDER_INVESTIGATION"
    );

    /** 破损照片核验结论（演示种子）：VERIFIED = 人工确认破损（补偿可达），
     *  REJECTED = 照片无法证明破损（失败关闭），其余默认待核验。 */
    private static final String DEFAULT_DAMAGE_PHOTO_STATUS = "PENDING_REVIEW";
    private static final Set<String> VERIFIED_DAMAGE_ORDERS = Set.of("O-SG-1001");
    private static final Set<String> REJECTED_DAMAGE_ORDERS = Set.of("O-MY-2001");

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

    /** 订单号数字段（O-CC-1234 → 1234），用于派生案件/照片/交付 ID；格式由 deriveTrackingNumber 保证。 */
    private static String orderNumber(String orderId) {
        Matcher matcher = TRUSTED_ORDER_ID_PATTERN.matcher(orderId);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("UNTRUSTED_ORDER_ID_FORMAT");
        }
        return matcher.group(2);
    }

    public AfterSalesTypes.OrderSnapshot getOrder(String orderId) {
        Map<String, Object> order = DemoFulfillmentDataFactory.createOrders().stream()
                .filter(candidate -> orderId.equals(candidate.get("order_id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ORDER_NOT_FOUND"));

        String country = String.valueOf(order.get("country"));
        String paymentStatus = String.valueOf(order.get("payment_status"));
        String refundStatus = String.valueOf(order.getOrDefault(
                "refund_status",
                "refunded".equalsIgnoreCase(paymentStatus) ? "REFUNDED" : "NONE"
        ));
        return new AfterSalesTypes.OrderSnapshot(
                orderId,
                String.valueOf(order.get("user_id")),
                String.valueOf(order.get("platform")),
                country,
                String.valueOf(order.get("currency")),
                String.valueOf(order.get("warehouse_region")),
                BigDecimal.valueOf(((Number) order.get("order_value")).doubleValue()),
                "paid".equalsIgnoreCase(paymentStatus),
                String.valueOf(order.get("fulfillment_status")),
                ((Number) order.get("promised_delivery_days")).intValue(),
                deriveTrackingNumber(orderId, country),
                refundStatus
        );
    }

    /**
     * 物流未更新天数按可信订单号确定性派生（见 INACTIVE_DAYS_BY_ORDER）：
     * O-VN-5002 = 10 天（补偿评估可达政策阈值）、O-VN-5003 = 2 天（未达阈值）、其余保持 10 天。
     * 未更新天数绝不来自客户消息（消息不可信）；轨迹时间点相对 lastUpdated 固定偏移、
     * 目的地随国家变化，最后节点 = lastUpdated（CUSTOMS_DOCUMENT_REQUIRED），与状态自洽。
     */
    public AfterSalesTypes.ShipmentSnapshot getShipment(AfterSalesTypes.OrderSnapshot order) {
        int inactiveDays = INACTIVE_DAYS_BY_ORDER.getOrDefault(order.orderId(), DEFAULT_INACTIVE_DAYS);
        Instant lastUpdated = Instant.now().minus(inactiveDays, ChronoUnit.DAYS);
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
                inactiveDays,
                Math.max(0, inactiveDays - order.promisedDeliveryDays()),
                timeline
        );
    }

    /**
     * 承运商理赔/调查案件快照（LOST_IN_TRANSIT 证据）：案件号、调查结论与时间点全部由
     * 可信订单号确定性派生（见 CARRIER_OUTCOME_BY_ORDER），绝不来自客户消息。
     * evidenceId 不可变：carrier-case:&lt;caseId&gt;:v1。
     */
    public AfterSalesTypes.CarrierCaseSnapshot getCarrierCase(AfterSalesTypes.OrderSnapshot order) {
        String caseId = "CC-" + order.country() + "-" + orderNumber(order.orderId());
        String outcome = CARRIER_OUTCOME_BY_ORDER.getOrDefault(order.orderId(), DEFAULT_CARRIER_OUTCOME);
        Instant openedAt = Instant.now().minus(INACTIVE_DAYS_BY_ORDER.getOrDefault(
                order.orderId(), DEFAULT_INACTIVE_DAYS), ChronoUnit.DAYS);
        String summary = "LOST_CONFIRMED".equals(outcome)
                ? "Carrier investigation closed: the parcel is confirmed lost in transit."
                : "Carrier investigation open; no scan activity since the last checkpoint.";
        return new AfterSalesTypes.CarrierCaseSnapshot(
                "carrier-case:" + caseId + ":v1",
                caseId,
                "SF Express",
                "LOST_CONFIRMED".equals(outcome) ? "CLOSED" : "OPEN",
                openedAt,
                openedAt,
                outcome,
                summary
        );
    }

    /** 派送/签收证明快照的确定性交付时间：比承诺天数早一天送达（按时交付的演示种子）。 */
    private static Instant deliveredAtFor(AfterSalesTypes.OrderSnapshot order) {
        return Instant.now().minus(Math.max(1, order.promisedDeliveryDays() - 1), ChronoUnit.DAYS);
    }

    /**
     * 派送证明快照（DAMAGED_ITEM 证据）：交付时间、地点与签收人全部由可信订单快照确定性
     * 派生（目的地与订单国家一致），绝不来自客户消息。evidenceId 不可变：
     * delivery:&lt;orderId&gt;:&lt;交付日期&gt;。
     */
    public AfterSalesTypes.DeliverySnapshot getDelivery(AfterSalesTypes.OrderSnapshot order) {
        Instant deliveredAt = deliveredAtFor(order);
        String deliveryId = "DL-" + order.country() + "-" + orderNumber(order.orderId());
        String location = DESTINATION_CITIES.getOrDefault(order.country(), "SEA regional hub");
        return new AfterSalesTypes.DeliverySnapshot(
                "delivery:" + order.orderId() + ":" + deliveredAt.toString().substring(0, 10),
                deliveryId,
                order.trackingNumber(),
                deliveredAt,
                location,
                "RECEIVED",
                "DELIVERED",
                "SIGNATURE"
        );
    }

    /**
     * 破损照片快照（DAMAGED_ITEM 证据）：照片 ID、拍摄时间与核验结论全部由可信订单快照
     * 确定性派生（见 VERIFIED_DAMAGE_ORDERS / REJECTED_DAMAGE_ORDERS），绝不来自客户消息。
     * evidenceId 不可变：damage-photo:&lt;photoId&gt;:v1。
     */
    public AfterSalesTypes.DamagePhotoSnapshot getDamagePhoto(AfterSalesTypes.OrderSnapshot order) {
        String photoId = "DP-" + order.country() + "-" + orderNumber(order.orderId()) + "-01";
        String status;
        if (VERIFIED_DAMAGE_ORDERS.contains(order.orderId())) {
            status = "VERIFIED";
        } else if (REJECTED_DAMAGE_ORDERS.contains(order.orderId())) {
            status = "REJECTED";
        } else {
            status = DEFAULT_DAMAGE_PHOTO_STATUS;
        }
        String reviewSummary = switch (status) {
            case "VERIFIED" -> "Manual review confirmed visible damage matching the reported item.";
            case "REJECTED" -> "Manual review found no visible damage in the photo; claim not verified.";
            default -> "Photo uploaded; awaiting manual review.";
        };
        return new AfterSalesTypes.DamagePhotoSnapshot(
                "damage-photo:" + photoId + ":v1",
                photoId,
                deliveredAtFor(order),
                "image/jpeg",
                1080,
                1440,
                1_240_000L,
                status,
                reviewSummary,
                null // 种子快照无附件记录，不派生校验和
        );
    }

    /**
     * 受损商品快照（DAMAGED_ITEM 证据）：商品 ID 取自订单行（首个商品），商品资料取自
     * 只读演示目录，全部由服务端解析，绝不来自客户消息。evidenceId 不可变：
     * product:&lt;productId&gt;:v1。
     */
    public AfterSalesTypes.ProductSnapshot getProduct(AfterSalesTypes.OrderSnapshot order) {
        Map<String, Object> orderRow = DemoFulfillmentDataFactory.createOrders().stream()
                .filter(candidate -> order.orderId().equals(candidate.get("order_id")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("ORDER_NOT_FOUND"));
        @SuppressWarnings("unchecked")
        List<String> productIds = (List<String>) orderRow.get("product_ids");
        if (productIds == null || productIds.isEmpty()) {
            throw new IllegalArgumentException("ORDER_HAS_NO_PRODUCT");
        }
        Product product = DemoCatalogDataFactory.createCatalog().stream()
                .filter(candidate -> productIds.get(0).equals(candidate.getProductId()))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("PRODUCT_NOT_FOUND"));
        return new AfterSalesTypes.ProductSnapshot(
                "product:" + product.getProductId() + ":v1",
                product.getProductId(),
                product.getName(),
                product.getCategory(),
                BigDecimal.valueOf(product.getPrice()),
                product.getCurrency(),
                product.getBrand(),
                product.isCrossBorderEligible()
        );
    }

    public AfterSalesTypes.ExecutionResult issueDelayCoupon(
            AfterSalesTypes.OrderSnapshot order,
            BigDecimal amount,
            String idempotencyKey) {
        // 连接器边界再做一次防御性校验；真实渠道应在服务端以条件写/原子命令校验同一条件。
        if (order == null || order.fullyRefunded()) {
            throw new IllegalStateException("ORDER_ALREADY_REFUNDED");
        }
        if (!order.paid()) {
            throw new IllegalStateException("ORDER_NOT_PAID");
        }
        return execute(order, new ExecutionCommand(idempotencyKey, order.orderId(),
                "DELAY_COMPENSATION_COUPON", amount, order.currency()));
    }
}
