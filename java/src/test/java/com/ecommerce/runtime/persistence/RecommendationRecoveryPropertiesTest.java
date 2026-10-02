package com.ecommerce.runtime.persistence;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationRecoveryPropertiesTest {
    @Test
    void renewalTimeoutMustBePositiveAndShorterThanTheLease() {
        var properties = new RecommendationRecoveryProperties();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        properties.setLeaseDuration(Duration.ofSeconds(6));
        properties.setHeartbeatInterval(Duration.ofSeconds(1));
        assertThatCode(properties::validate).doesNotThrowAnyException();
        properties.setRenewalTimeoutSeconds(0);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setRenewalTimeoutSeconds(6);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
        properties.setRenewalTimeoutSeconds(7);
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class);
    }
}
