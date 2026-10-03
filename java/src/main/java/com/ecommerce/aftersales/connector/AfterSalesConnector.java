package com.ecommerce.aftersales.connector;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import java.math.BigDecimal;
import java.util.Optional;

/**
 * External boundary. Implementations must atomically deduplicate writes and reject a reused key
 * with different arguments. findExecution must return only a matching command's committed result.
 */
public interface AfterSalesConnector {
    AfterSalesTypes.OrderSnapshot getOrder(String orderId);
    AfterSalesTypes.ShipmentSnapshot getShipment(AfterSalesTypes.OrderSnapshot order);
    AfterSalesTypes.CarrierCaseSnapshot getCarrierCase(AfterSalesTypes.OrderSnapshot order);
    AfterSalesTypes.DeliverySnapshot getDelivery(AfterSalesTypes.OrderSnapshot order);
    AfterSalesTypes.ProductSnapshot getProduct(AfterSalesTypes.OrderSnapshot order);
    AfterSalesTypes.ExecutionResult execute(AfterSalesTypes.OrderSnapshot order, ExecutionCommand command);
    Optional<AfterSalesTypes.ExecutionResult> findExecution(ExecutionCommand command);

    record ExecutionCommand(String idempotencyKey, String orderId, String actionType, BigDecimal amount, String currency) {
        public ExecutionCommand {
            if (idempotencyKey == null || idempotencyKey.isBlank() || orderId == null || orderId.isBlank()
                    || actionType == null || actionType.isBlank() || amount == null || amount.signum() <= 0
                    || currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("EXECUTION_COMMAND_INVALID");
            }
            amount = amount.stripTrailingZeros();
        }
    }
}
