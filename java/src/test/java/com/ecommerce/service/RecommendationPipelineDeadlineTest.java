package com.ecommerce.service;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.BlackboardField;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class RecommendationPipelineDeadlineTest {
    @Test
    void unfinishedWorkIsCancelledWhenRemainingRunBudgetExpires() {
        RecommendationPipelineState state = state();
        state.setDeadline(Duration.ofMillis(20));
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertTimeoutPreemptively(Duration.ofSeconds(2), () ->
                assertThatThrownBy(() -> state.await(pending)).isInstanceOf(RunDeadlineExceededException.class));
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    void futureIsCancelledWhenRunWasAlreadyClosedBeforeAwait() {
        RecommendationPipelineState state = state();
        state.close();
        CompletableFuture<String> pending = new CompletableFuture<>();
        assertThatThrownBy(() -> state.await(pending)).isInstanceOf(RunDeadlineExceededException.class);
        assertThat(pending.isCancelled()).isTrue();
    }

    @Test
    void futureIsCancelledWhenTheWholeBudgetWasSpentInExecutorQueue() {
        RecommendationPipelineState state = state();
        state.setDeadline(Duration.ZERO);
        CompletableFuture<String> queued = new CompletableFuture<>();
        assertThatThrownBy(() -> state.await(queued)).isInstanceOf(RunDeadlineExceededException.class);
        assertThat(queued.isCancelled()).isTrue();
    }

    @Test
    void interruptedCallerKeepsInterruptStatusAndCancelsOutstandingFuture() {
        RecommendationPipelineState state = state();
        CompletableFuture<String> pending = new CompletableFuture<>();
        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> state.await(pending)).isInstanceOf(RunDeadlineExceededException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(pending.isCancelled()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void aResultArrivingAfterCloseCannotEscapeAsSuccessfulOutput() throws Exception {
        RecommendationPipelineState state = state();
        state.setDeadline(Duration.ofSeconds(5));
        CompletableFuture<String> result = new CompletableFuture<>();
        CountDownLatch waiting = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<?> outcome = worker.submit(() -> {
                waiting.countDown();
                assertThatThrownBy(() -> state.await(result)).isInstanceOf(RunDeadlineExceededException.class);
            });
            assertThat(waiting.await(1, TimeUnit.SECONDS)).isTrue();
            state.close();
            result.complete("late result");
            outcome.get(2, TimeUnit.SECONDS);
        } finally {
            worker.shutdownNow();
        }
    }

    @Test
    void lateSpecialistPatchesAndDirectAgentWritesLeaveClosedStateUnchanged() {
        RecommendationPipelineState state = state();
        Product original = Product.builder().productId("original").build();
        state.setRawProducts(List.of(original));
        long version = state.getCandidateVersion();
        state.close();
        assertThatThrownBy(() -> state.applyCandidatePatch(CandidateStatePatch.rerank(version, List.of(original))))
                .isInstanceOf(RunDeadlineExceededException.class);
        assertThatThrownBy(() -> state.applyCandidatePatch(CandidateStatePatch.inventory(
                version, Set.of("original"), Map.of("eligible", true))))
                .isInstanceOf(RunDeadlineExceededException.class);
        assertThatThrownBy(() -> state.write(AgentId.COPY, BlackboardField.COPIES, List.of(Map.of("text", "late"))))
                .isInstanceOf(RunDeadlineExceededException.class);
        assertThat(state.getRankedProducts()).isNull();
        assertThat(state.getAvailableIds()).isNull();
        assertThat(state.getCopies()).isNull();
        assertThat(state.getRawProducts()).containsExactly(original);
        assertThat(state.getCandidateVersion()).isEqualTo(version);
    }

    @Test
    void expiredRunRejectsEvenCurrentVersionPatch() {
        RecommendationPipelineState state = state();
        state.setRawProducts(List.of());
        state.setDeadline(Duration.ZERO);
        assertThatThrownBy(() -> state.applyCandidatePatch(CandidateStatePatch.rerank(state.getCandidateVersion(), List.of())))
                .isInstanceOf(RunDeadlineExceededException.class);
        assertThat(state.getRankedProducts()).isNull();
    }

    @Test
    void successfulAndFailedFuturesPreserveTheirResultsWithinBudget() {
        RecommendationPipelineState state = state();
        state.setDeadline(Duration.ofSeconds(5));
        assertThat(state.await(CompletableFuture.completedFuture("ok"))).isEqualTo("ok");
        RuntimeException failure = new IllegalStateException("upstream unavailable");
        assertThatThrownBy(() -> state.await(CompletableFuture.failedFuture(failure))).isSameAs(failure);
    }

    private static RecommendationPipelineState state() {
        return new RecommendationPipelineState("run-deadline", RecommendationRequest.builder().scene("homepage").build());
    }
}
