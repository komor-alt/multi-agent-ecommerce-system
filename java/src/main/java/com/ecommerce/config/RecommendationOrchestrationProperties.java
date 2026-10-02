package com.ecommerce.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Parallel recommendation orchestration and its isolated coordinator pool. */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.orchestration")
public class RecommendationOrchestrationProperties {
    private boolean parallelEnabled = true;
    private boolean speculativeRerankEnabled = true;
    private int maxParallelSpecialists = 2;
    private int coreSize = 2;
    private int maxSize = 4;
    private int queueCapacity = 16;
    private int keepAliveSeconds = 60;
    private int maxRetainedRuns = 100;
    private String threadNamePrefix = "agent-orchestrator-";

    @PostConstruct
    void validate() {
        requirePositive(maxParallelSpecialists, "max-parallel-specialists");
        requirePositive(coreSize, "core-size");
        requirePositive(maxSize, "max-size");
        if (maxSize < coreSize) {
            throw new IllegalStateException("agent.orchestration.max-size must be >= core-size");
        }
        if (queueCapacity < 0) {
            throw new IllegalStateException("agent.orchestration.queue-capacity must not be negative");
        }
        if (keepAliveSeconds < 0) {
            throw new IllegalStateException("agent.orchestration.keep-alive-seconds must not be negative");
        }
        requirePositive(maxRetainedRuns, "max-retained-runs");
        if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
            throw new IllegalStateException("agent.orchestration.thread-name-prefix must not be blank");
        }
    }

    private static void requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalStateException("agent.orchestration." + property + " must be positive");
        }
    }
}
