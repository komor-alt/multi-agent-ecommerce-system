package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.ApprovalRecordRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
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
    void startOnMissingRunThrows() {
        when(runRepository.claimReady(eq("nope"), any())).thenReturn(0);
        when(runRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().start("nope"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("AGENT_RUN_NOT_FOUND");
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
}
