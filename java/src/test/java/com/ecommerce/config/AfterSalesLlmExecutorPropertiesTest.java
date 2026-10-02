package com.ecommerce.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterSalesLlmExecutorPropertiesTest {

    @Test
    void defaultConfigurationIsValidAndPoolsAreIndependent() {
        AfterSalesLlmExecutorProperties properties = new AfterSalesLlmExecutorProperties();

        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.getIntake()).isNotSameAs(properties.getPlanner());
        assertThat(properties.getIntake().getThreadNamePrefix()).contains("intake");
        assertThat(properties.getPlanner().getThreadNamePrefix()).contains("planner");
    }

    @Test
    void rejectsMaxSizeBelowCoreSize() {
        AfterSalesLlmExecutorProperties properties = new AfterSalesLlmExecutorProperties();
        properties.getPlanner().setCoreSize(4);
        properties.getPlanner().setMaxSize(2);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("planner.max-size must be >= core-size");
    }

    @Test
    void rejectsZeroQueueCapacity() {
        AfterSalesLlmExecutorProperties properties = new AfterSalesLlmExecutorProperties();
        properties.getIntake().setQueueCapacity(0);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("intake.queue-capacity must be positive");
    }
}
