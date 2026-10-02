package com.ecommerce.service;

import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.persistence.RecommendationExecutionLease;
import com.ecommerce.runtime.persistence.RecommendationRecoveryProperties;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecommendationRecoveryWorkerTest {
    private final RecommendationRuntimeStore store = mock(RecommendationRuntimeStore.class);
    private final AutonomousAgentLoopService loop = mock(AutonomousAgentLoopService.class);
    private final RecommendationRecoveryProperties properties = new RecommendationRecoveryProperties();
    private final AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);

    @Test
    void disabledWorkerDoesNotReadOrClaimRuns() {
        properties.setEnabled(false);
        worker(Runnable::run).scan();
        verifyNoInteractions(store, loop);
        assertThat(guard.activeRunCount()).isZero();
    }

    @Test
    void saturatedCapacityNeverClaimsOrDispatches() {
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1"));
        Executor executor = mock(Executor.class);
        try (AgentConcurrencyGuard.GuardLease occupied = guard.tryAcquire("request")) {
            worker(executor).scan();
            assertThat(guard.activeRunCount()).isEqualTo(1);
            verify(store, never()).claimRun(anyString(), anyString());
            verifyNoInteractions(executor, loop);
        }
        assertThat(guard.activeRunCount()).isZero();
    }

    @Test
    void executorRejectionReleasesCapacityWithoutClaiming() {
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1", "run-2"));
        worker(task -> { throw new RejectedExecutionException(); }).scan();
        assertThat(guard.activeRunCount()).isZero();
        verify(store, never()).claimRun(anyString(), anyString());
        verifyNoInteractions(loop);
    }

    @Test
    void unexpectedSubmissionFailureAlsoReleasesCapacity() {
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1"));
        worker(task -> { throw new IllegalStateException("executor unavailable"); }).scan();
        assertThat(guard.activeRunCount()).isZero();
        verify(store, never()).claimRun(anyString(), anyString());
    }

    @Test
    void queuedWorkDoesNotClaimUntilItsRunnableStarts() {
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1", "run-2"));
        when(store.claimRun("run-1", properties.getWorkerId())).thenReturn(Optional.empty());
        Queue<Runnable> queued = new ArrayDeque<>();
        worker(queued::add).scan();
        assertThat(queued).hasSize(1);
        assertThat(guard.activeRunCount()).isEqualTo(1);
        verify(store, never()).claimRun(anyString(), anyString());

        queued.remove().run();
        verify(store).claimRun("run-1", properties.getWorkerId());
        verifyNoInteractions(loop);
        assertThat(guard.activeRunCount()).isZero();
    }

    @Test
    void repeatedScansDoNotFillExecutorWithTheSameUnclaimedRun() {
        AgentConcurrencyGuard largerGuard = new AgentConcurrencyGuard(3);
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1"));
        when(store.claimRun("run-1", properties.getWorkerId())).thenReturn(Optional.empty());
        Queue<Runnable> queued = new ArrayDeque<>();
        RecommendationRecoveryWorker worker = new RecommendationRecoveryWorker(
                store, loop, largerGuard, queued::add, properties);
        worker.scan();
        worker.scan();
        assertThat(queued).hasSize(1);
        assertThat(largerGuard.activeRunCount()).isEqualTo(1);
        queued.remove().run();
        assertThat(largerGuard.activeRunCount()).isZero();
        worker.scan();
        assertThat(queued).hasSize(1);
        queued.remove().run();
        assertThat(largerGuard.activeRunCount()).isZero();
    }

    @Test
    void lostClaimRaceReturnsCapacityNormally() {
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1"));
        when(store.claimRun("run-1", properties.getWorkerId())).thenReturn(Optional.empty());
        worker(Runnable::run).scan();
        verifyNoInteractions(loop);
        assertThat(guard.activeRunCount()).isZero();
    }

    @Test
    void successfulClaimUsesTheExactLeaseAndReleasesCapacity() {
        RecommendationExecutionLease lease = lease();
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1"));
        when(store.claimRun("run-1", properties.getWorkerId())).thenReturn(Optional.of(lease));
        worker(Runnable::run).scan();
        verify(loop).runClaimed(lease);
        assertThat(guard.activeRunCount()).isZero();
    }

    @Test
    void databaseClaimFailureDoesNotLeakCapacityOrExecute() {
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1"));
        when(store.claimRun("run-1", properties.getWorkerId())).thenThrow(new IllegalStateException("database down"));
        assertThatCode(() -> worker(Runnable::run).scan()).doesNotThrowAnyException();
        verifyNoInteractions(loop);
        assertThat(guard.activeRunCount()).isZero();
    }

    @Test
    void loopFailureReleasesCapacityWithoutUnfencedFailureWrite() {
        RecommendationExecutionLease lease = lease();
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1"));
        when(store.claimRun("run-1", properties.getWorkerId())).thenReturn(Optional.of(lease));
        doThrow(new IllegalStateException("execution failure")).when(loop).runClaimed(lease);
        worker(Runnable::run).scan();
        verify(store, never()).failRun(eq("run-1"), anyString());
        assertThat(guard.activeRunCount()).isZero();
    }

    @Test
    void scanDatabaseFailureAllowsLaterScans() {
        when(store.findClaimableRunIds(properties.getBatchSize()))
                .thenThrow(new IllegalStateException("database down"))
                .thenReturn(List.of("run-1"));
        RecommendationExecutionLease lease = lease();
        when(store.claimRun("run-1", properties.getWorkerId())).thenReturn(Optional.of(lease));
        RecommendationRecoveryWorker worker = worker(Runnable::run);
        worker.scan();
        worker.scan();
        verify(loop).runClaimed(lease);
        assertThat(guard.activeRunCount()).isZero();
    }

    @Test
    void shutdownPreventsQueuedAndFutureWorkFromClaiming() {
        when(store.findClaimableRunIds(properties.getBatchSize())).thenReturn(List.of("run-1"));
        Queue<Runnable> queued = new ArrayDeque<>();
        RecommendationRecoveryWorker worker = worker(queued::add);
        worker.scan();
        worker.stop();
        worker.scan();
        queued.remove().run();
        verify(store).findClaimableRunIds(properties.getBatchSize());
        verify(store, never()).claimRun(anyString(), anyString());
        verifyNoInteractions(loop);
        assertThat(guard.activeRunCount()).isZero();
    }

    @Test
    void scansUseConfiguredFiniteBatchSize() {
        properties.setBatchSize(2);
        when(store.findClaimableRunIds(2)).thenReturn(List.of("run-1", "run-2"));
        when(store.claimRun(anyString(), anyString())).thenReturn(Optional.empty());
        worker(Runnable::run).scan();
        verify(store).findClaimableRunIds(2);
        verify(store).claimRun("run-1", properties.getWorkerId());
        verify(store).claimRun("run-2", properties.getWorkerId());
        assertThat(guard.activeRunCount()).isZero();
    }

    private RecommendationRecoveryWorker worker(Executor executor) {
        return new RecommendationRecoveryWorker(store, loop, guard, executor, properties);
    }

    private RecommendationExecutionLease lease() {
        return new RecommendationExecutionLease("run-1", "token-1", 1,
                properties.getWorkerId(), ToolLoopRequest.builder().runId("run-1").build());
    }
}
