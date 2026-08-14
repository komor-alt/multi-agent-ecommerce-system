package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Service
public class CompensationRuleService {

    /** SHIPMENT_DELAY：订单已付款 + 未更新天数达政策阈值 → 延迟补偿券。 */
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

    /**
     * LOST_IN_TRANSIT：订单已付款 + 未更新天数达政策阈值 + 承运商调查确认丢失
     * （CARRIER_CASE 证据）→ 全额退款（按政策比例/上限）。调查未关闭 → 失败关闭，不补偿。
     */
    public AfterSalesTypes.CompensationResult calculateLost(
            AfterSalesTypes.OrderSnapshot order,
            AfterSalesTypes.ShipmentSnapshot shipment,
            AfterSalesTypes.CarrierCaseSnapshot carrierCase,
            AfterSalesTypes.PolicyEvidence policy) {
        if (!order.paid()) {
            return blocked(order.currency(), "ORDER_NOT_PAID");
        }
        if (shipment.inactiveDays() < policy.minimumInactiveDays()) {
            return blocked(order.currency(), "POLICY_THRESHOLD_NOT_REACHED");
        }
        if (!"LOST_CONFIRMED".equals(carrierCase.outcome())) {
            return blocked(order.currency(), "CARRIER_INVESTIGATION_OPEN");
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
                "CARRIER_LOST_CONFIRMED"
        );
    }

    /**
     * DAMAGED_ITEM：订单已付款 + 交付签收（DELIVERY 证据）+ 破损照片核验通过
     * （DAMAGE_PHOTO 证据）+ 商品在跨境保障范围内（PRODUCT 证据）→ 破损补偿券
     * （按政策比例/上限）。任一不满足 → 失败关闭，不补偿。
     */
    public AfterSalesTypes.CompensationResult calculateDamage(
            AfterSalesTypes.OrderSnapshot order,
            AfterSalesTypes.DeliverySnapshot delivery,
            AfterSalesTypes.DamagePhotoSnapshot damagePhoto,
            AfterSalesTypes.ProductSnapshot product,
            AfterSalesTypes.PolicyEvidence policy) {
        if (!order.paid()) {
            return blocked(order.currency(), "ORDER_NOT_PAID");
        }
        if (!"DELIVERED".equals(delivery.status())) {
            return blocked(order.currency(), "DELIVERY_NOT_CONFIRMED");
        }
        if (!"VERIFIED".equals(damagePhoto.status())) {
            return blocked(order.currency(), "DAMAGE_PHOTO_NOT_VERIFIED");
        }
        if (!product.crossBorderEligible()) {
            return blocked(order.currency(), "PRODUCT_NOT_COVERED");
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
                "DELIVERY_DAMAGE_POLICY_MATCHED"
        );
    }

    private AfterSalesTypes.CompensationResult blocked(String currency, String reason) {
        return new AfterSalesTypes.CompensationResult(false, "NO_ACTION", BigDecimal.ZERO, currency, reason);
    }
}
