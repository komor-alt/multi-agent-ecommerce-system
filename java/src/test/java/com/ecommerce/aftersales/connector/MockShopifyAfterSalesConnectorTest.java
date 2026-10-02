package com.ecommerce.aftersales.connector;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mockStatic;

class MockShopifyAfterSalesConnectorTest {

    private final MockShopifyAfterSalesConnector connector = new MockShopifyAfterSalesConnector();

    @ParameterizedTest
    @CsvSource({
            "O-SG-1001,SF-SG-1001,Singapore SG",
            "O-MY-2001,SF-MY-2001,Kuala Lumpur MY",
            "O-TH-3001,SF-TH-3001,Bangkok TH",
            "O-ID-4001,SF-ID-4001,Jakarta ID",
            "O-VN-5001,SF-VN-5001,Hanoi VN"
    })
    void trackingNumberAndTimelineAreDerivedFromTrustedOrderSnapshot(
            String orderId, String expectedTracking, String expectedDestination) {
        AfterSalesTypes.OrderSnapshot order = connector.getOrder(orderId);
        assertThat(order.trackingNumber()).isEqualTo(expectedTracking);

        AfterSalesTypes.ShipmentSnapshot shipment = connector.getShipment(order);
        assertThat(shipment.trackingNumber()).isEqualTo(expectedTracking);
        assertThat(shipment.timeline()).isNotEmpty();
        AfterSalesTypes.ShipmentCheckpoint last = shipment.timeline().get(shipment.timeline().size() - 1);
        // 轨迹最后节点落在订单国家，不再对所有国家返回 Hanoi VN。
        assertThat(last.location()).isEqualTo(expectedDestination);
    }

    @Test
    void derivesTrackingNumberFromTrustedOrderId() {
        assertThat(MockShopifyAfterSalesConnector.deriveTrackingNumber("O-SG-1001", "SG"))
                .isEqualTo("SF-SG-1001");
    }

