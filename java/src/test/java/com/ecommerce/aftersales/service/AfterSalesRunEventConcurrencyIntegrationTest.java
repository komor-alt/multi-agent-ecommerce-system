package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEventEntity;
import com.ecommerce.aftersales.repository.AfterSalesRunEventRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class AfterSalesRunEventConcurrencyIntegrationTest {

    @Autowired
    private AfterSalesRunRepository runRepository;

    @Autowired
    private AfterSalesRunEventRepository eventRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void separateServiceInstancesAllocateUniqueMonotonicSequences() throws Exception {
        runRepository.save(AfterSalesRunEntity.builder()
                .id("run-concurrent-events")
                .ticketId("ticket-concurrent-events")
                .status("RUNNING")
                .maxSteps(32)
                .stepCount(0)
                .build());

        AfterSalesRunEventService instanceA =
                new AfterSalesRunEventService(eventRepository, runRepository, new ObjectMapper());
        AfterSalesRunEventService instanceB =
                new AfterSalesRunEventService(eventRepository, runRepository, new ObjectMapper());
        TransactionTemplate transactions = new TransactionTemplate(transactionManager);

        int writers = 20;
        ExecutorService writerPool = Executors.newFixedThreadPool(writers);
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < writers; i++) {
                int index = i;
                AfterSalesRunEventService service = i % 2 == 0 ? instanceA : instanceB;
                futures.add(writerPool.submit(() -> {
                    ready.countDown();
                    start.await();
                    transactions.executeWithoutResult(status -> service.append(
                            "run-concurrent-events",
                            "tool_completed",
                            "event-" + index,
                            "success",
                            "completed",
                            Map.of("index", index)));
                    return null;
                }));
            }
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (Future<?> future : futures) {
                future.get(10, TimeUnit.SECONDS);
            }
        } finally {
            writerPool.shutdownNow();
        }

        List<AfterSalesRunEventEntity> events =
                eventRepository.findByRunIdOrderBySequenceAsc("run-concurrent-events");
        List<Integer> sequences = events.stream().map(AfterSalesRunEventEntity::getSequence).toList();
        assertThat(events).hasSize(writers);
        assertThat(new HashSet<>(sequences)).hasSize(writers);
        assertThat(sequences).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, writers).boxed().toList());
    }
}
