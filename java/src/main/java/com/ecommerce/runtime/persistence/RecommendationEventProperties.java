package com.ecommerce.runtime.persistence;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Bounded per-instance resources for database-backed recommendation SSE delivery. */
@Getter
@Setter
@ConfigurationProperties(prefix = "agent.events")
public class RecommendationEventProperties {
    private int pageSize = 100;
    private int historyMaxPageSize = 500;
    private int maxSubscribers = 256;
    private int maxSubscribersPerRun = 8;
    private int senderThreads = 4;
    private int senderQueueCapacity = 256;
    private long streamTimeoutMs = 600_000;
    private long heartbeatIntervalMs = 15_000;

    @PostConstruct
    void validate() {
        positive(pageSize, "page-size");
        positive(historyMaxPageSize, "history-max-page-size");
        positive(maxSubscribers, "max-subscribers");
        positive(maxSubscribersPerRun, "max-subscribers-per-run");
        positive(senderThreads, "sender-threads");
        positive(senderQueueCapacity, "sender-queue-capacity");
        positive(streamTimeoutMs, "stream-timeout-ms");
        positive(heartbeatIntervalMs, "heartbeat-interval-ms");
        if (maxSubscribersPerRun > maxSubscribers) {
            throw new IllegalStateException("agent.events.max-subscribers-per-run must not exceed max-subscribers");
        }
    }

    private static void positive(long value, String name) {
        if (value <= 0) throw new IllegalStateException("agent.events." + name + " must be positive");
    }
}
