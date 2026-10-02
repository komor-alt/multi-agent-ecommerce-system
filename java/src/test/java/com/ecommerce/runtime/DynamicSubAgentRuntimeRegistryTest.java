package com.ecommerce.runtime;

import com.ecommerce.config.RecommendationOrchestrationProperties;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicSubAgentRuntimeRegistryTest {

    @Test
    void newerAttemptReplacesAbandonedLocalRuntimeAndLateFinishCannotReplaceIt() {
        DynamicSubAgentRuntimeRegistry registry = new DynamicSubAgentRuntimeRegistry(
                new RecommendationOrchestrationProperties());
        DynamicSubAgentRuntime old = registry.create("recovered", 1);
        DynamicSubAgentRuntime current = registry.create("recovered", 2);
        registry.finish(old, "failed", "lease_lost");
        assertThat(registry.find("recovered")).get().extracting(DynamicSubAgentRuntime.RunView::status)
                .isEqualTo(DynamicSubAgentRuntime.RunStatus.RUNNING);
        assertThat(current.executionAttempt()).isEqualTo(2);
        assertThatThrownBy(() -> registry.create("recovered", 1)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void persistedViewWinsOverStaleLocalAttempt() {
        var store = org.mockito.Mockito.mock(com.ecommerce.runtime.persistence.RecommendationRuntimeStore.class);
        var registry = new DynamicSubAgentRuntimeRegistry(new RecommendationOrchestrationProperties(), store);
        registry.create("recovered", 1);
        var terminal = new DynamicSubAgentRuntime("recovered", 2);
        terminal.finishRun("completed", "final_answer");
        org.mockito.Mockito.when(store.findRunView("recovered")).thenReturn(java.util.Optional.of(terminal.runView()));
        assertThat(registry.find("recovered")).get().extracting(DynamicSubAgentRuntime.RunView::status)
                .isEqualTo(DynamicSubAgentRuntime.RunStatus.COMPLETED);
    }

    @Test
    void retainsOnlyConfiguredNumberOfCompletedRuns() {
        RecommendationOrchestrationProperties properties = new RecommendationOrchestrationProperties();
        properties.setMaxRetainedRuns(1);
        DynamicSubAgentRuntimeRegistry registry = new DynamicSubAgentRuntimeRegistry(properties);

        DynamicSubAgentRuntime first = registry.create("run-1");
        registry.finish(first, "completed", "final_answer");
        DynamicSubAgentRuntime second = registry.create("run-2");
        registry.finish(second, "completed", "final_answer");

        assertThat(registry.find("run-1")).isEmpty();
        assertThat(registry.find("run-2")).get().extracting(DynamicSubAgentRuntime.RunView::status)
                .isEqualTo(DynamicSubAgentRuntime.RunStatus.COMPLETED);
    }

    @Test
    void duplicateActiveRunIdIsRejected() {
        DynamicSubAgentRuntimeRegistry registry = new DynamicSubAgentRuntimeRegistry(
                new RecommendationOrchestrationProperties());
        registry.create("run-active");

        assertThatThrownBy(() -> registry.create("run-active"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already active");
    }
}
