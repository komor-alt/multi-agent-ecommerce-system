package com.ecommerce.runtime.persistence;

import java.time.Instant;

/** Transport-neutral message emitted from the durable outbox. */
public record RecommendationOutboxMessage(
        String eventId,
        String dedupKey,
        String aggregateType,
        String aggregateId,
        String eventType,
        String payloadJson,
        Instant createdAt) {
}
