package com.ecommerce.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class AgentConcurrencyGuard {

    private final int maxConcurrentRuns;
    private final Semaphore runSemaphore;
    private final AtomicInteger activeRuns = new AtomicInteger();
    private final AtomicInteger rejectedRuns = new AtomicInteger();

    public AgentConcurrencyGuard(@Value("${agent.guard.max-concurrent-runs:12}") int maxConcurrentRuns) {
        this.maxConcurrentRuns = Math.max(1, maxConcurrentRuns);
        this.runSemaphore = new Semaphore(this.maxConcurrentRuns);
    }

    public GuardLease tryAcquire(String requestType) {
        boolean acquired = runSemaphore.tryAcquire();
        if (!acquired) {
            rejectedRuns.incrementAndGet();
            return GuardLease.rejected(requestType, "too_many_concurrent_agent_runs");
        }
        activeRuns.incrementAndGet();
        return GuardLease.acquired(this, requestType);
    }

    private void release() {
        activeRuns.decrementAndGet();
        runSemaphore.release();
    }

    public int activeRunCount() { return activeRuns.get(); }
    public int capacity() { return maxConcurrentRuns; }

    public Map<String, Object> snapshot() {
        return Map.of(
                "max_concurrent_runs", maxConcurrentRuns,
                "active_runs", activeRuns.get(),
                "available_permits", runSemaphore.availablePermits(),
                "rejected_runs", rejectedRuns.get()
        );
    }

    public static class GuardLease implements AutoCloseable {
        private final AgentConcurrencyGuard owner;
        private final String requestType;
        private final boolean acquired;
        private final String rejectReason;
        private final AtomicBoolean closed = new AtomicBoolean();

        private GuardLease(AgentConcurrencyGuard owner, String requestType, boolean acquired, String rejectReason) {
            this.owner = owner;
            this.requestType = requestType;
            this.acquired = acquired;
            this.rejectReason = rejectReason;
        }

        private static GuardLease acquired(AgentConcurrencyGuard owner, String requestType) {
            return new GuardLease(owner, requestType, true, "");
        }

        private static GuardLease rejected(String requestType, String reason) {
            return new GuardLease(null, requestType, false, reason);
        }

        public boolean isAcquired() {
            return acquired;
        }

        public String getRequestType() {
            return requestType;
        }

        public String getRejectReason() {
            return rejectReason;
        }

        @Override
        public void close() {
            if (acquired && closed.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }
}
