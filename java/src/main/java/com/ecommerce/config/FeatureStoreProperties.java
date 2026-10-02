package com.ecommerce.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.feature-store")
public class FeatureStoreProperties {
    private long maxCachedUsers = 1_000;
    private int maxRecentEvents = 20;
    private int recentEventPreviewSize = 5;
    private int maxEventBytes = 4_096;
    private Duration memoryTtl = Duration.ofMinutes(30);
    private Duration redisTtl = Duration.ofHours(24);

    @PostConstruct
    public void validate() {
        if (maxCachedUsers < 1 || maxRecentEvents < 1 || recentEventPreviewSize < 1
                || recentEventPreviewSize > maxRecentEvents || maxEventBytes < 1
                || memoryTtl == null || memoryTtl.isNegative() || memoryTtl.isZero()
                || redisTtl == null || redisTtl.isNegative() || redisTtl.toMillis() < 1) {
            throw new IllegalStateException("agent.feature-store requires positive bounds/TTLs and preview <= max-recent-events");
        }
    }
}
