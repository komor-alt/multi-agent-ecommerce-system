package com.ecommerce.runtime.persistence;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

abstract class RecommendationOutboxContract {
    @Autowired RecommendationOutboxRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    private MutableClock clock;
    private TransactionTemplate transaction;

    @BeforeEach
    void resetOutbox() {
        repository.deleteAll();
        clock = new MutableClock(Instant.parse("2026-09-25T01:00:00Z"));
        transaction = new TransactionTemplate(transactionManager);
    }

    @Test
    void publishesOutsideTransactionAndRetriesWithBoundedExponentialBackoff() {
        repository.save(pendingEvent("retry"));
        AtomicInteger attempts = new AtomicInteger();
        RecommendationOutboxDispatcher dispatcher = dispatcher(message -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            if (attempts.incrementAndGet() <= 2) throw new IllegalStateException("sensitive broker details");
        }, 3, 100, 0.2);

        dispatcher.dispatchPending();
        RecommendationOutboxEntity first = row("retry");
        assertThat(first.getStatus()).isEqualTo("PENDING");
        assertThat(first.getAttemptCount()).isEqualTo(1);
        assertThat(first.getNextAttemptAt()).isEqualTo(clock.instant().plusMillis(100));
        assertThat(first.getLastError()).isEqualTo("IllegalStateException");
        dispatcher.dispatchPending();
        assertThat(attempts).hasValue(1);
        clock.advance(100);
        dispatcher.dispatchPending();
        assertThat(row("retry").getNextAttemptAt()).isEqualTo(clock.instant().plusMillis(200));
        clock.advance(200);
        dispatcher.dispatchPending();
        assertThat(row("retry").getStatus()).isEqualTo("PUBLISHED");
        assertThat(row("retry").getAttemptCount()).isEqualTo(3);
        assertThat(row("retry").getClaimToken()).isNull();
    }

    @Test
    void exhaustedPublishBecomesDeadLetterWithoutRetryingForever() {
        repository.save(pendingEvent("dead"));
        RecommendationOutboxDispatcher dispatcher = dispatcher(message -> {
            throw new IllegalArgumentException("invalid");
        }, 1, 0, 0);
        dispatcher.dispatchPending();
        dispatcher.dispatchPending();
        assertThat(row("dead").getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(row("dead").getAttemptCount()).isEqualTo(1);
        assertThat(row("dead").getNextAttemptAt()).isNull();
    }

    @Test
    void onlyOneInstanceWinsAConcurrentClaim() throws Exception {
        repository.save(pendingEvent("race"));
        var workers = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> claimAfter(start, "race", "owner-a"));
            var second = workers.submit(() -> claimAfter(start, "race", "owner-b"));
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS) + second.get(10, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(row("race").getAttemptCount()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void slowPublishDoesNotHoldDatabaseTransactionOrBlockAnotherDispatcher() throws Exception {
        repository.save(pendingEvent("slow"));
        CountDownLatch publishing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        var workers = Executors.newFixedThreadPool(2);
        try {
            var first = workers.submit(() -> dispatcher(message -> {
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                calls.incrementAndGet();
                publishing.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }, 3, 0, 0).dispatchPending());
            assertThat(publishing.await(10, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> dispatcher(message -> calls.incrementAndGet(), 3, 0, 0).dispatchPending());
            second.get(5, TimeUnit.SECONDS);
            assertThat(row("slow").getStatus()).isEqualTo("IN_FLIGHT");
            assertThat(calls).hasValue(1);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
            assertThat(row("slow").getStatus()).isEqualTo("PUBLISHED");
        } finally {
            release.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void expiredLeaseIsReclaimedAndLateAcknowledgementCannotOverwriteNewOwner() {
        repository.save(pendingEvent("recover"));
        assertThat(transaction.<Integer>execute(status -> repository.claim(
                "recover", "crashed-owner", clock.instant(), clock.instant().plusMillis(1000), 3))).isEqualTo(1);
        clock.advance(1001);
        assertThat(transaction.<Integer>execute(status -> repository.claim(
                "recover", "new-owner", clock.instant(), clock.instant().plusMillis(1000), 3))).isEqualTo(1);
        assertThat(transaction.<Integer>execute(status -> repository.acknowledge(
                "recover", "crashed-owner", clock.instant()))).isZero();
        assertThat(transaction.<Integer>execute(status -> repository.fail("recover", "crashed-owner",
                clock.instant(), "PENDING", clock.instant(), "late failure"))).isZero();
        assertThat(row("recover").getClaimToken()).isEqualTo("new-owner");
        assertThat(transaction.<Integer>execute(status -> repository.acknowledge(
                "recover", "new-owner", clock.instant()))).isEqualTo(1);
        assertThat(row("recover").getAttemptCount()).isEqualTo(2);
    }

    @Test
    void finalAttemptProcessCrashBecomesUnknownOutcomeDeadLetter() {
        repository.save(pendingEvent("crashed"));
        transaction.executeWithoutResult(status -> repository.claim(
                "crashed", "lost-owner", clock.instant(), clock.instant().plusMillis(1000), 1));
        clock.advance(1001);
        AtomicInteger calls = new AtomicInteger();
        dispatcher(message -> calls.incrementAndGet(), 1, 0, 0).dispatchPending();
        assertThat(row("crashed").getStatus()).isEqualTo("DEAD_LETTER");
        assertThat(row("crashed").getLastError()).isEqualTo("DELIVERY_LEASE_EXHAUSTED_OUTCOME_UNKNOWN");
        assertThat(calls).hasValue(0);
    }

    @Test
    void rolledBackOutboxWriteIsNeverPublished() {
        transaction.executeWithoutResult(status -> {
            repository.saveAndFlush(pendingEvent("rolled-back"));
            status.setRollbackOnly();
        });
        AtomicInteger calls = new AtomicInteger();
        dispatcher(message -> calls.incrementAndGet(), 3, 0, 0).dispatchPending();
        assertThat(repository.findById("rolled-back")).isEmpty();
        assertThat(calls).hasValue(0);
    }

    @Test
    void backlogMetricsCountExpiredClaimsAndAgeOnlyActiveUndeliveredEvents() {
        var pending = pendingEvent("metrics-pending");
        pending.setCreatedAt(clock.instant().minusSeconds(30));
        var active = pendingEvent("metrics-active");
        active.setCreatedAt(clock.instant().minusSeconds(10));
        active.setStatus("IN_FLIGHT");
        active.setLeaseUntil(clock.instant().plusSeconds(20));
        var expired = pendingEvent("metrics-expired");
        expired.setCreatedAt(clock.instant().minusSeconds(20));
        expired.setStatus("IN_FLIGHT");
        expired.setLeaseUntil(clock.instant());
        var dead = pendingEvent("metrics-dead");
        dead.setCreatedAt(clock.instant().minusSeconds(3000));
        dead.setStatus("DEAD_LETTER");
        var published = pendingEvent("metrics-published");
        published.setStatus("PUBLISHED");
        published.setCreatedAt(clock.instant().minusSeconds(6000));
        repository.saveAll(List.of(pending, active, expired, dead, published));

        var monitor = new RecommendationPersistenceMonitor(mock(RecommendationRunRepository.class),
                mock(RecommendationTaskRepository.class), repository, clock);
        assertThat(monitor.snapshot()).containsEntry("outboxPending", 1L)
                .containsEntry("outboxPublished", 1L).containsEntry("outboxDeadLetter", 1L)
                .containsEntry("outboxInFlight", 2L).containsEntry("outboxExpiredLeases", 1L)
                .containsEntry("outboxOldestUnfinishedAgeSeconds", 30L);
        repository.deleteAll();
        assertThat(monitor.snapshot()).containsEntry("outboxInFlight", 0L)
                .containsEntry("outboxExpiredLeases", 0L).containsEntry("outboxOldestUnfinishedAgeSeconds", 0L);
    }

    private int claimAfter(CountDownLatch start, String id, String owner) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
        return transaction.<Integer>execute(status -> repository.claim(
                id, owner, clock.instant(), clock.instant().plusMillis(1000), 3));
    }

    private RecommendationOutboxDispatcher dispatcher(RecommendationOutboxTransport transport,
                                                        int attempts, long delay, double jitter) {
        return new RecommendationOutboxDispatcher(repository, transport, transactionManager,
                10, attempts, delay, 1000, 1000, jitter, clock, () -> 0.5);
    }

    private RecommendationOutboxEntity row(String id) {
        return repository.findById(id).orElseThrow();
    }

    static RecommendationOutboxEntity pendingEvent(String id) {
        return RecommendationOutboxEntity.builder().id(id).dedupKey(id)
                .aggregateType("TASK").aggregateId("task-1").eventType("TASK_STATUS_CHANGED")
                .payloadJson("{\"text\":\"中文 payload " + "x".repeat(2000) + "\"}")
                .status("PENDING").createdAt(Instant.parse("2026-09-25T00:00:00Z")).build();
    }

    private static final class MutableClock extends Clock {
        private volatile Instant now;
        private MutableClock(Instant now) { this.now = now; }
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
