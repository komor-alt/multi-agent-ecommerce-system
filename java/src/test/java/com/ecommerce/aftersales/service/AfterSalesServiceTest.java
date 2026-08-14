package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.ApprovalRecordRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
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
    private final AfterSalesAgentLoopService agentLoopService = mock(AfterSalesAgentLoopService.class);
    private final AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
    private final ApprovalService approvalService = mock(ApprovalService.class);
    private final ExecutionService executionService = mock(ExecutionService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Executor agentExecutor = mock(Executor.class);

    private AfterSalesService service() {
        return new AfterSalesService(
                ticketRepository, runRepository, proposalRepository, approvalRecordRepository,
                executionJobRepository, agentLoopService, eventService, approvalService,
                executionService, objectMapper, agentExecutor);
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
