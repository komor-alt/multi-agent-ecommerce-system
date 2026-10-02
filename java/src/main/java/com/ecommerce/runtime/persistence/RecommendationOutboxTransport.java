package com.ecommerce.runtime.persistence;

/**
 * Supply a broker producer implementation for cross-process delivery. Return only after acknowledgement;
 * enforce a publish timeout below the outbox lease. Consumers must deduplicate by the stable dedupKey,
 * since a process may crash after publishing but before recording PUBLISHED.
 */
public interface RecommendationOutboxTransport {
    void publish(RecommendationOutboxMessage message);
}
