package com.ecommerce.runtime.persistence;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Local transport used by the single-process deployment. The durable outbox is
 * unchanged when this bean is replaced by an external broker producer.
 */
@Component
@ConditionalOnProperty(
        name = "agent.persistence.outbox-transport",
        havingValue = "local",
        matchIfMissing = true)
public class InProcessRecommendationOutboxTransport implements RecommendationOutboxTransport {
    private final ApplicationEventPublisher publisher;

    public InProcessRecommendationOutboxTransport(ApplicationEventPublisher publisher) {
        this.publisher = publisher;
    }

    @Override
    public void publish(RecommendationOutboxMessage message) {
        publisher.publishEvent(message);
    }
}
