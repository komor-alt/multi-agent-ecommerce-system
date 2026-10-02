package com.ecommerce.service;

import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.persistence.RecommendationExecutionLease;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import com.ecommerce.runtime.persistence.StaleExecutionLeaseException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RecommendationLeaseHeartbeatServiceTest {
    private final RecommendationRuntimeStore store = mock(RecommendationRuntimeStore.class);
    private final RecommendationLeaseHeartbeatService service = new RecommendationLeaseHeartbeatService(store);
    private final RecommendationExecutionLease lease = lease("run-1", "token-1");

    @Test
    void renewsOnlyAnActiveRegisteredLease() {
        when(store.renewLease(lease)).thenReturn(true);
        try (RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease)) {
            service.heartbeat();
            verify(store).renewLease(lease);
            assertThat(handle.lost()).isFalse();
            assertThatCode(handle::assertActive).doesNotThrowAnyException();
            assertThat(service.activeCount()).isEqualTo(1);
        }
        assertThat(service.activeCount()).isZero();
    }

    @Test
    void failedRenewalMarksLossOnceAndNeverRevivesToken() {
        when(store.renewLease(lease)).thenReturn(false, true);
        AtomicInteger losses = new AtomicInteger();
        try (RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease)) {
            handle.onLost(losses::incrementAndGet);
            service.heartbeat();
            service.heartbeat();
            verify(store, times(1)).renewLease(lease);
            assertThat(handle.lost()).isTrue();
            assertThatThrownBy(handle::assertActive).isInstanceOf(StaleExecutionLeaseException.class);
            assertThat(losses.get()).isEqualTo(1);
            assertThat(service.activeCount()).isZero();
        }
    }

    @Test
    void databaseErrorFailsClosedWithoutExposingPayload() {
        when(store.renewLease(lease)).thenThrow(new IllegalStateException("sensitive database message"));
        try (RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease)) {
            assertThatCode(service::heartbeat).doesNotThrowAnyException();
            assertThat(handle.lost()).isTrue();
            assertThatThrownBy(handle::assertActive).isInstanceOf(StaleExecutionLeaseException.class)
                    .hasMessage("RECOMMENDATION_EXECUTION_LEASE_STALE:run-1");
            assertThat(service.activeCount()).isZero();
        }
    }

    @Test
    void closingHandleIsIdempotentAndStopsRenewals() {
        RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease);
        handle.close();
        handle.close();
        service.heartbeat();
        verifyNoInteractions(store);
        assertThat(service.activeCount()).isZero();
        assertThat(handle.lost()).isFalse();
        assertThatThrownBy(handle::assertActive).isInstanceOf(StaleExecutionLeaseException.class);
    }

    @Test
    void callbackRegisteredAfterLossStillReceivesOneNotification() {
        when(store.renewLease(lease)).thenReturn(false);
        try (RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease)) {
            service.heartbeat();
            AtomicInteger losses = new AtomicInteger();
            handle.onLost(losses::incrementAndGet);
            service.heartbeat();
            assertThat(losses.get()).isEqualTo(1);
            assertThatThrownBy(() -> handle.onLost(losses::incrementAndGet))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void duplicateRegistrationCannotReplaceActiveOwner() {
        when(store.renewLease(lease)).thenReturn(true);
        try (RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease)) {
            assertThatThrownBy(() -> service.begin(lease)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("RECOMMENDATION_LEASE_HEARTBEAT_ALREADY_REGISTERED");
            service.heartbeat();
            verify(store).renewLease(lease);
            assertThat(service.activeCount()).isEqualTo(1);
            assertThatCode(handle::assertActive).doesNotThrowAnyException();
        }
    }

    @Test
    void distinctTokensForTheSameRunRemainIndependent() {
        RecommendationExecutionLease replacement = lease("run-1", "token-2");
        when(store.renewLease(lease)).thenReturn(false);
        when(store.renewLease(replacement)).thenReturn(true);
        try (RecommendationLeaseHeartbeatService.Handle old = service.begin(lease);
             RecommendationLeaseHeartbeatService.Handle current = service.begin(replacement)) {
            service.heartbeat();
            old.close();
            service.heartbeat();
            verify(store, times(1)).renewLease(lease);
            verify(store, times(2)).renewLease(replacement);
            assertThat(old.lost()).isTrue();
            assertThatCode(current::assertActive).doesNotThrowAnyException();
            assertThat(service.activeCount()).isEqualTo(1);
        }
    }

    @Test
    void callbackFailureDoesNotPreventOtherLeasesFromRenewing() {
        RecommendationExecutionLease other = lease("run-2", "token-2");
        when(store.renewLease(lease)).thenReturn(false);
        when(store.renewLease(other)).thenReturn(true);
        try (RecommendationLeaseHeartbeatService.Handle failing = service.begin(lease);
             RecommendationLeaseHeartbeatService.Handle healthy = service.begin(other)) {
            failing.onLost(() -> { throw new IllegalArgumentException("broken callback"); });
            assertThatCode(service::heartbeat).doesNotThrowAnyException();
            verify(store).renewLease(other);
            assertThatCode(healthy::assertActive).doesNotThrowAnyException();
            assertThat(service.activeCount()).isEqualTo(1);
        }
    }

    @Test
    void shutdownMarksAllLeasesLostAndRejectsNewRegistrations() {
        AtomicInteger losses = new AtomicInteger();
        try (RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease)) {
            handle.onLost(losses::incrementAndGet);
            service.stop();
            service.stop();
            service.heartbeat();
            assertThat(handle.lost()).isTrue();
            assertThat(losses.get()).isEqualTo(1);
            assertThat(service.activeCount()).isZero();
            assertThatThrownBy(() -> service.begin(lease("run-2", "token-2")))
                    .isInstanceOf(StaleExecutionLeaseException.class);
            verifyNoInteractions(store);
        }
    }

    @Test
    void ordinaryCompletionDoesNotSignalLeaseLoss() {
        AtomicInteger losses = new AtomicInteger();
        RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease);
        handle.onLost(losses::incrementAndGet);
        handle.close();
        service.stop();
        assertThat(losses.get()).isZero();
        verify(store, never()).renewLease(lease);
    }

    @Test
    void slowDatabaseRenewalDoesNotHoldHandleMonitorOrResurrectClosedHandle() throws Exception {
        CountDownLatch renewalStarted = new CountDownLatch(1);
        CountDownLatch databaseReturns = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        when(store.renewLease(lease)).thenAnswer(ignored -> {
            renewalStarted.countDown();
            if (!databaseReturns.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test DB timeout");
            return true;
        });
        RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease);
        try {
            Future<?> renewal = worker.submit(service::heartbeat);
            assertThat(renewalStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                handle.assertActive();
                handle.onLost(() -> {});
                handle.close();
            });
            assertThat(service.activeCount()).isZero();
            databaseReturns.countDown();
            renewal.get(2, TimeUnit.SECONDS);
            assertThatThrownBy(handle::assertActive).isInstanceOf(StaleExecutionLeaseException.class);
            assertThat(service.activeCount()).isZero();
            service.heartbeat();
            verify(store, times(1)).renewLease(lease);
        } finally {
            databaseReturns.countDown();
            handle.close();
            worker.shutdownNow();
        }
    }

    @Test
    void overlappingTicksDoNotRenewTheSameLeaseConcurrently() throws Exception {
        CountDownLatch renewalStarted = new CountDownLatch(1);
        CountDownLatch databaseReturns = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        when(store.renewLease(lease)).thenAnswer(ignored -> {
            renewalStarted.countDown();
            if (!databaseReturns.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test DB timeout");
            return true;
        });
        try (RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease)) {
            Future<?> renewal = worker.submit(service::heartbeat);
            assertThat(renewalStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofSeconds(1), service::heartbeat);
            verify(store, times(1)).renewLease(lease);
            databaseReturns.countDown();
            renewal.get(2, TimeUnit.SECONDS);
            handle.assertActive();
            service.heartbeat();
            verify(store, times(2)).renewLease(lease);
        } finally {
            databaseReturns.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void shutdownNotifiesLossDuringSlowRenewalAndLateSuccessCannotUndoIt() throws Exception {
        CountDownLatch renewalStarted = new CountDownLatch(1);
        CountDownLatch databaseReturns = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        when(store.renewLease(lease)).thenAnswer(ignored -> {
            renewalStarted.countDown();
            if (!databaseReturns.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test DB timeout");
            return true;
        });
        AtomicInteger losses = new AtomicInteger();
        try (RecommendationLeaseHeartbeatService.Handle handle = service.begin(lease)) {
            handle.onLost(losses::incrementAndGet);
            Future<?> renewal = worker.submit(service::heartbeat);
            assertThat(renewalStarted.await(2, TimeUnit.SECONDS)).isTrue();
            assertTimeoutPreemptively(Duration.ofSeconds(1), service::stop);
            assertThat(losses.get()).isEqualTo(1);
            assertThat(handle.lost()).isTrue();
            databaseReturns.countDown();
            renewal.get(2, TimeUnit.SECONDS);
            assertThat(handle.lost()).isTrue();
            assertThat(losses.get()).isEqualTo(1);
            assertThat(service.activeCount()).isZero();
            assertThatThrownBy(handle::assertActive).isInstanceOf(StaleExecutionLeaseException.class);
        } finally {
            databaseReturns.countDown();
            worker.shutdownNow();
        }
    }

    private static RecommendationExecutionLease lease(String runId, String token) {
        return new RecommendationExecutionLease(runId, token, 1, "worker-1",
                ToolLoopRequest.builder().runId(runId).build());
    }
}
