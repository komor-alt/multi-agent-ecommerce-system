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

class AgentConcurrencyGuardTest {

    @Test
    void rejectsWhenMaxConcurrentRunsIsReached() {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);

        AgentConcurrencyGuard.GuardLease first = guard.tryAcquire("recommend");
        AgentConcurrencyGuard.GuardLease second = guard.tryAcquire("recommend");

        assertThat(first.isAcquired()).isTrue();
        assertThat(second.isAcquired()).isFalse();
        assertThat(second.getRejectReason()).isEqualTo("too_many_concurrent_agent_runs");
        assertThat(guard.snapshot()).containsEntry("rejected_runs", 1);

        first.close();
        assertThat(guard.snapshot()).containsEntry("active_runs", 0);
    }

    @Test
    void releasesPermitAfterLeaseIsClosed() {
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);

        AgentConcurrencyGuard.GuardLease first = guard.tryAcquire("recommend");
        first.close();
        AgentConcurrencyGuard.GuardLease second = guard.tryAcquire("agent_loop");

        assertThat(second.isAcquired()).isTrue();
        assertThat(guard.snapshot()).containsEntry("active_runs", 1);
        second.close();
        assertThat(guard.snapshot()).containsEntry("available_permits", 1);
    }

    @Test
    void capsConcurrentCallersUnderBurstLoad() throws Exception {
        int capacity = 3;
        int callers = 12;
        AgentConcurrencyGuard guard = new AgentConcurrencyGuard(capacity);
        ExecutorService executor = Executors.newFixedThreadPool(callers);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> futures = new ArrayList<>();

        for (int i = 0; i < callers; i++) {
            futures.add(executor.submit(() -> {
                ready.countDown();
                start.await(1, TimeUnit.SECONDS);
                try (AgentConcurrencyGuard.GuardLease lease = guard.tryAcquire("burst_recommend")) {
                    if (!lease.isAcquired()) {
                        return false;
                    }
                    Thread.sleep(100);
                    return true;
                }
            }));
        }

        assertThat(ready.await(1, TimeUnit.SECONDS)).isTrue();
        start.countDown();

        int acquired = 0;
        for (Future<Boolean> future : futures) {
            if (future.get(1, TimeUnit.SECONDS)) {
                acquired++;
            }
        }
        executor.shutdownNow();

        assertThat(acquired).isEqualTo(capacity);
        assertThat(guard.snapshot()).containsEntry("active_runs", 0);
        assertThat(guard.snapshot()).containsEntry("rejected_runs", callers - capacity);
    }
}
