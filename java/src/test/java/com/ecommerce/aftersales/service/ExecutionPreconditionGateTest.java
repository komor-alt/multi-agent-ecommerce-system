package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ExecutionPreconditionGateTest {
    private final ExecutionPreconditionGate gate = new ExecutionPreconditionGate();

    @Test
    void rejectsOrderThatWasRefundedAfterApproval() {
        ExecutionPreconditionGate.ValidationResult result = gate.validate(order(true, "IN_TRANSIT", "REFUNDED"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(ExecutionPreconditionGate.ORDER_ALREADY_REFUNDED);
    }

    @Test
    void acceptsPaidActiveOrder() {
        ExecutionPreconditionGate.ValidationResult result = gate.validate(order(true, "IN_TRANSIT", "NONE"));

        assertThat(result.allowed()).isTrue();
        assertThat(result.reasonCode()).isNull();
    }

    @Test
    void rejectsCancelledOrder() {
        ExecutionPreconditionGate.ValidationResult result = gate.validate(order(true, "CANCELLED", "NONE"));

        assertThat(result.allowed()).isFalse();
        assertThat(result.reasonCode()).isEqualTo(ExecutionPreconditionGate.ORDER_NO_LONGER_EXECUTABLE);
    }

    private AfterSalesTypes.OrderSnapshot order(boolean paid, String fulfillmentStatus, String refundStatus) {
        return new AfterSalesTypes.OrderSnapshot(
                "O-VN-5002",
                "U-1",
                "shopify",
                "VN",
                "VND",
                "VN-SOUTH",
                new BigDecimal("100.00"),
                paid,
                fulfillmentStatus,
                5,
                "SF-VN-5002",
                refundStatus
        );
    }
}
