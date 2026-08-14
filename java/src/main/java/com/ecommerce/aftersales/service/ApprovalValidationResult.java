package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;

/**
 * 审批策略校验通过后的可信复核结果：Gate 从工单 / 运行 / 最终答复 / 政策目录 / 规则引擎
 * 重建的服务端可信数据，供调用方（ApprovalService）在通过后落库使用；
 * 任何校验失败都以 ApprovalPolicyViolationException 抛出，绝不返回部分结果。
 */
public record ApprovalValidationResult(
        String proposalId,
        AfterSalesTypes.OrderSnapshot order,
        AfterSalesTypes.ShipmentSnapshot shipment,
        AfterSalesTypes.PolicyEvidence policy,
        AfterSalesTypes.CompensationResult recomputedCompensation) {
}
