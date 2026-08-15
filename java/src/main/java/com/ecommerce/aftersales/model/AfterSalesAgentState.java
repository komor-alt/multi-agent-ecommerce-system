package com.ecommerce.aftersales.model;

import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.service.LlmCallBudget;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@RequiredArgsConstructor
public class AfterSalesAgentState {
    private final String runId;
    private final AfterSalesTicketEntity ticket;
    private AfterSalesTypes.OrderSnapshot order;
    private AfterSalesTypes.ShipmentSnapshot shipment;
    private AfterSalesTypes.CarrierCaseSnapshot carrierCase;
    private AfterSalesTypes.DeliverySnapshot delivery;
    private AfterSalesTypes.DamagePhotoSnapshot damagePhoto;
    private AfterSalesTypes.ProductSnapshot product;
    private AfterSalesTypes.PolicyEvidence policy;
    private AfterSalesTypes.CompensationResult compensation;
    private AfterSalesTypes.IntakeResult intake;
    /** Intake 分类后由 DecisionRouteResolver 确定性解析的决策路线；模型不能输出。 */
    private DecisionRoute route;
    private String proposalId;
    private LlmCallBudget llmBudget;
    private final List<String> evidenceIds = new ArrayList<>();
}
