package com.ecommerce.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationOrchestrationPropertiesTest {

    @Test
    void defaultsAreValid() {
        assertThatCode(new RecommendationOrchestrationProperties()::validate)
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsPoolMaxBelowCore() {
        RecommendationOrchestrationProperties properties = new RecommendationOrchestrationProperties();
        properties.setCoreSize(4);
        properties.setMaxSize(2);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-size must be >= core-size");
    }
}
