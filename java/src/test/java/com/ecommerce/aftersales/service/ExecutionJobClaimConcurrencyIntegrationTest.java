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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

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
}
