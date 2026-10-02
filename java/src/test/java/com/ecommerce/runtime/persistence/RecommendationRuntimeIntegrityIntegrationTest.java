package com.ecommerce.runtime.persistence;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.service.RecommendationPipelineState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RecommendationRuntimeStore.class, RecommendationRuntimeIntegrityIntegrationTest.JacksonConfiguration.class})
class RecommendationRuntimeIntegrityIntegrationTest {
    @Autowired RecommendationRuntimeStore store;
    @Autowired RecommendationRunRepository runs;
    @Autowired RecommendationOutboxRepository outbox;
    @Autowired RecommendationRunEventRepository events;

    @Test
    void changedArtifactRollsBackWholeSnapshotIncludingRunVersionAndOutbox() {
        String id = UUID.randomUUID().toString();
        DynamicSubAgentRuntime runtime = completedTask(id);
        store.persist(runtime.runView());
        long version = runs.findById(id).orElseThrow().getStateVersion();
        long eventCount = outbox.count();
        var view = runtime.runView();
        var original = view.artifacts().get(0);
        var changed = new DynamicSubAgentRuntime.SubAgentArtifact(original.artifactId(), original.taskId(),
                original.producerRole(), original.type(), original.baseCandidateVersion(),
                Map.of("tampered", true), original.evidenceIds(), original.createdAtEpochMs());
        var corrupt = new DynamicSubAgentRuntime.RunView(id, DynamicSubAgentRuntime.RunStatus.COMPLETED,
                "changed", view.createdAtEpochMs(), System.currentTimeMillis(), view.tasks(), List.of(changed));
        assertThatThrownBy(() -> store.persist(corrupt))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("ARTIFACT_IMMUTABLE");
        assertThat(runs.findById(id).orElseThrow().getStateVersion()).isEqualTo(version);
        assertThat(runs.findById(id).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(outbox.count()).isEqualTo(eventCount);
        assertThat(store.findRunView(id).orElseThrow().artifacts()).containsExactly(original);

        // The fallback must not deserialize or reuse the rejected in-memory artifact snapshot.
        assertThat(store.failRun(id, "projection_rejected")).isTrue();
        assertThat(runs.findById(id).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(store.findRunView(id).orElseThrow().artifacts()).containsExactly(original);
        assertThat(events.findByRunIdOrderBySequenceAsc(id)).singleElement()
                .satisfies(event -> assertThat(event.getName()).isEqualTo("run.failed"));
    }

    @Test
    void terminalRunIgnoresContradictoryTerminalAndLateNewTasksWithoutEmittingFalseEvents() {
        String id = UUID.randomUUID().toString();
        DynamicSubAgentRuntime runtime = completedTask(id);
        runtime.finishRun("completed", "original outcome");
        store.persist(runtime.runView());
        var saved = store.findRunView(id).orElseThrow();
        long version = runs.findById(id).orElseThrow().getStateVersion();
        long eventCount = outbox.count();
        var contradicted = new DynamicSubAgentRuntime.RunView(id, DynamicSubAgentRuntime.RunStatus.FAILED,
                "late failure", saved.createdAtEpochMs(), System.currentTimeMillis(), List.of(), List.of());
        store.persist(contradicted);
        assertThat(store.findRunView(id)).contains(saved);
        assertThat(runs.findById(id).orElseThrow().getStateVersion()).isEqualTo(version);
        assertThat(outbox.count()).isEqualTo(eventCount);
        assertThat(store.failRun(id, "late_failed_fallback")).isFalse();
        assertThat(store.findRunView(id)).contains(saved);
        assertThat(outbox.count()).isEqualTo(eventCount);
    }

    @Test
    void failureFallbackCancelsActiveTasksAndCommitsOneOrderedTerminalEvent() {
        String id = UUID.randomUUID().toString();
        DynamicSubAgentRuntime runtime = completedTask(id);
        var state = new RecommendationPipelineState(id,
                RecommendationRequest.builder().userId("user").scene("homepage").build());
        var activeTasks = runtime.spawnBatch(List.of(
                new DynamicSubAgentRuntime.SubAgentSpec("PRODUCT", AgentId.PRODUCT,
                        "running task", List.of("recall_products"), 2, "recall_products"),
                new DynamicSubAgentRuntime.SubAgentSpec("COPY", AgentId.COPY,
                        "pending task", List.of("generate_copy"), 2, "generate_copy")), state);
        runtime.markRunning(activeTasks.get(0));
        var staleView = runtime.runView();
        store.persist(staleView);
        events.saveAndFlush(RecommendationRunEventEntity.builder().id(UUID.randomUUID().toString()).runId(id)
                .sequence(3).type("run_started").name("run.started").status("running")
                .dataJson("{}").createdAt(Instant.now()).build());

        assertThat(store.failRun(id, "executor_failed")).isTrue();
        assertThat(store.failRun(id, "different_retry_reason")).isFalse();
        store.persist(staleView);
        var restored = store.findRunView(id).orElseThrow();
        assertThat(restored.status()).isEqualTo(DynamicSubAgentRuntime.RunStatus.FAILED);
        assertThat(restored.stopReason()).isEqualTo("executor_failed");
        assertThat(restored.tasks().stream().filter(task -> task.agent() == AgentId.PROFILE))
                .singleElement().satisfies(task -> assertThat(task.status())
                        .isEqualTo(DynamicSubAgentRuntime.TaskStatus.COMPLETED));
        assertThat(restored.tasks().stream().filter(task -> task.agent() != AgentId.PROFILE)).hasSize(2)
                .allSatisfy(task -> {
                    assertThat(task.status()).isEqualTo(DynamicSubAgentRuntime.TaskStatus.CANCELLED);
                    assertThat(task.stopReason()).isEqualTo("executor_failed");
                    assertThat(task.completedAtEpochMs()).isPositive();
                });
        assertThat(events.findByRunIdOrderBySequenceAsc(id)).extracting(RecommendationRunEventEntity::getSequence)
                .containsExactly(3, 4);
        assertThat(events.findTopByRunIdOrderBySequenceDesc(id).orElseThrow().getName()).isEqualTo("run.failed");
        assertThat(outbox.findAll().stream().filter(event -> event.getDedupKey().equals("run:" + id + ":status:FAILED")))
                .hasSize(1);
        assertThat(outbox.findAll().stream().filter(event -> event.getDedupKey().startsWith("task:" + id)
                && event.getDedupKey().endsWith(":status:CANCELLED"))).hasSize(2);
    }

    @Test
    void concurrentFailureFallbackHasOneWinnerAndOneTerminalEvent() throws Exception {
        String id = UUID.randomUUID().toString();
        store.startRun(id, RecommendationRequest.builder().userId("user").scene("homepage").build());
        var workers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> {
                start.await();
                return store.failRun(id, "first_failure");
            });
            var second = workers.submit(() -> {
                start.await();
                return store.failRun(id, "second_failure");
            });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
            assertThat(events.findByRunIdOrderBySequenceAsc(id)).hasSize(1);
            assertThat(runs.findById(id).orElseThrow().getStateVersion()).isEqualTo(1);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void concurrentRunCreationHasOneWinnerAndNeverMergesTheLoserRequest() throws Exception {
        String id = UUID.randomUUID().toString();
        var workers = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = workers.submit(() -> createAfter(start, id, "first"));
            var second = workers.submit(() -> createAfter(start, id, "second"));
            start.countDown();
            String winnerA = first.get(10, TimeUnit.SECONDS);
            String winnerB = second.get(10, TimeUnit.SECONDS);
            assertThat(List.of(winnerA, winnerB).stream().filter(value -> !value.isEmpty())).hasSize(1);
            String winner = winnerA.isEmpty() ? winnerB : winnerA;
            assertThat(runs.findById(id).orElseThrow().getRequestJson()).contains(winner);
            assertThat(outbox.findAll().stream().filter(event -> event.getAggregateId().equals(id)))
                    .singleElement().satisfies(event -> assertThat(event.getPayloadJson()).contains(winner));
        } finally {
            workers.shutdownNow();
        }
    }

    private String createAfter(CountDownLatch start, String id, String user) throws InterruptedException {
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
        try {
            store.startRun(id, RecommendationRequest.builder().userId(user).scene("homepage").build());
            return user;
        } catch (RuntimeException duplicate) {
            return "";
        }
    }

    private DynamicSubAgentRuntime completedTask(String id) {
        var request = RecommendationRequest.builder().userId("user").scene("homepage").build();
        store.startRun(id, request);
        var runtime = new DynamicSubAgentRuntime(id);
        var task = runtime.spawn(new DynamicSubAgentRuntime.SubAgentSpec("PROFILE", AgentId.PROFILE,
                "Load user profile", List.of("load_profile"), 2, "load_profile"),
                new RecommendationPipelineState(id, request));
        runtime.markRunning(task);
        runtime.finish(task, true, "done", List.of(ToolObservation.builder().toolName("load_profile")
                .data(Map.of("segment", "中文-" + "x".repeat(3000))).evidenceIds(List.of("profile-1")).build()));
        return runtime;
    }

    @TestConfiguration
    static class JacksonConfiguration {
        @Bean ObjectMapper objectMapper() { return new ObjectMapper().findAndRegisterModules(); }
    }
}
