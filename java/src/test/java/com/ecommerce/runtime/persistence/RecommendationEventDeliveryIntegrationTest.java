package com.ecommerce.runtime.persistence;

import com.ecommerce.model.AgentRunEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recommendation-event-delivery;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RecommendationRunEventService.class, RecommendationEventDeliveryIntegrationTest.JsonConfiguration.class})
class RecommendationEventDeliveryIntegrationTest {
    @Autowired private RecommendationRunEventService writer;
    @Autowired private RecommendationRunRepository runs;
    @Autowired private RecommendationRunEventRepository events;
    @Autowired private PlatformTransactionManager transactions;

    @Test
    void separateApplicationInstanceTailsNewCommitsAndResumesFromDatabaseCursor() {
        String runId = startRun();
        AgentRunEvent first = source(runId, "first", 90);
        AgentRunEvent second = source(runId, "second", 1);
        writer.append(first);
        RecommendationRunEventDeliveryTest.QueuedExecutor executor = new RecommendationRunEventDeliveryTest.QueuedExecutor();
        RecommendationRunEventDeliveryTest.TestService reader = reader(executor);
        try {
            RecommendationRunEventDeliveryTest.RecordingEmitter stream =
                    (RecommendationRunEventDeliveryTest.RecordingEmitter) reader.stream(runId, first.getEventId());
            executor.runAll();
            assertThat(stream.ids()).isEmpty();
            writer.append(second); // No reference to the reader's subscribers exists on the writer.
            RecommendationRunEntity run = runs.findById(runId).orElseThrow();
            run.setStatus("COMPLETED");
            runs.saveAndFlush(run);
            reader.poll();
            executor.runAll();
            assertThat(stream.ids()).containsExactly(second.getEventId());
            assertThat(stream.completed).isTrue();
            assertThat(writer.history(runId)).extracting(row -> row.get("sequence")).containsExactly(1, 2);
            assertThatThrownBy(() -> writer.append(source(runId, "late", 3)))
                    .hasMessage("RECOMMENDATION_EVENT_RUN_TERMINAL");
        } finally {
            reader.shutdown();
        }
    }

    @Test
    void databaseLockOrdersConcurrentCommitsAndHidesUncommittedEventsFromReaders() throws Exception {
        String runId = startRun();
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        ExecutorService writers = Executors.newFixedThreadPool(2);
        RecommendationRunEventDeliveryTest.QueuedExecutor delivery = new RecommendationRunEventDeliveryTest.QueuedExecutor();
        RecommendationRunEventDeliveryTest.TestService reader = reader(delivery);
        AgentRunEvent first = source(runId, "first", 100);
        AgentRunEvent second = source(runId, "second", 1);
        try {
            Future<?> firstWrite = writers.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(tx -> {
                writer.append(first);
                events.flush();
                firstInserted.countDown();
                await(allowCommit);
            }));
            assertThat(firstInserted.await(5, TimeUnit.SECONDS)).isTrue();
            RecommendationRunEventDeliveryTest.RecordingEmitter stream =
                    (RecommendationRunEventDeliveryTest.RecordingEmitter) reader.stream(runId, null);
            delivery.runAll();
            assertThat(stream.ids()).isEmpty();
            Future<?> secondWrite = writers.submit(() -> writer.append(second));
            assertThatThrownBy(() -> secondWrite.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            allowCommit.countDown();
            firstWrite.get(5, TimeUnit.SECONDS);
            secondWrite.get(5, TimeUnit.SECONDS);
            reader.poll();
            delivery.runAll();
            assertThat(stream.ids()).containsExactly(first.getEventId(), second.getEventId());
            assertThat(writer.history(runId)).extracting(row -> row.get("sequence")).containsExactly(1, 2);
        } finally {
            allowCommit.countDown();
            writers.shutdownNow();
            reader.shutdown();
        }
    }

    @Test
    void rollbackDoesNotPublishEventsOrConsumeCursorAndDuplicateAppendDoesNotDuplicateRow() {
        String runId = startRun();
        AgentRunEvent rolledBack = source(runId, "rollback", 10);
        new TransactionTemplate(transactions).executeWithoutResult(tx -> {
            writer.append(rolledBack);
            events.flush();
            tx.setRollbackOnly();
        });
        assertThat(writer.history(runId)).isEmpty();
        AgentRunEvent committed = source(runId, "commit", 20);
        writer.append(committed);
        writer.append(committed);
        List<java.util.Map<String, Object>> history = writer.history(runId);
        assertThat(history).hasSize(1);
        assertThat(history.get(0)).containsEntry("sequence", 1).containsEntry("eventId", committed.getEventId());
    }

    private String startRun() {
        String id = "event-test-" + UUID.randomUUID();
        runs.saveAndFlush(RecommendationRunEntity.builder().id(id).scene("homepage")
                .status("RUNNING").requestJson("{}").build());
        return id;
    }

    private RecommendationRunEventDeliveryTest.TestService reader(RecommendationRunEventDeliveryTest.QueuedExecutor executor) {
        RecommendationEventProperties properties = new RecommendationEventProperties();
        return new RecommendationRunEventDeliveryTest.TestService(events, runs, properties,
                new RecommendationRunEventDeliveryTest.MutableClock(), executor);
    }

    private static AgentRunEvent source(String runId, String label, int suppliedSequence) {
        return AgentRunEvent.builder().requestId(runId).eventId(runId + ":" + label).sequence(suppliedSequence).build();
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test commit latch timed out");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    @TestConfiguration
    static class JsonConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
}
