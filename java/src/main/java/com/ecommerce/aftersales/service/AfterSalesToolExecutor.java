package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AfterSalesToolExecutor {
    public static final String GET_ORDER_DETAIL = "get_order_detail";
    public static final String GET_SHIPMENT_TRACE = "get_shipment_trace";
    public static final String SEARCH_POLICY = "search_after_sales_policy";
    public static final String CALCULATE_COMPENSATION = "calculate_compensation";
    public static final String CREATE_ACTION_PROPOSAL = "create_action_proposal";

    private final MockShopifyAfterSalesConnector connector;
    private final DemoAfterSalesPolicyCatalogService policyCatalog;
    private final CompensationRuleService compensationRuleService;
    private final ActionProposalRepository proposalRepository;
    private final ObjectMapper objectMapper;

    public AfterSalesToolExecutor(
            MockShopifyAfterSalesConnector connector,
            DemoAfterSalesPolicyCatalogService policyCatalog,
            CompensationRuleService compensationRuleService,
            ActionProposalRepository proposalRepository,
            ObjectMapper objectMapper) {
        this.connector = connector;
        this.policyCatalog = policyCatalog;
        this.compensationRuleService = compensationRuleService;
        this.proposalRepository = proposalRepository;
        this.objectMapper = objectMapper;
    }

    public AfterSalesTypes.ToolResult execute(String action, AfterSalesAgentState state) {
        return switch (action) {
            case GET_ORDER_DETAIL -> getOrderDetail(state);
            case GET_SHIPMENT_TRACE -> getShipmentTrace(state);
            case SEARCH_POLICY -> searchPolicy(state);
            case CALCULATE_COMPENSATION -> calculateCompensation(state);
            case CREATE_ACTION_PROPOSAL -> createActionProposal(state);
            default -> throw new IllegalArgumentException("TOOL_NOT_WHITELISTED");
        };
    }

    public Map<String, Object> trustedArguments(String action, AfterSalesAgentState state) {
        return switch (action) {
            case GET_ORDER_DETAIL -> Map.of(
                    "orderId", state.getTicket().getOrderId(),
                    "ticketId", state.getTicket().getId()
            );
            case GET_SHIPMENT_TRACE -> Map.of(
                    "orderId", state.getOrder().orderId(),
                    "trackingNumber", state.getOrder().trackingNumber()
            );
            case SEARCH_POLICY -> Map.of(
                    "country", state.getOrder().country(),
                    "issueType", state.getTicket().getIssueType(),
                    "occurredAt", state.getTicket().getCreatedAt().toString()
            );
            case CALCULATE_COMPENSATION -> Map.of(
                    "orderAmount", state.getOrder().paidAmount(),
                    "currency", state.getOrder().currency(),
                    "inactiveDays", state.getShipment().inactiveDays(),
                    "policyVersion", state.getPolicy().version()
            );
            case CREATE_ACTION_PROPOSAL -> Map.of(
                    "ticketId", state.getTicket().getId(),
                    "actionType", state.getCompensation().actionType(),
                    "amount", state.getCompensation().amount(),
                    "currency", state.getCompensation().currency(),
                    "policyVersion", state.getPolicy().version()
            );
            default -> Map.of();
        };
    }

    private AfterSalesTypes.ToolResult getOrderDetail(AfterSalesAgentState state) {
        AfterSalesTypes.OrderSnapshot order = connector.getOrder(state.getTicket().getOrderId());
        state.setOrder(order);
        state.getTicket().setUserId(order.userId());
        String evidenceId = "order:" + order.orderId() + ":v1";
        state.getEvidenceIds().add(evidenceId);
        return new AfterSalesTypes.ToolResult(
                "Paid order verified for " + order.country() + " with trusted amount and currency.",
                order,
                List.of(evidenceId)
        );
    }

    private AfterSalesTypes.ToolResult getShipmentTrace(AfterSalesAgentState state) {
        require(state.getOrder(), "ORDER_REQUIRED");
        AfterSalesTypes.ShipmentSnapshot shipment = connector.getShipment(state.getOrder());
        state.setShipment(shipment);
        String evidenceId = "shipment:" + state.getOrder().orderId() + ":" + shipment.lastUpdatedAt().toString().substring(0, 10);
        state.getEvidenceIds().add(evidenceId);
        return new AfterSalesTypes.ToolResult(
                "Shipment has been inactive for " + shipment.inactiveDays() + " days at customs.",
                shipment,
                List.of(evidenceId)
        );
    }

    private AfterSalesTypes.ToolResult searchPolicy(AfterSalesAgentState state) {
        require(state.getOrder(), "ORDER_REQUIRED");
        require(state.getShipment(), "SHIPMENT_REQUIRED");
        // 可信键来自订单快照与工单：country + issueType + occurredAt。
        // 无匹配政策时目录失败关闭（PolicyNotFoundException / POLICY_NOT_FOUND），不提供通用政策兜底。
        AfterSalesTypes.PolicyEvidence policy = policyCatalog.lookup(
                state.getOrder().country(),
                state.getTicket().getIssueType(),
                state.getTicket().getCreatedAt()
        );
        state.setPolicy(policy);
        state.getEvidenceIds().add(policy.evidenceId());
        return new AfterSalesTypes.ToolResult(
                "Shipment delay policy " + policy.version() + " section " + policy.section()
                        + " matched the ticket occurrence time.",
                policy,
                List.of(policy.evidenceId())
        );
    }

    private AfterSalesTypes.ToolResult calculateCompensation(AfterSalesAgentState state) {
        require(state.getOrder(), "ORDER_REQUIRED");
        require(state.getShipment(), "SHIPMENT_REQUIRED");
        require(state.getPolicy(), "POLICY_REQUIRED");
        AfterSalesTypes.CompensationResult result =
                compensationRuleService.calculate(state.getOrder(), state.getShipment(), state.getPolicy());
        state.setCompensation(result);
        String evidenceId = "calculation:" + state.getTicket().getId() + ":v1";
        state.getEvidenceIds().add(evidenceId);
        return new AfterSalesTypes.ToolResult(
                result.eligible()
                        ? "Deterministic rules calculated an eligible " + result.amount() + " " + result.currency() + " coupon."
                        : "Deterministic rules blocked compensation: " + result.reason(),
                result,
                List.of(evidenceId)
        );
    }

    private AfterSalesTypes.ToolResult createActionProposal(AfterSalesAgentState state) {
        require(state.getCompensation(), "COMPENSATION_REQUIRED");
        if (!state.getCompensation().eligible()) {
            throw new IllegalStateException("COMPENSATION_NOT_ELIGIBLE");
        }
        ActionProposalEntity proposal = proposalRepository.save(ActionProposalEntity.builder()
                .id(UUID.randomUUID().toString())
                .ticketId(state.getTicket().getId())
                .actionType(state.getCompensation().actionType())
                .amount(state.getCompensation().amount())
                .currency(state.getCompensation().currency())
                .policyVersion(state.getPolicy().version())
                .proposalVersion("v1")
                .decisionSummary("The paid order is inactive beyond the applicable policy threshold. A delay coupon requires operator approval.")
                .evidenceIdsJson(writeJson(state.getEvidenceIds()))
                .status(AfterSalesTypes.ProposalStatus.PENDING)
                .build());
        state.setProposalId(proposal.getId());
        return new AfterSalesTypes.ToolResult(
                "Action proposal created and isolated from the side-effect executor.",
                proposal,
                List.copyOf(state.getEvidenceIds())
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("PROPOSAL_SERIALIZATION_FAILED", error);
        }
    }

    private static void require(Object value, String code) {
        if (value == null) {
            throw new IllegalStateException(code);
        }
    }
}
