package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import com.ecommerce.config.AfterSalesExecutionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExecutionServiceTest {

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void cancelsApprovedJobWhenOrderWasRefundedBeforeConnectorSideEffect() {
        ExecutionJobRepository jobRepository = mock(ExecutionJobRepository.class);
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        MockShopifyAfterSalesConnector connector = mock(MockShopifyAfterSalesConnector.class);
        AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        TransactionStatus transactionStatus = mock(TransactionStatus.class);

        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        ExecutionJobEntity job = ExecutionJobEntity.builder()
                .id("job-1")
                .proposalId("proposal-1")
                .ticketId("ticket-1")
                .idempotencyKey("0123456789abcdef")
                .actionType("ISSUE_VOUCHER")
                .amount(new BigDecimal("10.00"))
                .currency("VND")
                .status(AfterSalesTypes.ExecutionStatus.PENDING)
                .attemptCount(0)
                .build();
        AfterSalesTicketEntity ticket = AfterSalesTicketEntity.builder()
                .id("ticket-1")
                .ticketNo("AS-1")
                .orderId("O-VN-5002")
                .issueType("SHIPMENT_DELAY")
                .customerMessage("parcel delayed")
                .status(AfterSalesTypes.TicketStatus.PENDING_APPROVAL)
                .currentRunId("run-1")
                .build();
        AfterSalesTypes.OrderSnapshot refundedOrder = new AfterSalesTypes.OrderSnapshot(
                "O-VN-5002",
                "U-1",
                "shopify",
                "VN",
                "VND",
                "VN-SOUTH",
                new BigDecimal("100.00"),
                true,
                "IN_TRANSIT",
                5,
                "SF-VN-5002",
                "REFUNDED"
        );

        when(jobRepository.findByIdForUpdate("job-1")).thenReturn(Optional.of(job));
        when(ticketRepository.findById("ticket-1")).thenReturn(Optional.of(ticket));
        when(connector.getOrder("O-VN-5002")).thenReturn(refundedOrder);
        AfterSalesExecutionProperties properties = new AfterSalesExecutionProperties();
        properties.setWorkerId("test-worker");

        ExecutionService service = new ExecutionService(
                jobRepository,
                ticketRepository,
                connector,
                new ExecutionPreconditionGate(),
                eventService,
                new ObjectMapper(),
                transactionTemplate,
                properties,
                Runnable::run,
                mock(ExecutionApprovalValidator.class)
        );

        service.executeAsync("job-1");
        // 模拟异步消息重复投递：CANCELLED 是终态，任务不能被重新置为 RUNNING。
        service.executeAsync("job-1");

        assertThat(job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.CANCELLED);
        assertThat(job.getLastError()).isEqualTo(ExecutionPreconditionGate.ORDER_ALREADY_REFUNDED);
        assertThat(job.getNextRetryAt()).isNull();
        assertThat(job.getLeaseOwner()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(ticket.getStatus()).isEqualTo(AfterSalesTypes.TicketStatus.RESOLVED);
        verify(connector, times(1)).getOrder("O-VN-5002");
        verify(connector, never()).issueDelayCoupon(any(), any(), anyString());
        verify(eventService, times(1)).append(
                eq("run-1"),
                eq("execution_cancelled"),
                eq("补偿执行已取消"),
                eq("warning"),
                eq(ExecutionPreconditionGate.ORDER_ALREADY_REFUNDED),
                any()
        );
        verify(eventService).complete("run-1");
    }

    @Test
    void databaseClaimPreventsDuplicateQueueSubmission() {
        Fixture fixture = new Fixture();
        ExecutionJobEntity job = fixture.job(AfterSalesTypes.ExecutionStatus.PENDING, 0);
        fixture.stubJobAndTicket(job);
        List<Runnable> queuedTasks = new ArrayList<>();
        ExecutionService service = fixture.service(queuedTasks::add);

        service.executeAsync(job.getId());
        service.executeAsync(job.getId());

        assertThat(queuedTasks).hasSize(1);
        assertThat(job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.RUNNING);
        assertThat(job.getAttemptCount()).isEqualTo(1);
        assertThat(job.getLeaseOwner()).isEqualTo("test-worker");
        assertThat(job.getLeaseUntil()).isAfter(Instant.now());
        verify(fixture.connector, never()).issueDelayCoupon(any(), any(), anyString());
    }

    @Test
    void executorRejectionReturnsClaimedJobToPersistentRetryQueueWithoutBurningAttempt() {
        Fixture fixture = new Fixture();
        fixture.properties.setRetryDelayMs(2_000L);
        ExecutionJobEntity job = fixture.job(AfterSalesTypes.ExecutionStatus.PENDING, 0);
        fixture.stubJobAndTicket(job);
        Executor rejectingExecutor = command -> {
            throw new RejectedExecutionException("queue full");
        };

        fixture.service(rejectingExecutor).executeAsync(job.getId());

        assertThat(job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.RETRY_WAIT);
        assertThat(job.getAttemptCount()).isZero();
        assertThat(job.getLastError()).isEqualTo("EXECUTION_EXECUTOR_SATURATED");
        assertThat(job.getNextRetryAt()).isAfter(Instant.now());
        assertThat(job.getLeaseOwner()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        verify(fixture.connector, never()).getOrder(anyString());
    }

    @Test
    void expiredRunningLeaseIsRecoveredForRetry() {
        Fixture fixture = new Fixture();
        ExecutionJobEntity job = fixture.job(AfterSalesTypes.ExecutionStatus.RUNNING, 1);
        job.setLeaseOwner("dead-worker");
        job.setLeaseUntil(Instant.now().minusSeconds(1));
        fixture.stubJobAndTicket(job);
        when(fixture.jobRepository.findExpiredLeases(
                eq(AfterSalesTypes.ExecutionStatus.RUNNING), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of(job));
        when(fixture.jobRepository.findDispatchable(
                any(AfterSalesTypes.ExecutionStatus.class), any(Instant.class), any(Pageable.class)))
                .thenReturn(List.of());

        fixture.service(Runnable::run).retryDueJobs();

        assertThat(job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.RETRY_WAIT);
        assertThat(job.getLastError()).isEqualTo("EXECUTION_LEASE_EXPIRED");
        assertThat(job.getNextRetryAt()).isNotNull();
        assertThat(job.getLeaseOwner()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        verify(fixture.eventService).append(
                eq("run-1"),
                eq("execution_lease_recovered"),
                eq("执行任务租约已恢复"),
                eq("warning"),
                eq("EXECUTION_LEASE_EXPIRED"),
                any()
        );
    }

    private static final class Fixture {
        private final ExecutionJobRepository jobRepository = mock(ExecutionJobRepository.class);
        private final AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        private final MockShopifyAfterSalesConnector connector = mock(MockShopifyAfterSalesConnector.class);
        private final AfterSalesRunEventService eventService = mock(AfterSalesRunEventService.class);
        private final TransactionTemplate transactionTemplate = immediateTransactionTemplate();
        private final AfterSalesExecutionProperties properties = new AfterSalesExecutionProperties();
        private final AfterSalesTicketEntity ticket = AfterSalesTicketEntity.builder()
                .id("ticket-1")
                .ticketNo("AS-1")
                .orderId("O-VN-5002")
                .issueType("SHIPMENT_DELAY")
                .customerMessage("parcel delayed")
                .status(AfterSalesTypes.TicketStatus.PENDING_APPROVAL)
                .currentRunId("run-1")
                .build();

        private Fixture() {
            properties.setWorkerId("test-worker");
            properties.setLeaseDurationMs(60_000L);
        }

        private ExecutionJobEntity job(AfterSalesTypes.ExecutionStatus status, int attemptCount) {
            return ExecutionJobEntity.builder()
                    .id("job-1")
                    .proposalId("proposal-1")
                    .ticketId("ticket-1")
                    .idempotencyKey("0123456789abcdef")
                    .actionType("ISSUE_VOUCHER")
                    .amount(new BigDecimal("10.00"))
                    .currency("VND")
                    .status(status)
                    .attemptCount(attemptCount)
                    .build();
        }

        private void stubJobAndTicket(ExecutionJobEntity job) {
            when(jobRepository.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
            when(ticketRepository.findById(ticket.getId())).thenReturn(Optional.of(ticket));
        }

        private ExecutionService service(Executor executor) {
            return new ExecutionService(
                    jobRepository,
                    ticketRepository,
                    connector,
                    new ExecutionPreconditionGate(),
                    eventService,
                    new ObjectMapper(),
                    transactionTemplate,
                    properties,
                    executor,
                    mock(ExecutionApprovalValidator.class)
            );
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static TransactionTemplate immediateTransactionTemplate() {
        TransactionTemplate template = mock(TransactionTemplate.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(template.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback callback = invocation.getArgument(0);
            return callback.doInTransaction(status);
        });
        return template;
    }
}
