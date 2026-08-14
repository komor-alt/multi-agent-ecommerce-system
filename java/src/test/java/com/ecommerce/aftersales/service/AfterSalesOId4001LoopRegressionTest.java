package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.TicketAttachmentRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.client.ChatClient;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * O-ID-4001（印尼订单）真实运行回归：使用真实 Connector + 政策目录 + 工具执行器走完整 Agent Loop，
 * 证明不再以 POLICY_NOT_FOUND 失败，而是到达提案创建（run_completed / ACTION_PROPOSAL_CREATED）。
 */
class AfterSalesOId4001LoopRegressionTest {

    private record CapturedEvent(String type, Map<String, Object> data) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void oid4001CompletesWithProposalInsteadOfPolicyNotFound() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        ActionProposalRepository proposalRepository = mock(ActionProposalRepository.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(1), invocation.getArgument(5)));
            return null;
        }).when(eventService).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());

        AfterSalesToolExecutor toolExecutor = new AfterSalesToolExecutor(
                new MockShopifyAfterSalesConnector(),
                new DemoAfterSalesPolicyCatalogService(),
                new CompensationRuleService(),
                proposalRepository,
                attachmentRepository,
                objectMapper);
        AfterSalesAgentLoopService service = new AfterSalesAgentLoopService(
                toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                attachmentRepository, intakeService(), plannerService(), new DecisionRouteResolver(), new AfterSalesEscalationPolicyService(), objectMapper, 0L);

        when(runRepository.findById("run-id-4001")).thenReturn(Optional.of(run()));
        AfterSalesTicketEntity ticket = ticket();
        when(ticketContextService.load("ticket-id-4001"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(proposalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service.run("run-id-4001", "ticket-id-4001");

        // 回归核心：run 以提案创建完成，没有 POLICY_NOT_FOUND 错误事件。
        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        // 决策路线（补偿评估）进入 decision_completed 事件。
        assertThat(appended).anyMatch(event -> "decision_completed".equals(event.type())
                && "COMPENSATION_EVALUATION".equals(event.data().get("route")));
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository, atLeastOnce()).save(runCaptor.capture());
        AfterSalesRunEntity completedRun = runCaptor.getAllValues().stream()
                .filter(item -> "COMPLETED".equals(item.getStatus()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("COMPLETED run not saved; captured: "
                        + runCaptor.getAllValues().stream().map(AfterSalesRunEntity::getStatus).toList()));
        assertThat(completedRun.getStopReason()).isEqualTo("ACTION_PROPOSAL_CREATED");
        assertThat(completedRun.getStepCount()).isEqualTo(5);

        // 检索事件携带印尼政策证据 ID（稳定、含 policy ID + 版本 + 章节）。
        appended.stream()
                .filter(event -> "retrieval_completed".equals(event.type()))
                .forEach(event -> assertThat(event.data().get("evidenceIds"))
                        .asList().contains("policy:ID_SHIPMENT_DELAY:v2#section-4.2"));

        // 提案按印尼政策持久化：确定性金额 241000.00 IDR（2410000.00 × 10%，低于 250000 上限）、决策摘要国家中立。
        ArgumentCaptor<ActionProposalEntity> proposalCaptor = ArgumentCaptor.forClass(ActionProposalEntity.class);
        verify(proposalRepository).save(proposalCaptor.capture());
        ActionProposalEntity proposal = proposalCaptor.getValue();
        assertThat(proposal.getActionType()).isEqualTo("DELAY_COMPENSATION_COUPON");
        assertThat(proposal.getAmount()).isEqualByComparingTo("241000.00");
        assertThat(proposal.getCurrency()).isEqualTo("IDR");
        assertThat(proposal.getPolicyVersion()).isEqualTo("v2");
        assertThat(proposal.getDecisionSummary()).doesNotContain("Vietnam");
        assertThat(proposal.getEvidenceIdsJson()).contains("policy:ID_SHIPMENT_DELAY:v2#section-4.2");

        // 工单进入待人工审批（执行边界保留：Agent 不执行副作用）。
        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository, atLeastOnce()).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getAllValues().stream()
                .map(AfterSalesTicketEntity::getStatus)
                .toList()).contains(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);
    }

    /** RULES 模式 Intake：不发网络请求，分类结果确定。 */
    private AfterSalesIntakeService intakeService() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesIntakeService(builder, objectMapper, "RULES", 1000, "your_api_key_here");
    }

    /** RULES 模式 Planner：不发网络请求，确定性规划。 */
    private AfterSalesEvidencePlannerService plannerService() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesEvidencePlannerService(builder, objectMapper, "RULES", 1000, "your_api_key_here");
    }

    private AfterSalesRunEntity run() {
        return AfterSalesRunEntity.builder()
                .id("run-id-4001")
                .ticketId("ticket-id-4001")
                .status("RUNNING")
                .maxSteps(6)
                .stepCount(0)
                .startedAt(Instant.now())
                .build();
    }

    private AfterSalesTicketEntity ticket() {
        return AfterSalesTicketEntity.builder()
                .id("ticket-id-4001")
                .ticketNo("AS-4001")
                .orderId("O-ID-4001")
                .issueType("SHIPMENT_DELAY")
                // 显式退款/赔偿词 → REQUEST_REFUND → COMPENSATION_EVALUATION 路线（走完整补偿管线）。
                .customerMessage("my parcel from Indonesia has not moved in days, 请退款赔偿")
                .status(AfterSalesTypes.TicketStatus.ANALYZING)
                .currentRunId("run-id-4001")
                .createdAt(Instant.parse("2026-08-01T00:00:00Z"))
                .build();
    }
}
