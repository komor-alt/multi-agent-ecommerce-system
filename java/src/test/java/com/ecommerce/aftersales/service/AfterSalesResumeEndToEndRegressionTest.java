package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
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
 * DAMAGED_ITEM 完整端到端回归（真实 Connector + 政策目录 + 工具执行器，RULES 模式）：
 * 客户报告破损 → run 1 收集 ORDER+DELIVERY 后因缺照片进入 WAITING_CUSTOMER（REQUEST_MORE_INFO）；
 * 客户补充照片附件后 run 2（parentRunId = run 1）恢复父快照，继续 DAMAGE_PHOTO → PRODUCT →
 * POLICY → 补偿计算 → 方案创建。全程不重复已收集工具，照片证据来自持久化附件元数据。
 */
class AfterSalesResumeEndToEndRegressionTest {

    private record CapturedEvent(String runId, String type, Map<String, Object> data) {
    }

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    void damagedItemResumeFlowEndToEnd() {
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
        ActionProposalRepository proposalRepository = mock(ActionProposalRepository.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        AfterSalesTicketContextService ticketContextService = mock(AfterSalesTicketContextService.class);
        List<CapturedEvent> appended = new ArrayList<>();
        doAnswer(invocation -> {
            appended.add(new CapturedEvent(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(5)));
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

        // 工单：O-SG-1001（新加坡破损订单），分类为 DAMAGED_ITEM + REQUEST_REFUND → 补偿评估路线。
        AfterSalesTicketEntity ticket = AfterSalesTicketEntity.builder()
                .id("ticket-1")
                .ticketNo("AS-1")
                .orderId("O-SG-1001")
                .issueType("SHIPMENT_DELAY")
                .customerMessage("包裹破损，请退款")
                .status(AfterSalesTypes.TicketStatus.ANALYZING)
                .currentRunId("run-1")
                .createdAt(Instant.parse("2026-08-01T00:00:00Z"))
                .build();
        when(ticketContextService.load("ticket-1"))
                .thenReturn(new AfterSalesTicketContextService.TicketContext(ticket, ticket.getCustomerMessage()));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(proposalRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // ============ run 1：无照片附件 → REQUEST_MORE_INFO ============
        AfterSalesRunEntity run1 = AfterSalesRunEntity.builder()
                .id("run-1")
                .ticketId("ticket-1")
                .status("RUNNING")
                .maxSteps(8)
                .stepCount(0)
                .startedAt(Instant.now())
                .build();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run1));
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-1")).thenReturn(List.of());

        service.run("run-1", "ticket-1");

        assertThat(appended).noneMatch(event -> "error".equals(event.type()));
        assertThat(run1.getStatus()).isEqualTo("WAITING_CUSTOMER");
        assertThat(run1.getStopReason()).isEqualTo("CUSTOMER_INFO_REQUIRED");
        assertThat(run1.getStepCount()).isEqualTo(2);
        assertThat(ticket.getStatus()).isEqualTo(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        assertThat(run1.getResumeStateJson()).isNotBlank();
        // 照片缺失：无方案、无金额计算。
        verify(proposalRepository, org.mockito.Mockito.never()).save(any());

        // ============ 客户补充照片附件（服务端核验结论 VERIFIED 的持久化元数据）============
        TicketAttachmentEntity photo = TicketAttachmentEntity.builder()
                .id("att-photo-1")
                .ticketId("ticket-1")
                .messageId("message-2")
                .fileName("damage.jpg")
                .contentType("image/jpeg")
                .storageKey("objects/att-photo-1")
                .metadataJson("{\"width\":1080,\"height\":1440,\"sizeBytes\":1240000,"
                        + "\"reviewStatus\":\"VERIFIED\",\"reviewSummary\":\"Manual review confirmed visible damage.\"}")
                .createdAt(Instant.now())
                .build();
        when(attachmentRepository.findByTicketIdOrderByCreatedAtDesc("ticket-1")).thenReturn(List.of(photo));

        // ============ run 2：恢复父会话继续取证 ============
        AfterSalesRunEntity run2 = AfterSalesRunEntity.builder()
                .id("run-2")
                .ticketId("ticket-1")
                .parentRunId("run-1")
                .status("RUNNING")
                .maxSteps(8)
                .stepCount(0)
                .startedAt(Instant.now())
                .build();
        when(runRepository.findById("run-2")).thenReturn(Optional.of(run2));
        ticket.setCurrentRunId("run-2");

        service.run("run-2", "ticket-1");

        // 恢复事件：结构化 parentRunId + 已还原证据。
        List<CapturedEvent> resumed = appended.stream()
                .filter(event -> "run-2".equals(event.runId()) && "run_resumed".equals(event.type()))
                .toList();
        assertThat(resumed).hasSize(1);
        assertThat(resumed.get(0).data()).containsEntry("parentRunId", "run-1");
        @SuppressWarnings("unchecked")
        List<String> restoredEvidenceIds = (List<String>) resumed.get(0).data().get("restoredEvidenceIds");
        assertThat(restoredEvidenceIds).hasSize(2);
        assertThat(restoredEvidenceIds.get(0)).isEqualTo("order:O-SG-1001:v1");
        assertThat(restoredEvidenceIds.get(1)).startsWith("delivery:O-SG-1001:");

        // 不重复父 run 已收集的 ORDER / DELIVERY：run 2 只执行照片之后的新工具。
        List<String> run2Tools = appended.stream()
                .filter(event -> "run-2".equals(event.runId()))
                .filter(event -> "tool_completed".equals(event.type()) || "retrieval_completed".equals(event.type()))
                .map(event -> (String) event.data().get("action"))
                .toList();
        assertThat(run2Tools).containsExactly(
                AfterSalesToolExecutor.GET_DAMAGE_PHOTO,
                AfterSalesToolExecutor.GET_PRODUCT,
                AfterSalesToolExecutor.SEARCH_POLICY,
                AfterSalesToolExecutor.CALCULATE_COMPENSATION,
                AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL);

        // 照片证据由服务端从持久化附件元数据派生。
        CapturedEvent photoEvent = appended.stream()
                .filter(event -> "run-2".equals(event.runId()))
                .filter(event -> "retrieval_completed".equals(event.type()))
                .filter(event -> AfterSalesToolExecutor.GET_DAMAGE_PHOTO.equals(event.data().get("action")))
                .findFirst()
                .orElseThrow();
        assertThat(photoEvent.data().get("evidenceIds")).isEqualTo(List.of("damage-photo:att-photo-1:v1"));

        // 终态：方案待审批，金额/政策由真实规则计算，证据链含父 run 还原的证据 ID。
        assertThat(run2.getStatus()).isEqualTo("COMPLETED");
        assertThat(run2.getStopReason()).isEqualTo("ACTION_PROPOSAL_CREATED");
        assertThat(run2.getStepCount()).isEqualTo(5);
        assertThat(ticket.getStatus()).isEqualTo(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);
        ArgumentCaptor<ActionProposalEntity> proposalCaptor = ArgumentCaptor.forClass(ActionProposalEntity.class);
        verify(proposalRepository).save(proposalCaptor.capture());
        ActionProposalEntity proposal = proposalCaptor.getValue();
        assertThat(proposal.getActionType()).isEqualTo("DAMAGE_COMPENSATION_COUPON");
        assertThat(proposal.getAmount()).isEqualByComparingTo("25.00");
        assertThat(proposal.getCurrency()).isEqualTo("SGD");
        assertThat(proposal.getPolicyVersion()).isEqualTo("v2");
        assertThat(proposal.getEvidenceIdsJson())
                .contains("order:O-SG-1001:v1")
                .contains("damage-photo:att-photo-1:v1")
                .contains("product:P001:v1")
                .contains("policy:SG_DAMAGED_ITEM:v2#section-6.3")
                .doesNotContain("shipment:");
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
}
