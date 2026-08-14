package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 审批测试共用夹具：可信 VN 订单 + 物流 + 政策 + 最终答复 + 待审批方案，
 * 与 DemoAfterSalesPolicyCatalogService / CompensationRuleService 的真实数据一致：
 * 1899000.00 VND × 10% = 189900.00，min(189900, 150000) = 150000.00 VND 补偿。
 */
final class ApprovalTestSupport {

    static final ObjectMapper MAPPER = new ObjectMapper().registerModule(new JavaTimeModule());
    static final Instant TICKET_CREATED_AT = Instant.parse("2026-08-01T00:00:00Z");

    private ApprovalTestSupport() {
    }

    static AfterSalesTypes.OrderSnapshot order() {
        return new AfterSalesTypes.OrderSnapshot(
                "O-VN-5002", "sea_vn_002", "shopify", "VN", "VND", "CN",
                new BigDecimal("1899000.00"), true, "customs_document_required", 10, "SF-VN-5002");
    }

    static AfterSalesTypes.ShipmentSnapshot shipment() {
        return shipment(10);
    }

    static AfterSalesTypes.ShipmentSnapshot shipment(int inactiveDays) {
        return new AfterSalesTypes.ShipmentSnapshot(
                "SF-VN-5002", "customs_document_required", TICKET_CREATED_AT, inactiveDays, 0, List.of());
    }

    /** VN v3 政策（2026-01-01 生效，与目录一致）。 */
    static AfterSalesTypes.PolicyEvidence policy() {
        return new AfterSalesTypes.PolicyEvidence(
                "policy:VN_SHIPMENT_DELAY:v3#section-4.2", "VN_SHIPMENT_DELAY", "v3", "VN",
                "SHIPMENT_DELAY", Instant.parse("2026-01-01T00:00:00Z"), 7, new BigDecimal("0.10"),
                new BigDecimal("150000.00"), "DELAY_COMPENSATION_COUPON", "4.2", "delay coupon");
    }

    static AfterSalesTicketEntity ticket() {
        return AfterSalesTicketEntity.builder()
                .id("ticket-1")
                .ticketNo("AS-1")
                .orderId("O-VN-5002")
                .issueType("SHIPMENT_DELAY")
                .customerMessage("refund please")
                .status(AfterSalesTypes.TicketStatus.PENDING_APPROVAL)
                .currentRunId("run-1")
                .createdAt(TICKET_CREATED_AT)
                .build();
    }

    static AfterSalesRunEntity run() {
        return AfterSalesRunEntity.builder()
                .id("run-1")
                .ticketId("ticket-1")
                .status("COMPLETED")
                .maxSteps(6)
                .stepCount(5)
                .stopReason("ACTION_PROPOSAL_CREATED")
                .finalAnswerJson(finalAnswerJson())
                .build();
    }

    static String finalAnswerJson() {
        return finalAnswerJson(order(), shipment(), policy());
    }

    static String finalAnswerJson(
            AfterSalesTypes.OrderSnapshot order,
            AfterSalesTypes.ShipmentSnapshot shipment,
            AfterSalesTypes.PolicyEvidence policy) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("order", order);
        answer.put("shipment", shipment);
        answer.put("policy", policy);
        return writeJson(answer);
    }

    static String writeJson(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception error) {
            throw new AssertionError("fixture serialization failed", error);
        }
    }

    static ActionProposalEntity proposal() {
        return ActionProposalEntity.builder()
                .id("proposal-1")
                .ticketId("ticket-1")
                .actionType("DELAY_COMPENSATION_COUPON")
                .amount(new BigDecimal("150000.00"))
                .currency("VND")
                .policyVersion("v3")
                .proposalVersion("v1")
                .decisionSummary("delay coupon pending operator approval")
                .evidenceIdsJson("[\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                        + "\"policy:VN_SHIPMENT_DELAY:v3#section-4.2\",\"calculation:ticket-1:v1\"]")
                .status(AfterSalesTypes.ProposalStatus.PENDING)
                .build();
    }

    /** 构造通过全部复核的 Gate：mock 工单/运行仓库 + 真实政策目录与规则引擎。 */
    static ApprovalPolicyGate passingGate() {
        return gate(ticket(), run());
    }

    static ApprovalPolicyGate gate(AfterSalesTicketEntity ticket, AfterSalesRunEntity run) {
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        when(ticketRepository.findById(anyString())).thenReturn(Optional.of(ticket));
        when(runRepository.findById(anyString())).thenReturn(Optional.of(run));
        return new ApprovalPolicyGate(
                ticketRepository, runRepository,
                new DemoAfterSalesPolicyCatalogService(),
                new CompensationRuleService(),
                MAPPER);
    }
}
