package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AfterSalesToolExecutorTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ActionProposalRepository proposalRepository = mock(ActionProposalRepository.class);
    private final AfterSalesToolExecutor executor = new AfterSalesToolExecutor(
            new MockShopifyAfterSalesConnector(),
            new DemoAfterSalesPolicyCatalogService(),
            new CompensationRuleService(),
            proposalRepository,
            objectMapper);

    @Test
    void indonesiaOrderOid4001ReachesProposalCreationInsteadOfPolicyNotFound() {
        when(proposalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        AfterSalesAgentState state = state("O-ID-4001", "2026-08-01T00:00:00Z");

        AfterSalesTypes.ToolResult orderResult = executor.execute(AfterSalesToolExecutor.GET_ORDER_DETAIL, state);
        AfterSalesTypes.OrderSnapshot order = (AfterSalesTypes.OrderSnapshot) orderResult.data();
        assertThat(order.country()).isEqualTo("ID");
        assertThat(order.trackingNumber()).isEqualTo("SF-ID-4001");
        // 种子回归：IDR 订单金额必须保持 IDR 合理量级，不得退回 241.0。
        assertThat(order.paidAmount()).isEqualByComparingTo("2410000.00");

        AfterSalesTypes.ToolResult shipmentResult = executor.execute(AfterSalesToolExecutor.GET_SHIPMENT_TRACE, state);
        AfterSalesTypes.ShipmentSnapshot shipment = (AfterSalesTypes.ShipmentSnapshot) shipmentResult.data();
        assertThat(shipment.inactiveDays()).isEqualTo(10);
        assertThat(shipment.timeline().get(shipment.timeline().size() - 1).location()).isEqualTo("Jakarta ID");

        AfterSalesTypes.ToolResult policyResult = executor.execute(AfterSalesToolExecutor.SEARCH_POLICY, state);
        AfterSalesTypes.PolicyEvidence policy = (AfterSalesTypes.PolicyEvidence) policyResult.data();
        assertThat(policy.policyId()).isEqualTo("ID_SHIPMENT_DELAY");
        assertThat(policy.evidenceId()).isEqualTo("policy:ID_SHIPMENT_DELAY:v2#section-4.2");
        // 工具/检索摘要不得写死越南。
        assertThat(policyResult.summary()).doesNotContain("Vietnam").contains("v2");

        AfterSalesTypes.ToolResult compensationResult = executor.execute(AfterSalesToolExecutor.CALCULATE_COMPENSATION, state);
        AfterSalesTypes.CompensationResult compensation = (AfterSalesTypes.CompensationResult) compensationResult.data();
        assertThat(compensation.eligible()).isTrue();
        // 确定性计算：2410000.00 IDR × 10% = 241000.00，低于 IDR 上限 250000。
        assertThat(compensation.amount()).isEqualByComparingTo("241000.00");
        assertThat(compensation.currency()).isEqualTo("IDR");

        executor.execute(AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL, state);
        assertThat(state.getProposalId()).isNotNull();

        ArgumentCaptor<ActionProposalEntity> proposalCaptor = ArgumentCaptor.forClass(ActionProposalEntity.class);
        verify(proposalRepository).save(proposalCaptor.capture());
        ActionProposalEntity proposal = proposalCaptor.getValue();
        assertThat(proposal.getActionType()).isEqualTo("DELAY_COMPENSATION_COUPON");
        assertThat(proposal.getAmount()).isEqualByComparingTo("241000.00");
        assertThat(proposal.getCurrency()).isEqualTo("IDR");
        assertThat(proposal.getPolicyVersion()).isEqualTo("v2");
        assertThat(proposal.getStatus()).isEqualTo(AfterSalesTypes.ProposalStatus.PENDING);
        // decisionSummary 国家/政策中立，不写死越南。
        assertThat(proposal.getDecisionSummary()).doesNotContain("Vietnam").contains("policy threshold");
        // 证据 ID 链完整且含稳定政策证据 ID。
        assertThat(proposal.getEvidenceIdsJson())
                .contains("order:O-ID-4001:v1")
                .contains("policy:ID_SHIPMENT_DELAY:v2#section-4.2");
        assertThat(state.getEvidenceIds()).hasSize(4);
        assertThat(state.getEvidenceIds()).contains(
                "order:O-ID-4001:v1",
                "policy:ID_SHIPMENT_DELAY:v2#section-4.2",
                "calculation:" + state.getTicket().getId() + ":v1");
    }

    @Test
    void unsupportedCountryFailsClosedOnPolicyLookupWithStructuredError() {
        AfterSalesAgentState state = state("O-ID-4001", "2026-08-01T00:00:00Z");
        state.setOrder(new AfterSalesTypes.OrderSnapshot(
                "O-KR-9001", "sea_kr_001", "shopify", "KR", "KRW", "KR",
                new BigDecimal("50000.00"), true, "cross_border_shipping", 5, "SF-KR-9001"));
        state.setShipment(new AfterSalesTypes.ShipmentSnapshot(
                "SF-KR-9001", "cross_border_shipping", Instant.now(), 10, 5, List.of()));

        assertThatThrownBy(() -> executor.execute(AfterSalesToolExecutor.SEARCH_POLICY, state))
                .isInstanceOfSatisfying(PolicyNotFoundException.class, error -> {
                    assertThat(error.getMessage()).isEqualTo("POLICY_NOT_FOUND");
                    assertThat(error.getCountry()).isEqualTo("KR");
                    assertThat(error.getIssueType()).isEqualTo("SHIPMENT_DELAY");
                });
        assertThat(state.getPolicy()).isNull();
    }

    private AfterSalesAgentState state(String orderId, String createdAt) {
        AfterSalesTicketEntity ticket = AfterSalesTicketEntity.builder()
                .id("ticket-4001")
                .ticketNo("AS-4001")
                .orderId(orderId)
                .issueType("SHIPMENT_DELAY")
                .customerMessage("my parcel from Indonesia has not moved in days")
                .status(AfterSalesTypes.TicketStatus.ANALYZING)
                .createdAt(Instant.parse(createdAt))
                .build();
        return new AfterSalesAgentState("run-4001", ticket);
    }
}
