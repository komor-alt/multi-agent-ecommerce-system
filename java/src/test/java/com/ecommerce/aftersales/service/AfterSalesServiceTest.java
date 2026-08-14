package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
import com.ecommerce.aftersales.entity.TicketMessageEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.ApprovalRecordRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import com.ecommerce.aftersales.repository.TicketAttachmentRepository;
import com.ecommerce.aftersales.repository.TicketMessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AfterSalesServiceTest {

    private final AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
    private final AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
    private final ActionProposalRepository proposalRepository = mock(ActionProposalRepository.class);
    private final ApprovalRecordRepository approvalRecordRepository = mock(ApprovalRecordRepository.class);
    private final ExecutionJobRepository executionJobRepository = mock(ExecutionJobRepository.class);
    private final TicketMessageRepository messageRepository = mock(TicketMessageRepository.class);
    private final TicketAttachmentRepository attachmentRepository = mock(TicketAttachmentRepository.class);
    private final AfterSalesAgentLoopService agentLoopService = mock(AfterSalesAgentLoopService.class);
    private final AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final ExecutionService executionService = mock(ExecutionService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Executor agentExecutor = mock(Executor.class);

    private AfterSalesService service() {
        return new AfterSalesService(
                ticketRepository, runRepository, proposalRepository, approvalRecordRepository,
                executionJobRepository, messageRepository, attachmentRepository, agentLoopService,
                eventService, approvalService, executionService, objectMapper, agentExecutor);
    }

    @Test
    void deferredAnalyzePersistsReadyRunWithoutStartingAgentOrEvents() {
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket(AfterSalesTypes.TicketStatus.OPEN)));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> response = service().analyze("ticket-1", true);

        assertThat(response.get("status")).isEqualTo("ANALYZING");
        assertThat(response.get("runStatus")).isEqualTo("READY");
        assertThat(response.get("runId")).isNotNull();
        assertThat(response.get("streamUrl")).isNotNull();

        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository).save(runCaptor.capture());
        AfterSalesRunEntity savedRun = runCaptor.getValue();
        assertThat(savedRun.getStatus()).isEqualTo("READY");
        assertThat(savedRun.getStartedAt()).isNull();
        assertThat(savedRun.getTicketId()).isEqualTo("ticket-1");

        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository).save(ticketCaptor.capture());
        assertThat(ticketCaptor.getValue().getStatus()).isEqualTo(AfterSalesTypes.TicketStatus.ANALYZING);
        assertThat(ticketCaptor.getValue().getCurrentRunId()).isEqualTo(savedRun.getId());

        // 不得启动 Agent、不得生成任何事件（run_started / 工具事件都没有）。
        verify(agentExecutor, never()).execute(any());
        verify(eventService, never()).append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void immediateAnalyzeRemainsBackwardCompatibleAndStartsAgent() {
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket(AfterSalesTypes.TicketStatus.OPEN)));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> response = service().analyze("ticket-1", false);

        assertThat(response.get("status")).isEqualTo("ANALYZING");
        assertThat(response.get("runStatus")).isEqualTo("RUNNING");

        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(runCaptor.getValue().getStartedAt()).isNotNull();

        // 立即模式照旧异步启动。
        verify(agentExecutor).execute(any());
    }

    @Test
    void analyzeOnAlreadyAnalyzingTicketReturnsExistingRunWithoutStarting() {
        AfterSalesTicketEntity analyzing = ticket(AfterSalesTypes.TicketStatus.ANALYZING);
        analyzing.setCurrentRunId("run-existing");
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(analyzing));

        Map<String, Object> response = service().analyze("ticket-1", true);

        assertThat(response.get("runId")).isEqualTo("run-existing");
        verify(runRepository, never()).save(any());
        verify(agentExecutor, never()).execute(any());
    }

    @Test
    void startClaimsReadyRunWritesStartedAtAndSubmitsAgent() {
        // 认领成功后 runStatus 读取的是已提交的 RUNNING 状态（模拟 claimReady 提交后的数据库视图）。
        AfterSalesRunEntity claimed = AfterSalesRunEntity.builder()
                .id("run-1")
                .ticketId("ticket-1")
                .status("RUNNING")
                .maxSteps(6)
                .stepCount(0)
                .startedAt(Instant.now())
                .build();
        when(runRepository.claimReady(eq("run-1"), any())).thenReturn(1);
        when(runRepository.findById("run-1")).thenReturn(Optional.of(claimed));

        Map<String, Object> response = service().start("run-1");

        // startedAt 在认领启动时写入，而不是 prepare 时。
        ArgumentCaptor<Instant> startedAtCaptor = ArgumentCaptor.forClass(Instant.class);
        verify(runRepository).claimReady(eq("run-1"), startedAtCaptor.capture());
        assertThat(startedAtCaptor.getValue()).isNotNull();
        assertThat(startedAtCaptor.getValue()).isBeforeOrEqualTo(Instant.now());

        verify(agentExecutor).execute(any());
        assertThat(response.get("runId")).isEqualTo("run-1");
        assertThat(response.get("status")).isEqualTo("RUNNING");
        assertThat(response.get("startedAt")).isNotNull();
    }

    @Test
    void startIsAtMostOnceWhenClaimFails() {
        // claimReady 返回 0：并发/重复调用中没有拿到认领权，不得再次启动。
        AfterSalesRunEntity running = AfterSalesRunEntity.builder()
                .id("run-1")
                .ticketId("ticket-1")
                .status("RUNNING")
                .maxSteps(6)
                .stepCount(0)
                .startedAt(Instant.now())
                .build();
        when(runRepository.claimReady(eq("run-1"), any())).thenReturn(0);
        when(runRepository.findById("run-1")).thenReturn(Optional.of(running));

        Map<String, Object> response = service().start("run-1");

        assertThat(response.get("status")).isEqualTo("RUNNING");
        assertThat(response.get("startedAt")).isNotNull();
        verify(agentExecutor, never()).execute(any());
    }

    @Test
    void startOnTerminalRunsReturnsStructuredStatusWithoutRestarting() {
        AfterSalesRunEntity completed = AfterSalesRunEntity.builder()
                .id("run-1")
                .ticketId("ticket-1")
                .status("COMPLETED")
                .maxSteps(6)
                .stepCount(5)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .durationMs(123L)
                .build();
        when(runRepository.claimReady(eq("run-1"), any())).thenReturn(0);
        when(runRepository.findById("run-1")).thenReturn(Optional.of(completed));

        Map<String, Object> completedResponse = service().start("run-1");
        assertThat(completedResponse.get("status")).isEqualTo("COMPLETED");
        assertThat(completedResponse.get("durationMs")).isEqualTo(123L);

        AfterSalesRunEntity failed = AfterSalesRunEntity.builder()
                .id("run-1")
                .ticketId("ticket-1")
                .status("FAILED")
                .maxSteps(6)
                .stepCount(0)
                .build();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(failed));
        assertThat(service().start("run-1").get("status")).isEqualTo("FAILED");

        verify(agentExecutor, never()).execute(any());
    }

    @Test
    void listBatchLoadsExecutionStatusWithoutNPlusOne() {
        AfterSalesTicketEntity ticket1 = ticket(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);
        AfterSalesTicketEntity ticket2 = ticket(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);
        ticket2.setId("ticket-2");
        when(ticketRepository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of(ticket1, ticket2));

        ExecutionJobEntity retryWaiting = ExecutionJobEntity.builder()
                .id("job-1")
                .ticketId("ticket-1")
                .proposalId("proposal-1")
                .idempotencyKey("key-1")
                .actionType("ISSUE_VOUCHER")
                .amount(new BigDecimal("10.00"))
                .currency("VND")
                .status(AfterSalesTypes.ExecutionStatus.RETRY_WAIT)
                .attemptCount(2)
                .build();
        when(executionJobRepository.findByTicketIdIn(List.of("ticket-1", "ticket-2"))).thenReturn(List.of(retryWaiting));

        List<Map<String, Object>> items = service().list();

        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("executionStatus", "RETRY_WAIT");
        // 无执行任务的工单不输出该字段（可选字段）。
        assertThat(items.get(1)).doesNotContainKey("executionStatus");
        // 一次批量查询，不逐工单查询。
        verify(executionJobRepository).findByTicketIdIn(List.of("ticket-1", "ticket-2"));
        verify(executionJobRepository, never()).findByProposalId(anyString());
    }

    @Test
    void listWithNoTicketsSkipsExecutionJobBatchQuery() {
        when(ticketRepository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of());

        assertThat(service().list()).isEmpty();
        // 空列表时不发起 findByTicketIdIn(empty)。
        verify(executionJobRepository, never()).findByTicketIdIn(any());
    }

    @Test
    void listReportsDeadLetterAndSucceededExecutionStatuses() {
        AfterSalesTicketEntity deadLetter = ticket(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);
        AfterSalesTicketEntity succeeded = ticket(AfterSalesTypes.TicketStatus.RESOLVED);
        succeeded.setId("ticket-2");
        when(ticketRepository.findTop20ByOrderByCreatedAtDesc()).thenReturn(List.of(deadLetter, succeeded));
        when(executionJobRepository.findByTicketIdIn(any())).thenReturn(List.of(
                executionJob("job-1", "ticket-1", AfterSalesTypes.ExecutionStatus.DEAD_LETTER),
                executionJob("job-2", "ticket-2", AfterSalesTypes.ExecutionStatus.SUCCEEDED)));

        List<Map<String, Object>> items = service().list();

        assertThat(items.get(0)).containsEntry("executionStatus", "DEAD_LETTER");
        assertThat(items.get(1)).containsEntry("executionStatus", "SUCCEEDED");
    }

    @Test
    void startOnMissingRunThrows() {
        when(runRepository.claimReady(eq("nope"), any())).thenReturn(0);
        when(runRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().start("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AGENT_RUN_NOT_FOUND");
    }

    @Test
    void approveWritesTrustedOperatorIntoEventAndForwardsToApprovalService() {
        AfterSalesTicketEntity ticket = ticket(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);
        ticket.setCurrentRunId("run-1");
        ExecutionJobEntity job = executionJob("job-1", "ticket-1", AfterSalesTypes.ExecutionStatus.PENDING);
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket));
        when(approvalService.approve("proposal-1", "operator-vn-01", "approved"))
                .thenReturn(new ApprovalService.ApprovalOutcome(job, true));

        Map<String, Object> response = service().approve("proposal-1",
                OperatorContext.fromTrustedGatewayHeader("operator-vn-01"), "approved");

        assertThat(response.get("id")).isEqualTo("job-1");
        verify(approvalService).approve("proposal-1", "operator-vn-01", "approved");
        verify(executionService).executeAsync("job-1");
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-1"), eq("approval_recorded"), eq("人工审批通过"), eq("success"),
                anyString(), dataCaptor.capture());
        // 事件里的审批人只能是可信 Gateway 身份，不可能是 body/其他来源。
        assertThat(dataCaptor.getValue()).containsEntry("operatorId", "operator-vn-01");
        assertThat(dataCaptor.getValue()).containsEntry("decision", "APPROVED");
    }

    @Test
    void rejectWritesTrustedOperatorIntoEventAndCompletesRun() {
        AfterSalesTicketEntity ticket = ticket(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);
        ticket.setCurrentRunId("run-1");
        ActionProposalEntity proposal = ActionProposalEntity.builder()
                .id("proposal-1")
                .ticketId("ticket-1")
                .status(AfterSalesTypes.ProposalStatus.REJECTED)
                .build();
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(approvalService.reject("proposal-1", "operator-vn-01", "rejected")).thenReturn(proposal);

        Map<String, Object> response = service().reject("proposal-1",
                OperatorContext.fromTrustedGatewayHeader("operator-vn-01"), "rejected");

        assertThat(response.get("status")).isEqualTo("REJECTED");
        assertThat(ticket.getStatus()).isEqualTo(AfterSalesTypes.TicketStatus.RESOLVED);
        verify(ticketRepository).save(ticket);
        verify(approvalService).reject("proposal-1", "operator-vn-01", "rejected");
        ArgumentCaptor<Map<String, Object>> dataCaptor = ArgumentCaptor.forClass(Map.class);
        verify(eventService).append(eq("run-1"), eq("approval_recorded"), eq("人工驳回方案"), eq("success"),
                anyString(), dataCaptor.capture());
        assertThat(dataCaptor.getValue()).containsEntry("operatorId", "operator-vn-01");
        assertThat(dataCaptor.getValue()).containsEntry("decision", "REJECTED");
        verify(eventService).complete("run-1");
    }

    @Test
    void createPersistsInitialCustomerMessageWithCustomerRole() {
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        service().create("O-SG-1001", "  my parcel arrived damaged  ");

        ArgumentCaptor<AfterSalesTicketEntity> ticketCaptor = ArgumentCaptor.forClass(AfterSalesTicketEntity.class);
        verify(ticketRepository).save(ticketCaptor.capture());
        AfterSalesTicketEntity saved = ticketCaptor.getValue();
        assertThat(saved.getCustomerMessage()).isEqualTo("my parcel arrived damaged");

        ArgumentCaptor<TicketMessageEntity> messageCaptor = ArgumentCaptor.forClass(TicketMessageEntity.class);
        verify(messageRepository).save(messageCaptor.capture());
        TicketMessageEntity message = messageCaptor.getValue();
        assertThat(message.getRole()).isEqualTo(AfterSalesTypes.MessageRole.CUSTOMER);
        assertThat(message.getContent()).isEqualTo("my parcel arrived damaged");
        assertThat(message.getTicketId()).isEqualTo(saved.getId());
        assertThat(message.getRunId()).isNull();
    }

    @Test
    void detailReturnsOrderedMessagesWithTheirAttachments() {
        AfterSalesTicketEntity ticket = ticket(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        ticket.setCurrentRunId(null);
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket));
        TicketMessageEntity initial = TicketMessageEntity.builder()
                .id("msg-1")
                .ticketId("ticket-1")
                .role(AfterSalesTypes.MessageRole.CUSTOMER)
                .content("my parcel arrived damaged")
                .createdAt(Instant.parse("2026-08-01T01:00:00Z"))
                .build();
        TicketMessageEntity followUp = TicketMessageEntity.builder()
                .id("msg-2")
                .ticketId("ticket-1")
                .runId("run-parent")
                .role(AfterSalesTypes.MessageRole.CUSTOMER)
                .content("photo attached")
                .createdAt(Instant.parse("2026-08-11T01:00:00Z"))
                .build();
        when(messageRepository.findByTicketIdOrderByCreatedAtAsc("ticket-1")).thenReturn(List.of(initial, followUp));
        TicketAttachmentEntity attachment = TicketAttachmentEntity.builder()
                .id("att-1")
                .ticketId("ticket-1")
                .messageId("msg-2")
                .fileName("damage.jpg")
                .contentType("image/jpeg")
                .storageKey("objects/att-1")
                .metadataJson("{\"width\":1080}")
                .createdAt(Instant.parse("2026-08-11T01:00:01Z"))
                .build();
        when(attachmentRepository.findByMessageIdOrderByCreatedAtAsc("msg-2")).thenReturn(List.of(attachment));

        Map<String, Object> result = service().detail("ticket-1");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> messages = (List<Map<String, Object>>) result.get("messages");
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).containsEntry("id", "msg-1");
        assertThat(messages.get(0)).containsEntry("role", "CUSTOMER");
        assertThat(messages.get(0)).doesNotContainKey("attachments");
        assertThat(messages.get(1)).containsEntry("runId", "run-parent");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) messages.get(1).get("attachments");
        assertThat(attachments).hasSize(1);
        assertThat(attachments.get(0)).containsEntry("fileName", "damage.jpg");
        assertThat(attachments.get(0)).containsEntry("contentType", "image/jpeg");
        assertThat(attachments.get(0).get("metadata")).isEqualTo(Map.of("width", 1080));
    }

    @Test
    void appendMessageOnWaitingCustomerPersistsDataAndCreatesDeferredResumedRun() {
        AfterSalesTicketEntity ticket = ticket(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        ticket.setCurrentRunId("run-parent");
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket));
        when(runRepository.findById("run-parent")).thenReturn(Optional.of(waitingParentRun()));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(attachmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> response = service().appendCustomerMessage("ticket-1", "photo attached",
                List.of(attachmentInput()), true);

        assertThat(response.get("status")).isEqualTo("ANALYZING");
        assertThat(response.get("runStatus")).isEqualTo("READY");

        // 客户消息 + 附件元数据落库（role CUSTOMER、runId 指向请求补充信息的父 run）。
        ArgumentCaptor<TicketMessageEntity> messageCaptor = ArgumentCaptor.forClass(TicketMessageEntity.class);
        verify(messageRepository).save(messageCaptor.capture());
        TicketMessageEntity message = messageCaptor.getValue();
        assertThat(message.getRole()).isEqualTo(AfterSalesTypes.MessageRole.CUSTOMER);
        assertThat(message.getContent()).isEqualTo("photo attached");
        assertThat(message.getRunId()).isEqualTo("run-parent");

        ArgumentCaptor<TicketAttachmentEntity> attachmentCaptor = ArgumentCaptor.forClass(TicketAttachmentEntity.class);
        verify(attachmentRepository).save(attachmentCaptor.capture());
        TicketAttachmentEntity attachment = attachmentCaptor.getValue();
        assertThat(attachment.getTicketId()).isEqualTo("ticket-1");
        assertThat(attachment.getMessageId()).isEqualTo(message.getId());
        assertThat(attachment.getFileName()).isEqualTo("damage.jpg");
        assertThat(attachment.getContentType()).isEqualTo("image/jpeg");
        assertThat(attachment.getStorageKey()).isEqualTo("objects/att-1");
        assertThat(attachment.getUrl()).isNull();
        assertThat(attachment.getMetadataJson()).contains("\"width\":1080");

        // 新 run 链接父 run（parentRunId），ticket 进入 ANALYZING 并指向新 run。
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository).save(runCaptor.capture());
        AfterSalesRunEntity newRun = runCaptor.getValue();
        assertThat(newRun.getId()).isNotEqualTo("run-parent");
        assertThat(newRun.getParentRunId()).isEqualTo("run-parent");
        assertThat(newRun.getStatus()).isEqualTo("READY");
        assertThat(newRun.getStartedAt()).isNull();
        assertThat(newRun.getMaxSteps()).isEqualTo(8);
        assertThat(ticket.getStatus()).isEqualTo(AfterSalesTypes.TicketStatus.ANALYZING);
        assertThat(ticket.getCurrentRunId()).isEqualTo(newRun.getId());
        assertThat(response.get("runId")).isEqualTo(newRun.getId());

        // deferred=true：不启动 Agent。
        verify(agentExecutor, never()).execute(any());
    }

    @Test
    void appendMessageImmediateStartsResumedRun() {
        AfterSalesTicketEntity ticket = ticket(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        ticket.setCurrentRunId("run-parent");
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket));
        when(runRepository.findById("run-parent")).thenReturn(Optional.of(waitingParentRun()));
        when(ticketRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(runRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(messageRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(attachmentRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        Map<String, Object> response = service().appendCustomerMessage("ticket-1", "photo attached",
                List.of(attachmentInput()), false);

        assertThat(response.get("runStatus")).isEqualTo("RUNNING");
        ArgumentCaptor<AfterSalesRunEntity> runCaptor = ArgumentCaptor.forClass(AfterSalesRunEntity.class);
        verify(runRepository).save(runCaptor.capture());
        assertThat(runCaptor.getValue().getStartedAt()).isNotNull();
        // 立即模式照旧异步启动 Agent。
        verify(agentExecutor).execute(any());
    }

    @Test
    void appendMessageOnNonWaitingStatusesRejectsUnsafeResume() {
        for (AfterSalesTypes.TicketStatus status : AfterSalesTypes.TicketStatus.values()) {
            if (status == AfterSalesTypes.TicketStatus.WAITING_CUSTOMER) {
                continue;
            }
            AfterSalesTicketEntity ticket = ticket(status);
            ticket.setCurrentRunId("run-parent");
            when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket));

            assertThatThrownBy(() -> service().appendCustomerMessage("ticket-1", "photo attached",
                    List.of(attachmentInput()), true))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("TICKET_NOT_RESUMABLE");
            // 非 WAITING_CUSTOMER：不落任何消息/附件、不创建 run。
            verify(messageRepository, never()).save(any());
            verify(attachmentRepository, never()).save(any());
            verify(runRepository, never()).save(any());
        }
    }

    @Test
    void appendMessageValidatesInputFailClosed() {
        AfterSalesTicketEntity ticket = ticket(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        ticket.setCurrentRunId("run-parent");
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket));
        when(runRepository.findById("run-parent")).thenReturn(Optional.of(waitingParentRun()));

        // 内容与附件都为空 → 拒绝。
        assertThatThrownBy(() -> service().appendCustomerMessage("ticket-1", "  ", List.of(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MESSAGE_OR_ATTACHMENT_REQUIRED");
        assertThatThrownBy(() -> service().appendCustomerMessage("ticket-1", null, null, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("MESSAGE_OR_ATTACHMENT_REQUIRED");
        // 附件缺文件名/类型/地址与存储键 → 拒绝。
        assertThatThrownBy(() -> service().appendCustomerMessage("ticket-1", "photo",
                List.of(new AfterSalesService.AttachmentInput(null, "image/jpeg", null, "objects/att-1",
                        null, null)), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ATTACHMENT_INVALID");
        assertThatThrownBy(() -> service().appendCustomerMessage("ticket-1", "photo",
                List.of(new AfterSalesService.AttachmentInput("damage.jpg", "image/jpeg", null, "  ",
                        null, null)), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ATTACHMENT_INVALID");
        // 破损照片证据只接受图片附件：非 image/* 类型拒绝。
        assertThatThrownBy(() -> service().appendCustomerMessage("ticket-1", "photo",
                List.of(new AfterSalesService.AttachmentInput("receipt.pdf", "application/pdf", null, "objects/att-1",
                        null, null)), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ATTACHMENT_CONTENT_TYPE_NOT_IMAGE");
        // 元数据只允许文件事实键；核验结论（reviewStatus）是服务端持有键，客户不能写入。
        assertThatThrownBy(() -> service().appendCustomerMessage("ticket-1", "photo",
                List.of(new AfterSalesService.AttachmentInput("damage.jpg", "image/jpeg", null, "objects/att-1",
                        null, Map.of("reviewStatus", "VERIFIED"))), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ATTACHMENT_METADATA_KEY_NOT_ALLOWED");
        assertThatThrownBy(() -> service().appendCustomerMessage("ticket-1", "photo",
                List.of(new AfterSalesService.AttachmentInput("damage.jpg", "image/jpeg", null, "objects/att-1",
                        null, Map.of("width", List.of(1)))), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("ATTACHMENT_METADATA_VALUE_INVALID");
        verify(messageRepository, never()).save(any());
        verify(runRepository, never()).save(any());
    }

    @Test
    void appendMessageRejectsParentWithoutResumableSnapshot() {
        AfterSalesTicketEntity ticket = ticket(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        ticket.setCurrentRunId("run-parent");
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket));
        // 父 run 缺失可恢复快照（或 stopReason 不是 CUSTOMER_INFO_REQUIRED）：拒绝不安全恢复。
        when(runRepository.findById("run-parent")).thenReturn(Optional.of(AfterSalesRunEntity.builder()
                .id("run-parent")
                .ticketId("ticket-1")
                .status("COMPLETED")
                .maxSteps(8)
                .stepCount(2)
                .stopReason("ANSWER_DELIVERED")
                .build()));

        assertThatThrownBy(() -> service().appendCustomerMessage("ticket-1", "photo attached",
                List.of(attachmentInput()), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("PARENT_RUN_NOT_RESUMABLE");
        verify(messageRepository, never()).save(any());
        verify(runRepository, never()).save(any());
    }

    private AfterSalesRunEntity waitingParentRun() {
        return AfterSalesRunEntity.builder()
                .id("run-parent")
                .ticketId("ticket-1")
                .status("WAITING_CUSTOMER")
                .maxSteps(8)
                .stepCount(2)
                .stopReason("CUSTOMER_INFO_REQUIRED")
                .completedAt(Instant.now())
                .resumeStateJson("{\"parentRunId\":\"run-parent\"}")
                .build();
    }

    private AfterSalesService.AttachmentInput attachmentInput() {
        return new AfterSalesService.AttachmentInput(
                "damage.jpg", "image/jpeg", null, "objects/att-1", "sha256:abc", Map.of("width", 1080));
    }

    private AfterSalesTicketEntity ticket(AfterSalesTypes.TicketStatus status) {
        return AfterSalesTicketEntity.builder()
                .id("ticket-1")
                .ticketNo("AS-1")
                .orderId("O-VN-5002")
                .issueType("SHIPMENT_DELAY")
                .customerMessage("my parcel is stuck")
                .status(status)
                .createdAt(Instant.now())
                .build();
    }

    private ExecutionJobEntity executionJob(String id, String ticketId, AfterSalesTypes.ExecutionStatus status) {
        return ExecutionJobEntity.builder()
                .id(id)
                .ticketId(ticketId)
                .proposalId("proposal-" + id)
                .idempotencyKey("key-" + id)
                .actionType("ISSUE_VOUCHER")
                .amount(new BigDecimal("10.00"))
                .currency("VND")
                .status(status)
                .attemptCount(1)
                .build();
    }
}
