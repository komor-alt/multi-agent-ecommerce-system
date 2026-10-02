package com.ecommerce.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterSalesExecutionPropertiesTest {

    @Test
    void defaultConfigurationIsValid() {
        assertThatCode(new AfterSalesExecutionProperties()::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsExecutorMaxSizeBelowCoreSize() {
        AfterSalesExecutionProperties properties = new AfterSalesExecutionProperties();
        properties.getExecutor().setCoreSize(8);
        properties.getExecutor().setMaxSize(4);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-size must be >= core-size");
    }

    @Test
    void rejectsSilentTaskDroppingPolicy() {
        AfterSalesExecutionProperties properties = new AfterSalesExecutionProperties();
        properties.getExecutor().setRejectionPolicy("DISCARD");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be ABORT or CALLER_RUNS");
    }
}
