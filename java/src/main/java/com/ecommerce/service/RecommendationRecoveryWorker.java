package com.ecommerce.service;

import com.ecommerce.runtime.persistence.RecommendationRecoveryProperties;
import com.ecommerce.runtime.persistence.RecommendationExecutionLease;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, database-coordinated dispatch of new or abandoned recommendation runs.
 * A queued runnable owns only local capacity; its execution lease is claimed after
 * it starts, so waiting in an executor queue cannot consume a database lease.
 */
@Component
public class RecommendationRecoveryWorker {
    private static final Logger log = LoggerFactory.getLogger(RecommendationRecoveryWorker.class);

    private final RecommendationRuntimeStore store;
    private final AutonomousAgentLoopService loopService;
    private final AgentConcurrencyGuard guard;
    private final Executor executor;
    private final RecommendationRecoveryProperties properties;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final Set<String> dispatchedRuns = ConcurrentHashMap.newKeySet();

    public RecommendationRecoveryWorker(
            RecommendationRuntimeStore store,
            AutonomousAgentLoopService loopService,
            AgentConcurrencyGuard guard,
            @Qualifier("sseExecutor") Executor executor,
            RecommendationRecoveryProperties properties) {
        this.store = store;
        this.loopService = loopService;
        this.guard = guard;
        this.executor = executor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${agent.recovery.scan-interval:1s}")
    public void scan() {
        if (stopping.get() || !properties.isEnabled() || !scanning.compareAndSet(false, true)) return;
        try {
            List<String> runIds = store.findClaimableRunIds(properties.getBatchSize());
            for (String runId : runIds) {
                if (stopping.get()) break;
                if (!dispatchedRuns.add(runId)) continue;
                AgentConcurrencyGuard.GuardLease capacity = guard.tryAcquire("recovery");
                if (!capacity.isAcquired()) {
                    dispatchedRuns.remove(runId);
                    break;
                }
                if (stopping.get()) {
                    capacity.close();
                    dispatchedRuns.remove(runId);
                    break;
                }
                try {
                    executor.execute(() -> execute(runId, capacity));
                } catch (RejectedExecutionException rejected) {
                    capacity.close();
                    dispatchedRuns.remove(runId);
                    log.debug("Recommendation recovery executor is saturated");
                    break;
                } catch (RuntimeException submissionFailure) {
                    capacity.close();
                    dispatchedRuns.remove(runId);
                    log.warn("Recommendation recovery submission failed: {}",
                            submissionFailure.getClass().getSimpleName());
                    break;
                }
            }
        } catch (RuntimeException scanFailure) {
            log.warn("Recommendation recovery scan failed: {}", scanFailure.getClass().getSimpleName());
        } finally {
            scanning.set(false);
        }
    }

    private void execute(String runId, AgentConcurrencyGuard.GuardLease capacity) {
        try (capacity) {
            if (stopping.get() || !properties.isEnabled()) return;
            Optional<RecommendationExecutionLease> claimed = store.claimRun(runId, properties.getWorkerId());
            if (claimed.isEmpty()) return;
            // runClaimed owns fenced completion. On failure, never fall back to
            // an unfenced failRun: another instance may already own this run.
            loopService.runClaimed(claimed.get());
        } catch (RuntimeException executionFailure) {
            log.warn("Recommendation recovery execution failed: {}",
                    executionFailure.getClass().getSimpleName());
        } finally {
            dispatchedRuns.remove(runId);
        }
    }

    @PreDestroy
    public void stop() {
        stopping.set(true);
    }
}
