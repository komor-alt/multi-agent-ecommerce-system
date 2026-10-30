package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.MockShopifyAfterSalesConnector;
import com.ecommerce.aftersales.service.ExecutionService;
import com.ecommerce.aftersales.service.ExecutionPreconditionGate;
import com.ecommerce.aftersales.service.ExecutionApprovalValidator;
import com.ecommerce.aftersales.service.AfterSalesRunEventService;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import com.ecommerce.config.AfterSalesExecutionProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class ExecutionJobClaimConcurrencyIntegrationTest {

    @Autowired
    private ExecutionJobRepository jobRepository;

    @Autowired
    private AfterSalesTicketRepository ticketRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void concurrentDispatchersClaimAndSubmitTheSameJobOnlyOnce() throws Exception {
        ticketRepository.save(AfterSalesTicketEntity.builder()
                .id("ticket-concurrent")
                .ticketNo("AS-CONCURRENT")
                .orderId("O-VN-5002")
                .issueType("SHIPMENT_DELAY")
                .customerMessage("parcel delayed")
                .status(AfterSalesTypes.TicketStatus.PENDING_APPROVAL)
                .currentRunId("run-concurrent")
                .build());
        jobRepository.save(ExecutionJobEntity.builder()
                .id("job-concurrent")
                .proposalId("proposal-concurrent")
                .ticketId("ticket-concurrent")
                .idempotencyKey("0123456789abcdef-concurrent")
                .actionType("ISSUE_VOUCHER")
                .amount(new BigDecimal("10.00"))
                .currency("VND")
                .status(AfterSalesTypes.ExecutionStatus.PENDING)
                .attemptCount(0)
                .build());

        AfterSalesExecutionProperties properties = new AfterSalesExecutionProperties();
        properties.setWorkerId("integration-worker");
        AtomicInteger submitted = new AtomicInteger();
        ExecutionService service = new ExecutionService(
                jobRepository,
                ticketRepository,
                mock(MockShopifyAfterSalesConnector.class),
                new ExecutionPreconditionGate(),
                mock(AfterSalesRunEventService.class),
                new ObjectMapper(),
                new TransactionTemplate(transactionManager),
                properties,
                ignored -> submitted.incrementAndGet(),
                mock(ExecutionApprovalValidator.class)
        );

        int callers = 24;
        ExecutorService callersPool = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(callersPool.submit(() -> {
                    ready.countDown();
                    start.await();
                    service.executeAsync("job-concurrent");
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            callersPool.shutdownNow();
        }

        ExecutionJobEntity persisted = jobRepository.findById("job-concurrent").orElseThrow();
        assertThat(submitted.get()).isEqualTo(1);
        assertThat(persisted.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.RUNNING);
        assertThat(persisted.getAttemptCount()).isEqualTo(1);
        assertThat(persisted.getLeaseOwner()).isEqualTo("integration-worker");
        assertThat(persisted.getLeaseUntil()).isNotNull();
    }

    @Test
    void restartedWorkerRecoversExpiredLeaseAndOldAttemptCannotPersistItsResult() throws Exception {
        ticketRepository.save(AfterSalesTicketEntity.builder()
                .id("ticket-restart")
                .ticketNo("AS-RESTART")
                .orderId("O-VN-5002")
                .issueType("SHIPMENT_DELAY")
                .customerMessage("parcel delayed")
                .status(AfterSalesTypes.TicketStatus.PENDING_APPROVAL)
                .currentRunId("run-restart")
                .build());
        ExecutionJobEntity job = jobRepository.saveAndFlush(ExecutionJobEntity.builder()
                .id("job-restart")
                .proposalId("proposal-restart")
                .ticketId("ticket-restart")
                .idempotencyKey("0123456789abcdef-restart")
                .actionType("DELAY_COMPENSATION_COUPON")
                .amount(new BigDecimal("10.00"))
                .currency("VND")
                .status(AfterSalesTypes.ExecutionStatus.PENDING)
                .attemptCount(0)
                .build());

        CountDownLatch oldWorkerAtOrderRead = new CountDownLatch(1);
        CountDownLatch releaseOldWorker = new CountDownLatch(1);
        AtomicBoolean blockFirstRead = new AtomicBoolean(true);
        MockShopifyAfterSalesConnector ledger = new MockShopifyAfterSalesConnector() {
            @Override
            public AfterSalesTypes.OrderSnapshot getOrder(String orderId) {
                if (blockFirstRead.compareAndSet(true, false)) {
                    oldWorkerAtOrderRead.countDown();
                    try {
                        if (!releaseOldWorker.await(5, TimeUnit.SECONDS)) {
                            throw new AssertionError("old worker test latch timed out");
                        }
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError("old worker interrupted", error);
                    }
                }
                return super.getOrder(orderId);
            }
        };
        var events = mock(AfterSalesRunEventService.class);
        var approvalValidator = mock(ExecutionApprovalValidator.class);
        when(approvalValidator.rejection(any(), any(), any())).thenReturn(null);
        AfterSalesExecutionProperties oldProperties = new AfterSalesExecutionProperties();
        oldProperties.setWorkerId("old-worker");
        oldProperties.setLeaseDurationMs(50L);
        AfterSalesExecutionProperties restartedProperties = new AfterSalesExecutionProperties();
        restartedProperties.setWorkerId("restarted-worker");
        restartedProperties.setLeaseDurationMs(5_000L);
        restartedProperties.setRetryDelayMs(0L);
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);
        ExecutorService oldWorkerPool = Executors.newSingleThreadExecutor();
        var oldService = new ExecutionService(jobRepository, ticketRepository, ledger,
                new ExecutionPreconditionGate(), events, mapper, transactions, oldProperties,
                oldWorkerPool, approvalValidator);
        var restartedService = new ExecutionService(jobRepository, ticketRepository, ledger,
                new ExecutionPreconditionGate(), events, mapper, transactions, restartedProperties,
                Runnable::run, approvalValidator);
        try {
            oldService.executeAsync(job.getId());
            assertThat(oldWorkerAtOrderRead.await(5, TimeUnit.SECONDS)).isTrue();

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                ExecutionJobEntity persisted = jobRepository.findByIdForUpdate(job.getId()).orElseThrow();
                persisted.setLeaseUntil(Instant.now().minusSeconds(1));
                jobRepository.saveAndFlush(persisted);
            });

            // A new service instance recovers the persistent lease and completes attempt 2.
            restartedService.retryDueJobs();
            if (jobRepository.findById(job.getId()).orElseThrow().getStatus()
                    == AfterSalesTypes.ExecutionStatus.RETRY_WAIT) {
                restartedService.retry(job.getId());
            }
            ExecutionJobEntity afterRestart = jobRepository.findById(job.getId()).orElseThrow();
            assertThat(afterRestart.getStatus()).as("lastError=%s", afterRestart.getLastError())
                    .isEqualTo(AfterSalesTypes.ExecutionStatus.SUCCEEDED);
            assertThat(afterRestart.getAttemptCount()).isEqualTo(2);
            assertThat(afterRestart.getLeaseOwner()).isNull();
            assertThat(afterRestart.getResultPayload()).contains("CPN-");
            assertThat(ledger.committedEffectCount()).isEqualTo(1);

            releaseOldWorker.countDown();
            oldWorkerPool.shutdown();
            assertThat(oldWorkerPool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            ExecutionJobEntity afterOldWorkerReturns = jobRepository.findById(job.getId()).orElseThrow();
            assertThat(afterOldWorkerReturns.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.SUCCEEDED);
            assertThat(afterOldWorkerReturns.getAttemptCount()).isEqualTo(2);
            assertThat(afterOldWorkerReturns.getResultPayload()).isEqualTo(afterRestart.getResultPayload());
            assertThat(ledger.committedEffectCount()).isEqualTo(1);
        } finally {
            releaseOldWorker.countDown();
            oldWorkerPool.shutdownNow();
        }
    }
}
