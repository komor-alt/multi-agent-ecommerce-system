package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CompensationRuleServiceTest {
    private final CompensationRuleService service = new CompensationRuleService();

    @Test
    void calculatesExactAmountFromTrustedOrderAndPolicy() {
        AfterSalesTypes.OrderSnapshot order = order(true, new BigDecimal("1899000.00"));
        AfterSalesTypes.ShipmentSnapshot shipment = shipment(10);
        AfterSalesTypes.PolicyEvidence policy = policy(7, new BigDecimal("0.10"), new BigDecimal("150000.00"));

        AfterSalesTypes.CompensationResult result = service.calculate(order, shipment, policy);

        assertThat(result.eligible()).isTrue();
        assertThat(result.amount()).isEqualByComparingTo("150000.00");
        assertThat(result.currency()).isEqualTo("VND");
        assertThat(result.actionType()).isEqualTo("DELAY_COMPENSATION_COUPON");
    }

    @Test
    void blocksWhenShipmentDoesNotReachPolicyThreshold() {
        AfterSalesTypes.CompensationResult result = service.calculate(
                order(true, new BigDecimal("1899000.00")),
                shipment(5),
                policy(7, new BigDecimal("0.10"), new BigDecimal("150000.00"))
        );

        assertThat(result.eligible()).isFalse();
        assertThat(result.reason()).isEqualTo("POLICY_THRESHOLD_NOT_REACHED");
        assertThat(result.amount()).isEqualByComparingTo("0");
    }

    private AfterSalesTypes.OrderSnapshot order(boolean paid, BigDecimal amount) {
        return new AfterSalesTypes.OrderSnapshot(
                "O-VN-5002", "sea_vn_002", "shopify", "VN", "VND", "CN",
                amount, paid, "customs_document_required", 10, "SF-VN-5002"
        );
    }

    private AfterSalesTypes.ShipmentSnapshot shipment(int inactiveDays) {
        return new AfterSalesTypes.ShipmentSnapshot(
                "SF-VN-5002", "customs_document_required", Instant.now(),
                inactiveDays, Math.max(0, inactiveDays - 10), List.of()
        );
    }

    private AfterSalesTypes.PolicyEvidence policy(int threshold, BigDecimal rate, BigDecimal maximum) {
        return new AfterSalesTypes.PolicyEvidence(
                "policy:VN_SHIPMENT_DELAY:v3#section-4.2",
                "VN_SHIPMENT_DELAY", "v3", "VN", "SHIPMENT_DELAY", Instant.now(),
                threshold, rate, maximum, "DELAY_COMPENSATION_COUPON", "4.2", "delay coupon"
        );
    }
}
