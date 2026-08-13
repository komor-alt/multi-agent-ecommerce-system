package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class CompensationRuleService {

    public AfterSalesTypes.CompensationResult calculate(
            AfterSalesTypes.OrderSnapshot order,
            AfterSalesTypes.ShipmentSnapshot shipment,
            AfterSalesTypes.PolicyEvidence policy) {
        if (!order.paid()) {
            return blocked(order.currency(), "ORDER_NOT_PAID");
        }
        if (shipment.inactiveDays() < policy.minimumInactiveDays()) {
            return blocked(order.currency(), "POLICY_THRESHOLD_NOT_REACHED");
        }

        BigDecimal amount = order.paidAmount()
                .multiply(policy.compensationRate())
                .min(policy.maximumCompensation())
                .setScale(2, RoundingMode.HALF_UP);
        return new AfterSalesTypes.CompensationResult(
                true,
                policy.actionType(),
                amount,
                order.currency(),
                "SHIPMENT_INACTIVE_POLICY_MATCHED"
        );
    }

    private AfterSalesTypes.CompensationResult blocked(String currency, String reason) {
        return new AfterSalesTypes.CompensationResult(false, "NO_ACTION", BigDecimal.ZERO, currency, reason);
    }
}
