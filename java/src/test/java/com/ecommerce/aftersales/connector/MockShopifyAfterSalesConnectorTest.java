package com.ecommerce.aftersales.connector;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        AfterSalesTypes.OrderSnapshot order = connector.getOrder("O-ID-4001");
        AfterSalesTypes.ShipmentSnapshot shipment = connector.getShipment(order);

        assertThat(shipment.inactiveDays()).isEqualTo(10);
        assertThat(shipment.lastUpdatedAt()).isNotNull();
        assertThat(shipment.lastUpdatedAt()).isBetween(
                java.time.Instant.now().minus(11, ChronoUnit.DAYS),
                java.time.Instant.now());
    }
}
