package com.ecommerce.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AgentEvaluationProfileTest {
    @AfterEach
    void clearOptIn() {
        System.clearProperty(AgentEvaluationProfile.ENABLE_PROPERTY);
    }

    @Test
    void productionDefaultsRemainGuardedParallelMultiAgent() {
        AgentEvaluationProfile.Settings defaults = AgentEvaluationProfile.current();

        assertThat(AgentEvaluationProfile.isActive()).isFalse();
        assertThat(defaults.topology()).isEqualTo(AgentEvaluationProfile.Topology.MULTI);
        assertThat(defaults.vetoEnabled()).isTrue();
        assertThat(defaults.pathGuardEnabled()).isTrue();
        assertThat(defaults.parallelSchedulingEnabled()).isTrue();
    }

    @Test
    void evaluationProfileIsScopedAndDoesNotLeakAcrossNestedRunsOrFailures() {
        System.setProperty(AgentEvaluationProfile.ENABLE_PROPERTY, "true");
        AgentEvaluationProfile.Settings outer = new AgentEvaluationProfile.Settings(
                AgentEvaluationProfile.Topology.MULTI, true, false, true, 1L);
        AgentEvaluationProfile.Settings inner = new AgentEvaluationProfile.Settings(
                AgentEvaluationProfile.Topology.SINGLE, false, true, false, 2L);

        assertThatThrownBy(() -> AgentEvaluationProfile.run(outer, () -> {
            assertThat(AgentEvaluationProfile.current()).isEqualTo(outer);
            AgentEvaluationProfile.run(inner, () -> {
                assertThat(AgentEvaluationProfile.current()).isEqualTo(inner);
                return null;
            });
            assertThat(AgentEvaluationProfile.current()).isEqualTo(outer);
            throw new IllegalStateException("expected");
        })).hasMessage("expected");

        assertThat(AgentEvaluationProfile.isActive()).isFalse();
        assertThat(AgentEvaluationProfile.current()).isEqualTo(AgentEvaluationProfile.Settings.production());
    }

    @Test
    void capturedProfilePropagatesToWorkerAndIsRemovedBeforeTheNextTask() throws Exception {
        System.setProperty(AgentEvaluationProfile.ENABLE_PROPERTY, "true");
        AgentEvaluationProfile.Settings evaluation = new AgentEvaluationProfile.Settings(
                AgentEvaluationProfile.Topology.MULTI, false, false, true, 7L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            var workerTask = AgentEvaluationProfile.run(evaluation,
                    () -> AgentEvaluationProfile.wrapCurrent(AgentEvaluationProfile::current));

            assertThat(executor.submit(workerTask::get).get()).isEqualTo(evaluation);
            assertThat(executor.submit(AgentEvaluationProfile::current).get())
                    .isEqualTo(AgentEvaluationProfile.Settings.production());

            var failingTask = AgentEvaluationProfile.run(evaluation,
                    () -> AgentEvaluationProfile.wrapCurrent(() -> {
                        assertThat(AgentEvaluationProfile.current()).isEqualTo(evaluation);
                        throw new IllegalStateException("worker failure");
                    }));
            assertThatThrownBy(() -> executor.submit(failingTask::get).get())
                    .hasRootCauseMessage("worker failure");
            assertThat(executor.submit(AgentEvaluationProfile::current).get())
                    .isEqualTo(AgentEvaluationProfile.Settings.production());
            assertThat(AgentEvaluationProfile.isActive()).isFalse();
        } finally {
            executor.shutdownNow();
        }
    }
}
