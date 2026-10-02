package com.ecommerce.service;

import com.ecommerce.runtime.persistence.RecommendationExecutionLease;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import com.ecommerce.runtime.persistence.StaleExecutionLeaseException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Renews leases owned by currently executing local runs. A failed renewal is
 * final for that handle; recovery must acquire a new token through the store.
 */
@Component
public class RecommendationLeaseHeartbeatService {
    private static final Logger log = LoggerFactory.getLogger(RecommendationLeaseHeartbeatService.class);

    private final RecommendationRuntimeStore store;
    private final ConcurrentMap<String, Handle> active = new ConcurrentHashMap<>();
    private final AtomicBoolean stopping = new AtomicBoolean();

    public RecommendationLeaseHeartbeatService(RecommendationRuntimeStore store) {
        this.store = store;
    }

    public Handle begin(RecommendationExecutionLease lease) {
        Objects.requireNonNull(lease, "lease");
        if (stopping.get()) throw new StaleExecutionLeaseException(lease.runId());
        Handle handle = new Handle(lease);
        if (active.putIfAbsent(lease.token(), handle) != null) {
            throw new IllegalStateException("RECOMMENDATION_LEASE_HEARTBEAT_ALREADY_REGISTERED");
        }
        // Covers shutdown racing with registration; no handle remains untracked.
        if (stopping.get()) {
            handle.markLost();
            throw new StaleExecutionLeaseException(lease.runId());
        }
        return handle;
    }

    @Scheduled(fixedDelayString = "${agent.recovery.heartbeat-interval:5s}",
            scheduler = "recommendationHeartbeatScheduler")
    public void heartbeat() {
        if (stopping.get()) return;
        active.values().forEach(Handle::renew);
    }

    public int activeCount() {
        return active.size();
    }

    @PreDestroy
    public void stop() {
        stopping.set(true);
        active.values().forEach(Handle::markLost);
    }

    public final class Handle implements AutoCloseable {
        private final RecommendationExecutionLease lease;
        private boolean closed;
        private boolean lost;
        private boolean renewalInFlight;
        private Runnable lossCallback;
        private boolean callbackDelivered;

        private Handle(RecommendationExecutionLease lease) {
            this.lease = lease;
        }

        public synchronized void assertActive() {
            if (closed || lost || stopping.get()) throw new StaleExecutionLeaseException(lease.runId());
        }

        public synchronized boolean lost() {
            return lost;
        }

        /** A single notification hook, also delivered when loss preceded registration. */
        public void onLost(Runnable callback) {
            Runnable toNotify;
            synchronized (this) {
                Objects.requireNonNull(callback, "callback");
                if (lossCallback != null) throw new IllegalStateException("Lease loss callback already registered");
                lossCallback = callback;
                toNotify = takeLossCallback();
            }
            notifyLoss(toNotify);
        }

        private void renew() {
            synchronized (this) {
                if (closed || lost || stopping.get() || renewalInFlight) return;
                renewalInFlight = true;
            }

            // Never hold the Handle monitor across database I/O. A blocked
            // renewal must not prevent deadlines, cancellation or shutdown.
            boolean renewed = false;
            try {
                renewed = store.renewLease(lease);
            } catch (RuntimeException renewalFailure) {
                // An uncertain renewal is not ownership proof. Fail closed
                // and rely on durable token checks to reject stale writes.
                log.warn("Recommendation lease renewal failed: {}",
                        renewalFailure.getClass().getSimpleName());
            }

            Runnable toNotify = null;
            synchronized (this) {
                renewalInFlight = false;
                // close/stop can win while the query is in flight. A late
                // successful renewal must never resurrect that local handle.
                if (closed || lost) return;
                if (!renewed || stopping.get()) {
                    lost = true;
                    active.remove(lease.token(), this);
                    toNotify = takeLossCallback();
                }
            }
            notifyLoss(toNotify);
        }

        private void markLost() {
            Runnable toNotify;
            synchronized (this) {
                if (closed || lost) return;
                lost = true;
                active.remove(lease.token(), this);
                toNotify = takeLossCallback();
            }
            notifyLoss(toNotify);
        }

        private Runnable takeLossCallback() {
            if (!lost || callbackDelivered || lossCallback == null) return null;
            callbackDelivered = true;
            return lossCallback;
        }

        private void notifyLoss(Runnable callback) {
            if (callback == null) return;
            try {
                callback.run();
            } catch (RuntimeException callbackFailure) {
                log.warn("Recommendation lease loss callback failed: {}",
                        callbackFailure.getClass().getSimpleName());
            }
        }

        @Override
        public synchronized void close() {
            if (closed) return;
            closed = true;
            active.remove(lease.token(), this);
        }
    }
}
