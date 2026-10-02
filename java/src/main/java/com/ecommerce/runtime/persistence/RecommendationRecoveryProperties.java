package com.ecommerce.runtime.persistence;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.UUID;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.recovery")
public class RecommendationRecoveryProperties {
    private boolean enabled = true;
    private Duration leaseDuration = Duration.ofSeconds(30);
    private Duration heartbeatInterval = Duration.ofSeconds(5);
    private Duration scanInterval = Duration.ofSeconds(1);
    private int renewalTimeoutSeconds = 3;
    private int batchSize = 16;
    private int maxAttempts = 3;
    private String workerId = "recommendation-" + UUID.randomUUID();

    @PostConstruct
    public void validate() {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.toMillis() < 1
                || heartbeatInterval == null || heartbeatInterval.isNegative() || heartbeatInterval.toMillis() < 1
                || scanInterval == null || scanInterval.isNegative() || scanInterval.toMillis() < 1
                || heartbeatInterval.compareTo(leaseDuration.dividedBy(2)) >= 0
                || renewalTimeoutSeconds < 1 || Duration.ofSeconds(renewalTimeoutSeconds).compareTo(leaseDuration) >= 0
                || batchSize < 1 || maxAttempts < 1 || workerId == null || workerId.isBlank() || workerId.length() > 255) {
            throw new IllegalStateException("Invalid agent.recovery limits, worker-id or lease/heartbeat durations");
        }
    }
}
