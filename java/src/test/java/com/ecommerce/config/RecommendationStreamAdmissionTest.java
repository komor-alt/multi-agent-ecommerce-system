package com.ecommerce.config;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.orchestrator.SupervisorOrchestrator;
import com.ecommerce.runtime.DynamicSubAgentRuntimeRegistry;
import com.ecommerce.runtime.persistence.RecommendationPersistenceMonitor;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.service.ABTestService;
import com.ecommerce.service.AgentConcurrencyGuard;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.ConstrainedToolLoopService;
import com.ecommerce.service.DemoDataService;
import com.ecommerce.service.MetricsCollector;
import com.ecommerce.service.RecommendationEvaluator;
import com.ecommerce.service.RedisFeatureStoreService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationStreamAdmissionTest {
    private final AutonomousAgentLoopService agent = mock(AutonomousAgentLoopService.class);
    private final RecommendationRunEventService events = mock(RecommendationRunEventService.class);
    private final MetricsCollector metrics = mock(MetricsCollector.class);
    private final AgentConcurrencyGuard guard = new AgentConcurrencyGuard(1);
    private final SseEmitter emitter = mock(SseEmitter.class);
    private final ToolLoopRequest incoming = ToolLoopRequest.builder()
            .request(RecommendationRequest.builder().userId("user-1").scene("homepage").build()).build();
    private final ToolLoopRequest prepared = ToolLoopRequest.builder().runId("server-run-1")
            .request(incoming.getRequest()).build();

    @BeforeEach
    void preparedRun() {
        when(agent.prepareRun(incoming)).thenReturn(prepared);
        when(events.stream("server-run-1", null)).thenReturn(emitter);
    }

    @Test
    void executorRejectionReleasesPermitClosesEmitterAndFinalizesPreparedRun() {
        RecommendationController controller = controller(task -> { throw new RejectedExecutionException("queue full"); });
        assertThatThrownBy(() -> controller.recommendWithAgentLoopStream(incoming))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertPermitReturned();
        verify(emitter).completeWithError(any(RejectedExecutionException.class));
        verify(agent).failPreparedRun("server-run-1", "dispatch_rejected");
        verify(agent, never()).runPrepared(any());
    }

    @Test
    void failedEmitterCleanupCannotSkipDurableFailureOrMaskDispatchRejection() {
        doThrow(new IllegalStateException("AsyncContext already closed")).when(emitter).completeWithError(any());
        RecommendationController controller = controller(task -> { throw new RejectedExecutionException("queue full"); });
        assertThatThrownBy(() -> controller.recommendWithAgentLoopStream(incoming))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertPermitReturned();
        verify(agent).failPreparedRun("server-run-1", "dispatch_rejected");
    }

    @Test
    void subscriptionLimitBeforeSubmissionDoesNotLeakPermitOrLeavePreparedRunRunning() {
        when(events.stream("server-run-1", null)).thenThrow(new RejectedExecutionException("subscriber limit"));
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        RecommendationController controller = controller(work::add);
        assertThatThrownBy(() -> controller.recommendWithAgentLoopStream(incoming))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        error -> assertThat(error.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        assertThat(work).isEmpty();
        assertPermitReturned();
        verify(agent).failPreparedRun("server-run-1", "dispatch_rejected");
    }

    @Test
    void prepareFailureReleasesPermitWithoutFinalizingAnUncreatedRun() {
        IllegalArgumentException invalid = new IllegalArgumentException("invalid request");
        when(agent.prepareRun(incoming)).thenThrow(invalid);
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        RecommendationController controller = controller(work::add);
        assertThatThrownBy(() -> controller.recommendWithAgentLoopStream(incoming)).isSameAs(invalid);
        assertPermitReturned();
        assertThat(work).isEmpty();
        verify(agent, never()).failPreparedRun(anyString(), anyString());
        verify(events, never()).stream(anyString(), any());
    }

    @Test
    void permitCoversQueueTimeAndIsReleasedAfterSuccessfulExecution() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        when(agent.runPrepared(prepared)).thenReturn(AgentLoopResponse.builder().runId("server-run-1")
                .status("completed").toolCalls(List.of()).build());
        RecommendationController controller = controller(work::add);
        assertThat(controller.recommendWithAgentLoopStream(incoming)).isSameAs(emitter);
        assertThat(work).hasSize(1);
        assertThat(guard.activeRunCount()).isEqualTo(1);
        verify(agent, never()).runPrepared(any());
        work.removeFirst().run();
        verify(agent).runPrepared(prepared);
        assertPermitReturned();
    }

    @Test
    void unexpectedAgentFailureStillReleasesQueuedRunsPermit() {
        ArrayDeque<Runnable> work = new ArrayDeque<>();
        when(agent.runPrepared(prepared)).thenThrow(new IllegalStateException("connector unavailable"));
        RecommendationController controller = controller(work::add);
        controller.recommendWithAgentLoopStream(incoming);
        work.removeFirst().run();
        assertPermitReturned();
        verify(metrics, never()).recordRecommendation(any());
    }

    private void assertPermitReturned() {
        assertThat(guard.snapshot()).containsEntry("active_runs", 0).containsEntry("available_permits", 1);
    }

    private RecommendationController controller(Executor executor) {
        return new RecommendationController(mock(SupervisorOrchestrator.class), mock(ABTestService.class), agent,
                metrics, mock(RecommendationEvaluator.class), mock(ConstrainedToolLoopService.class),
                mock(RedisFeatureStoreService.class), mock(DemoDataService.class), guard,
                mock(DynamicSubAgentRuntimeRegistry.class), events, mock(RecommendationPersistenceMonitor.class), executor);
    }
}
