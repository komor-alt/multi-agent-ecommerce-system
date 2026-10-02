package com.ecommerce.service;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class AgentConcurrencyGuardLeaseRaceTest {
    @Test
    void competingTimeoutCompletionAndFailureCallbacksReleaseOnePermitOnly() throws Exception {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);
        AgentConcurrencyGuard.GuardLease lease = guard.tryAcquire("stream");
        ExecutorService callbacks = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < 8; i++) {
                futures.add(callbacks.submit(() -> {
                    try {
                        if (!start.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("start latch timed out");
                        for (int j = 0; j < 100; j++) lease.close();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }));
            }
            start.countDown();
            for (Future<?> future : futures) future.get(3, TimeUnit.SECONDS);
            assertThat(guard.snapshot()).containsEntry("active_runs", 0).containsEntry("available_permits", 1);
            try (AgentConcurrencyGuard.GuardLease first = guard.tryAcquire("next");
                 AgentConcurrencyGuard.GuardLease excess = guard.tryAcquire("excess")) {
                assertThat(first.isAcquired()).isTrue();
                assertThat(excess.isAcquired()).isFalse();
            }
            assertThat(guard.activeRunCount()).isZero();
        } finally {
            start.countDown();
            callbacks.shutdownNow();
            lease.close();
        }
    }

    @Test
    void closingRejectedLeaseCannotIncreaseCapacityOrReleaseAnotherRunsPermit() {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);
        try (AgentConcurrencyGuard.GuardLease accepted = guard.tryAcquire("active")) {
            AgentConcurrencyGuard.GuardLease denied = guard.tryAcquire("rejected");
            denied.close();
            denied.close();
            assertThat(guard.snapshot()).containsEntry("active_runs", 1).containsEntry("available_permits", 0);
            assertThat(guard.tryAcquire("still-full").isAcquired()).isFalse();
        }
        assertThat(guard.snapshot()).containsEntry("active_runs", 0).containsEntry("available_permits", 1);
    }
}
