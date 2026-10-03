package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ApprovalIssueCoverageTest {
    @ParameterizedTest
    @ValueSource(strings = {"LOST_IN_TRANSIT", "DAMAGED_ITEM"})
    void approvesOnlyTheIssueSpecificEvidenceAndRecomputedAmount(String issue) {
        var connector = new MockShopifyAfterSalesConnector();
        boolean damaged = "DAMAGED_ITEM".equals(issue);
        var order = connector.getOrder(damaged ? "O-SG-1001" : "O-VN-5002");
        var ticket = ApprovalTestSupport.ticket();
        ticket.setOrderId(order.orderId());
        ticket.setUserId(order.userId());
        ticket.setIssueType(issue);
        var policies = new DemoAfterSalesPolicyCatalogService();
        var policy = policies.lookup(order.country(), issue, ticket.getCreatedAt());
        var evidence = new ArrayList<>(List.of("order:" + order.orderId(), policy.evidenceId(), "calculation:ticket-1"));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("order", order);
        answer.put("policy", policy);
        if (damaged) {
            var delivery = connector.getDelivery(order);
            var photo = connector.getDamagePhoto(order);
            var product = connector.getProduct(order);
            answer.put("delivery", delivery);
            answer.put("damagePhoto", photo);
            answer.put("product", product);
            evidence.addAll(List.of(delivery.evidenceId(), photo.evidenceId(), product.evidenceId()));
        } else {
            var carrier = connector.getCarrierCase(order);
            answer.put("shipment", connector.getShipment(order));
            answer.put("carrierCase", carrier);
            evidence.addAll(List.of("shipment:" + order.orderId(), carrier.evidenceId()));
        }
        answer.put("evidenceIds", evidence);
        var run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.writeJson(answer));
        var proposal = ApprovalTestSupport.proposal();
        proposal.setActionType(policy.actionType());
        proposal.setPolicyVersion(policy.version());
        proposal.setCurrency(order.currency());
        proposal.setAmount(new BigDecimal(damaged ? "25.00" : "150000.00"));
        proposal.setEvidenceIdsJson(ApprovalTestSupport.writeJson(evidence));
        var tickets = mock(AfterSalesTicketRepository.class);
        var runs = mock(AfterSalesRunRepository.class);
        when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        when(runs.findById(run.getId())).thenReturn(Optional.of(run));
        var result = new ApprovalPolicyGate(tickets, runs, policies, new CompensationRuleService(),
                ApprovalTestSupport.MAPPER).validate(proposal);
        assertThat(result.recomputedCompensation().amount()).isEqualByComparingTo(proposal.getAmount());
        assertThat(result.recomputedCompensation().actionType()).isEqualTo(policy.actionType());
    }
}
