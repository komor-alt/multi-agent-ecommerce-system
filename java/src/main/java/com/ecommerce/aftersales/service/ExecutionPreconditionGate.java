package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Set;

/**
 * 在不可逆副作用发生前，基于最新订单快照重新校验业务前置条件。
 * 这是审批校验之外的执行时门禁，用来处理审批后订单状态变化的 TOCTOU 场景。
 */
@Service
public class ExecutionPreconditionGate {
    public static final String ORDER_SNAPSHOT_MISSING = "ORDER_SNAPSHOT_MISSING";
    public static final String ORDER_ALREADY_REFUNDED = "ORDER_ALREADY_REFUNDED";
    public static final String ORDER_NOT_PAID = "ORDER_NOT_PAID";
    public static final String ORDER_NO_LONGER_EXECUTABLE = "ORDER_NO_LONGER_EXECUTABLE";

    private static final Set<String> TERMINAL_FULFILLMENT_STATUSES =
            Set.of("CANCELLED", "CANCELED", "REFUNDED", "RETURNED");

    public ValidationResult validate(AfterSalesTypes.OrderSnapshot order) {
        if (order == null) {
            return ValidationResult.rejected(ORDER_SNAPSHOT_MISSING);
        }
        if (order.fullyRefunded()) {
            return ValidationResult.rejected(ORDER_ALREADY_REFUNDED);
        }
        if (!order.paid()) {
            return ValidationResult.rejected(ORDER_NOT_PAID);
        }
        String fulfillmentStatus = order.fulfillmentStatus() == null
                ? ""
                : order.fulfillmentStatus().toUpperCase(Locale.ROOT);
        if (TERMINAL_FULFILLMENT_STATUSES.contains(fulfillmentStatus)) {
            return ValidationResult.rejected(ORDER_NO_LONGER_EXECUTABLE);
        }
        return ValidationResult.pass();
    }

    public record ValidationResult(boolean allowed, String reasonCode) {
        private static ValidationResult pass() {
            return new ValidationResult(true, null);
        }

        private static ValidationResult rejected(String reasonCode) {
            return new ValidationResult(false, reasonCode);
        }
    }
}
