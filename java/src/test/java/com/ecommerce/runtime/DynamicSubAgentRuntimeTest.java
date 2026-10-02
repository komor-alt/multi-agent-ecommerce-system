package com.ecommerce.runtime;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.service.RecommendationPipelineState;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.AbstractList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DynamicSubAgentRuntimeTest {

    @Test
    void parallelChildrenShareOneFrontierAndReturnVersionedArtifacts() {
        RecommendationPipelineState state = new RecommendationPipelineState(
                "run-1", RecommendationRequest.builder().userId("u-1").scene("homepage").build());
        state.setRawProducts(List.of(Product.builder().productId("P001").build()));
        DynamicSubAgentRuntime runtime = new DynamicSubAgentRuntime("run-1");

        DynamicSubAgentRuntime.TaskHandle rootChild = runtime.spawn(spec(
                AgentId.PRODUCT, "search_products"), state);
        runtime.markRunning(rootChild);
        runtime.finish(rootChild, true, "handoff", List.of(observation("search_products", "P001")));

        List<DynamicSubAgentRuntime.TaskHandle> batch = runtime.spawnBatch(List.of(
                spec(AgentId.INVENTORY, "check_inventory"),
                spec(AgentId.PRODUCT, "rerank")), state);
        batch.forEach(runtime::markRunning);
        runtime.finish(batch.get(0), true, "handoff", List.of(observation("check_inventory", "P001")));
        runtime.finish(batch.get(1), true, "handoff", List.of(observation("rerank", "P001")));

        assertThat(runtime.taskViews()).hasSize(3);
        assertThat(runtime.dependencies(batch.get(0).taskId())).containsExactly(rootChild.taskId());
        assertThat(runtime.dependencies(batch.get(1).taskId())).containsExactly(rootChild.taskId());
        assertThat(batch.get(0).context()).isEqualTo(batch.get(1).context());
        assertThat(runtime.taskViews()).allMatch(task ->
                task.status() == DynamicSubAgentRuntime.TaskStatus.COMPLETED);
        assertThat(runtime.artifactViews()).hasSize(3).allMatch(artifact ->
                artifact.baseCandidateVersion() == state.getCandidateVersion());
    }

    @Test
    void snapshotWaitsUntilTaskAndEveryArtifactArePublishedTogether() throws Exception {
        var state = new RecommendationPipelineState("atomic-snapshot",
                RecommendationRequest.builder().userId("user").scene("homepage").build());
        var runtime = new DynamicSubAgentRuntime("atomic-snapshot");
        var task = runtime.spawn(spec(AgentId.PRODUCT, "search_products"), state);
        runtime.markRunning(task);
        var firstArtifactPublished = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var snapshotRequested = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(3);
        try {
            var finish = workers.submit(() -> runtime.finish(task, true, "done",
                    blockingObservations(firstArtifactPublished, releaseWriter)));
            assertThat(firstArtifactPublished.await(5, TimeUnit.SECONDS)).isTrue();
            var snapshot = workers.submit(() -> {
                snapshotRequested.countDown();
                return runtime.runView();
            });
            assertThat(snapshotRequested.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> snapshot.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);

            // Only this run is locked. Unrelated requests remain independently observable.
            var other = workers.submit(() -> new DynamicSubAgentRuntime("other-run").runView());
            assertThat(other.get(2, TimeUnit.SECONDS).runId()).isEqualTo("other-run");
            releaseWriter.countDown();
            finish.get(5, TimeUnit.SECONDS);
            var view = snapshot.get(5, TimeUnit.SECONDS);
            assertThat(view.tasks()).singleElement().satisfies(completed -> {
                assertThat(completed.status()).isEqualTo(DynamicSubAgentRuntime.TaskStatus.COMPLETED);
                assertThat(completed.completedAtEpochMs()).isPositive();
                assertThat(completed.artifactIds()).containsExactlyElementsOf(
                        view.artifacts().stream().map(DynamicSubAgentRuntime.SubAgentArtifact::artifactId).toList());
            });
            assertThat(view.artifacts()).hasSize(2);
        } finally {
            releaseWriter.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void terminalTransitionWaitsForPublicationAndCancelsRemainingTasksAsOneSnapshot() throws Exception {
        var state = new RecommendationPipelineState("atomic-terminal",
                RecommendationRequest.builder().userId("user").scene("homepage").build());
        var runtime = new DynamicSubAgentRuntime("atomic-terminal");
        var tasks = runtime.spawnBatch(List.of(spec(AgentId.PRODUCT, "search_products"),
                spec(AgentId.INVENTORY, "check_inventory")), state);
        tasks.forEach(runtime::markRunning);
        var firstArtifactPublished = new CountDownLatch(1);
        var releaseWriter = new CountDownLatch(1);
        var terminalRequested = new CountDownLatch(1);
        var workers = Executors.newFixedThreadPool(2);
        try {
            var finish = workers.submit(() -> runtime.finish(tasks.get(0), true, "done",
                    blockingObservations(firstArtifactPublished, releaseWriter)));
            assertThat(firstArtifactPublished.await(5, TimeUnit.SECONDS)).isTrue();
            var terminal = workers.submit(() -> {
                terminalRequested.countDown();
                runtime.finishRun("failed", "cancel_remaining");
                return runtime.runView();
            });
            assertThat(terminalRequested.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> terminal.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseWriter.countDown();
            finish.get(5, TimeUnit.SECONDS);
            var view = terminal.get(5, TimeUnit.SECONDS);
            assertThat(view.status()).isEqualTo(DynamicSubAgentRuntime.RunStatus.FAILED);
            assertThat(view.completedAtEpochMs()).isPositive();
            assertThat(view.tasks()).extracting(DynamicSubAgentRuntime.SubAgentTaskView::status)
                    .containsExactly(DynamicSubAgentRuntime.TaskStatus.COMPLETED, DynamicSubAgentRuntime.TaskStatus.CANCELLED);
            assertThat(view.tasks()).allSatisfy(task -> assertThat(task.completedAtEpochMs()).isPositive());
            assertThat(view.tasks().get(0).artifactIds()).containsExactlyElementsOf(
                    view.artifacts().stream().map(DynamicSubAgentRuntime.SubAgentArtifact::artifactId).toList());
        } finally {
            releaseWriter.countDown();
            workers.shutdownNow();
        }
    }

    /** Pause finish after artifact 1 is visible internally but before task.artifactIds is assigned. */
    private List<ToolObservation> blockingObservations(CountDownLatch firstArtifactPublished,
                                                       CountDownLatch releaseWriter) {
        return new AbstractList<>() {
            @Override public ToolObservation get(int index) {
                if (index == 1) {
                    firstArtifactPublished.countDown();
                    try {
                        if (!releaseWriter.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }
                return observation("search_products", "P00" + index);
            }
            @Override public int size() { return 2; }
        };
    }

    private DynamicSubAgentRuntime.SubAgentSpec spec(AgentId agent, String action) {
        return new DynamicSubAgentRuntime.SubAgentSpec(
                agent.name(), agent, "test " + action, List.of(action), 1, action);
    }

    private ToolObservation observation(String tool, String productId) {
        return ToolObservation.builder()
                .toolName(tool)
                .summary("completed " + tool)
                .data(Map.of("productIds", List.of(productId)))
                .evidenceIds(List.of("product:" + productId))
                .build();
    }
}