    @Test
    void rejectsOrderIdThatDoesNotMatchTrustedFormat() {
        // 旧实现按索引截取会产出 SF-ID-ID-4001 这类重复国家段，整体格式校验必须拦截。
        assertThatThrownBy(() -> MockShopifyAfterSalesConnector.deriveTrackingNumber("O-ID-ID-4001", "ID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UNTRUSTED_ORDER_ID_FORMAT");
        assertThatThrownBy(() -> MockShopifyAfterSalesConnector.deriveTrackingNumber("ID-4001", "ID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("UNTRUSTED_ORDER_ID_FORMAT");
    }

    @Test
    void rejectsOrderIdWhoseCountryDiffersFromTrustedSnapshot() {
        assertThatThrownBy(() -> MockShopifyAfterSalesConnector.deriveTrackingNumber("O-SG-1001", "ID"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ORDER_ID_COUNTRY_MISMATCH");
    }

    @Test
    void keepsTenDayInactiveDemoBehavior() {
        Instant reference = Instant.now();
        AfterSalesTypes.OrderSnapshot order = connector.getOrder("O-ID-4001");
        AfterSalesTypes.ShipmentSnapshot shipment = connector.getShipment(order);

        assertThat(shipment.inactiveDays()).isEqualTo(10);
        assertThat(shipment.lastUpdatedAt()).isNotNull();
        assertThat(shipment.lastUpdatedAt()).isBetween(
                reference.minus(11, ChronoUnit.DAYS),
                reference);
    }

    @Test
    void vn5003IsDeterministicallyInactiveForTwoDays() {
        Instant reference = Instant.now();
        AfterSalesTypes.OrderSnapshot order = connector.getOrder("O-VN-5003");
        AfterSalesTypes.ShipmentSnapshot shipment = connector.getShipment(order);

        // 未更新天数来自可信种子（2 天，低于政策阈值 7 天 → 不可补偿），不依赖客户消息。
        assertThat(shipment.inactiveDays()).isEqualTo(2);
        assertThat(shipment.lastUpdatedAt()).isBetween(
                reference.minus(3, ChronoUnit.DAYS),
                reference.minus(1, ChronoUnit.DAYS));
        // 内部自洽：轨迹最后节点时间 = lastUpdated（此后无更新），状态与订单履约状态一致。
        assertThat(shipment.timeline()).isNotEmpty();
        AfterSalesTypes.ShipmentCheckpoint last = shipment.timeline().get(shipment.timeline().size() - 1);
        assertThat(last.occurredAt()).isEqualTo(shipment.lastUpdatedAt());
        assertThat(last.status()).isEqualTo("CUSTOMS_DOCUMENT_REQUIRED");
        assertThat(last.location()).isEqualTo("Hanoi VN");
        assertThat(shipment.status()).isEqualTo("customs_document_required");
        assertThat(shipment.delayDays()).isEqualTo(0); // 2 天未更新 ≤ 承诺 7 天
    }

    @Test
    void vn5002IsDeterministicallyInactiveForTenDays() {
        Instant reference = Instant.now();
        AfterSalesTypes.OrderSnapshot order = connector.getOrder("O-VN-5002");
        AfterSalesTypes.ShipmentSnapshot shipment = connector.getShipment(order);

        // 10 天 ≥ 政策阈值 7 天 → 补偿评估可达资格；轨迹时间点与未更新天数自洽。
        assertThat(shipment.inactiveDays()).isEqualTo(10);
        assertThat(shipment.lastUpdatedAt()).isBetween(
                reference.minus(11, ChronoUnit.DAYS),
                reference.minus(9, ChronoUnit.DAYS));
        AfterSalesTypes.ShipmentCheckpoint last = shipment.timeline().get(shipment.timeline().size() - 1);
        assertThat(last.occurredAt()).isEqualTo(shipment.lastUpdatedAt());
        assertThat(last.location()).isEqualTo("Hanoi VN");
    }

    @Test
    void carrierCaseIsDerivedFromTrustedOrderWithStableEvidenceId() {
        AfterSalesTypes.OrderSnapshot order = connector.getOrder("O-VN-5002");
        AfterSalesTypes.CarrierCaseSnapshot carrierCase = connector.getCarrierCase(order);

        assertThat(carrierCase.caseId()).isEqualTo("CC-VN-5002");
        assertThat(carrierCase.evidenceId()).isEqualTo("carrier-case:CC-VN-5002:v1");
        assertThat(carrierCase.outcome()).isEqualTo("LOST_CONFIRMED");
        assertThat(carrierCase.status()).isEqualTo("CLOSED");
        assertThat(carrierCase.carrierName()).isNotBlank();
        assertThat(carrierCase.summary()).contains("lost");

        // 调查中的订单：确定性不同结论与证据 ID。
        AfterSalesTypes.CarrierCaseSnapshot open = connector.getCarrierCase(connector.getOrder("O-ID-4001"));
        assertThat(open.caseId()).isEqualTo("CC-ID-4001");
        assertThat(open.evidenceId()).isEqualTo("carrier-case:CC-ID-4001:v1");
        assertThat(open.outcome()).isEqualTo("UNDER_INVESTIGATION");
        assertThat(open.status()).isEqualTo("OPEN");
    }

    @Test
    void deliveryProofIsDerivedFromTrustedOrderWithStableEvidenceId() {
        AfterSalesTypes.OrderSnapshot order = connector.getOrder("O-SG-1001");
        AfterSalesTypes.DeliverySnapshot delivery = connector.getDelivery(order);

        assertThat(delivery.deliveryId()).isEqualTo("DL-SG-1001");
        assertThat(delivery.trackingNumber()).isEqualTo("SF-SG-1001");
        assertThat(delivery.status()).isEqualTo("DELIVERED");
        assertThat(delivery.proofType()).isEqualTo("SIGNATURE");
        assertThat(delivery.deliveredLocation()).isEqualTo("Singapore SG");
        // evidenceId 不可变：delivery:<orderId>:<交付日期>，与既有 shipment evidenceId 模式一致。
        assertThat(delivery.evidenceId()).startsWith("delivery:O-SG-1001:");
        assertThat(delivery.deliveredAt().toString()).startsWith(delivery.evidenceId().substring("delivery:O-SG-1001:".length()));
    }

    @Test
    void damagePhotoIsDerivedWithVerifiedAndRejectedSeeds() {
        Instant reference = Instant.parse("2026-09-25T12:00:00Z");
        // Both independently derived evidence snapshots must use the same wall-clock instant.
        try (MockedStatic<Instant> clock = mockStatic(Instant.class, CALLS_REAL_METHODS)) {
            clock.when(Instant::now).thenReturn(reference);
            AfterSalesTypes.DamagePhotoSnapshot verified = connector.getDamagePhoto(connector.getOrder("O-SG-1001"));
            assertThat(verified.photoId()).isEqualTo("DP-SG-1001-01");
            assertThat(verified.evidenceId()).isEqualTo("damage-photo:DP-SG-1001-01:v1");
            assertThat(verified.status()).isEqualTo("VERIFIED");
            assertThat(verified.reviewSummary()).contains("confirmed");

            AfterSalesTypes.DamagePhotoSnapshot rejected = connector.getDamagePhoto(connector.getOrder("O-MY-2001"));
            assertThat(rejected.status()).isEqualTo("REJECTED");
            assertThat(rejected.evidenceId()).isEqualTo("damage-photo:DP-MY-2001-01:v1");

            AfterSalesTypes.DamagePhotoSnapshot pending = connector.getDamagePhoto(connector.getOrder("O-TH-3001"));
            assertThat(pending.status()).isEqualTo("PENDING_REVIEW");
            assertThat(pending.capturedAt()).isEqualTo(connector.getDelivery(connector.getOrder("O-TH-3001")).deliveredAt());
        }
    }

    @Test
    void productSnapshotIsResolvedFromTrustedOrderLine() {
        AfterSalesTypes.OrderSnapshot order = connector.getOrder("O-SG-1001");
        AfterSalesTypes.ProductSnapshot product = connector.getProduct(order);

        assertThat(product.productId()).isEqualTo("P001");
        assertThat(product.evidenceId()).isEqualTo("product:P001:v1");
        assertThat(product.name()).isEqualTo("Anker 140W GaN Charger");
        assertThat(product.category()).isEqualTo("accessory");
        assertThat(product.crossBorderEligible()).isTrue();
        assertThat(product.listPrice()).isNotNull();
    }

    @Test
    void issueCouponRejectsRefundedOrderAtConnectorBoundary() {
        AfterSalesTypes.OrderSnapshot refundedOrder = new AfterSalesTypes.OrderSnapshot(
                "O-SG-1001",
                "U-1",
                "shopify",
                "SG",
                "SGD",
                "SG",
                new BigDecimal("100.00"),
                true,
                "IN_TRANSIT",
                5,
                "SF-SG-1001",
                "REFUNDED"
        );

        assertThatThrownBy(() -> connector.issueDelayCoupon(
                refundedOrder, new BigDecimal("10.00"), "0123456789abcdef"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("ORDER_ALREADY_REFUNDED");
    }
}
