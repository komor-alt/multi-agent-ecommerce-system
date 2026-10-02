package com.ecommerce.runtime.persistence;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.DoubleSupplier;

/**
 * Short claim transaction -> external publish without DB locks -> fenced acknowledgement.
 * Delivery is at-least-once; consumers must durably deduplicate using message.dedupKey().
 * A transport must enforce its own network timeout shorter than the configured lease.
 */
@Service
public class RecommendationOutboxDispatcher {
    private static final Logger log = LoggerFactory.getLogger(RecommendationOutboxDispatcher.class);
    private final RecommendationOutboxRepository repository;
    private final RecommendationOutboxTransport transport;
    private final TransactionTemplate transaction;
    private final int batchSize;
    private final int maxAttempts;
    private final long retryDelayMs;
    private final long retryMaxDelayMs;
    private final long leaseMs;
    private final double jitterRatio;
    private final Clock clock;
    private final DoubleSupplier random;

    @Autowired
    public RecommendationOutboxDispatcher(
            RecommendationOutboxRepository repository,
            RecommendationOutboxTransport transport,
            PlatformTransactionManager transactionManager,
            @Value("${agent.persistence.outbox-batch-size:200}") int batchSize,
            @Value("${agent.persistence.outbox-max-attempts:10}") int maxAttempts,
            @Value("${agent.persistence.outbox-retry-delay-ms:5000}") long retryDelayMs,
            @Value("${agent.persistence.outbox-retry-max-delay-ms:300000}") long retryMaxDelayMs,
            @Value("${agent.persistence.outbox-lease-ms:60000}") long leaseMs,
            @Value("${agent.persistence.outbox-retry-jitter-ratio:0.2}") double jitterRatio) {
        this(repository, transport, transactionManager, batchSize, maxAttempts, retryDelayMs,
                retryMaxDelayMs, leaseMs, jitterRatio, Clock.systemUTC(),
                () -> ThreadLocalRandom.current().nextDouble());
    }

    RecommendationOutboxDispatcher(
            RecommendationOutboxRepository repository, RecommendationOutboxTransport transport,
            PlatformTransactionManager transactionManager, int batchSize, int maxAttempts,
            long retryDelayMs, long retryMaxDelayMs, long leaseMs, double jitterRatio,
            Clock clock, DoubleSupplier random) {
        if (batchSize < 1 || maxAttempts < 1 || retryDelayMs < 0 || retryMaxDelayMs < retryDelayMs
                || leaseMs < 1 || !Double.isFinite(jitterRatio) || jitterRatio < 0 || jitterRatio > 1) {
            throw new IllegalArgumentException("Invalid recommendation outbox configuration");
        }
        this.repository = repository;
        this.transport = transport;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.retryDelayMs = retryDelayMs;
        this.retryMaxDelayMs = retryMaxDelayMs;
        this.leaseMs = leaseMs;
        this.jitterRatio = jitterRatio;
        this.clock = clock;
        this.random = random;
    }

    @Scheduled(fixedDelayString = "${agent.persistence.outbox-scan-ms:250}")
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void dispatchPending() {
        Instant now = clock.instant();
        transaction.executeWithoutResult(status -> repository.deadLetterExhausted(now, maxAttempts));
        List<String> ids = transaction.execute(status -> repository.findDispatchableIds(
                now, maxAttempts, PageRequest.of(0, batchSize)));
        if (ids == null) return;
        for (String id : ids) {
            // Claim immediately before publishing, so later records do not consume their lease waiting in a batch.
            String token = UUID.randomUUID().toString();
            RecommendationOutboxEntity claimed = transaction.execute(status -> {
                Instant claimTime = clock.instant();
                if (repository.claim(id, token, claimTime, claimTime.plusMillis(leaseMs), maxAttempts) == 0) {
                    return null;
                }
                return repository.findById(id).orElseThrow();
            });
            if (claimed != null) dispatch(claimed, token);
        }
    }

    private void dispatch(RecommendationOutboxEntity event, String token) {
        try {
            transport.publish(new RecommendationOutboxMessage(
                    event.getId(), event.getDedupKey(), event.getAggregateType(), event.getAggregateId(),
                    event.getEventType(), event.getPayloadJson(), event.getCreatedAt()));
        } catch (Exception error) {
            Instant now = clock.instant();
            boolean exhausted = event.getAttemptCount() >= maxAttempts;
            transaction.executeWithoutResult(status -> repository.fail(event.getId(), token, now,
                    exhausted ? "DEAD_LETTER" : "PENDING",
                    exhausted ? null : now.plusMillis(retryDelay(event.getAttemptCount())),
                    error.getClass().getSimpleName()));
            log.warn("Recommendation outbox delivery failed: eventId={}, attempt={}, exhausted={}, errorType={}",
                    event.getId(), event.getAttemptCount(), exhausted, error.getClass().getSimpleName());
            return;
        }
        // Publication may already have succeeded if this separate database acknowledgement fails.
        Integer updated = transaction.execute(status -> repository.acknowledge(event.getId(), token, clock.instant()));
        if (updated == null || updated == 0) {
            log.warn("Recommendation outbox acknowledgement fenced: eventId={}", event.getId());
        }
    }

    private long retryDelay(int attempt) {
        double exponential = Math.min(retryMaxDelayMs,
                retryDelayMs * Math.pow(2, Math.min(62, Math.max(0, attempt - 1))));
        double factor = 1 - jitterRatio + 2 * jitterRatio * random.getAsDouble();
        return Math.min(retryMaxDelayMs, Math.max(0, Math.round(exponential * factor)));
    }
}
