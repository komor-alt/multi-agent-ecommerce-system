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
@ConfigurationProperties(prefix = "agent.limits")
public class RuntimeLimitsProperties {
    private Duration runTimeout = Duration.ofSeconds(60);
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(15);
    private int maxSteps = 32;
    private int maxItems = 100;

    @PostConstruct
    void validate() {
        if (runTimeout == null || runTimeout.isNegative() || runTimeout.isZero()
                || connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()
                || maxSteps < 1 || maxItems < 1) {
            throw new IllegalStateException("agent.limits durations and counts must be positive");
        }
    }
}
