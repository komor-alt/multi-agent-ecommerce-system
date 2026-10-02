package com.ecommerce.runtime.persistence;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Same transactional contract is exercised against H2 and opt-in isolated PostgreSQL. */
abstract class RecommendationExecutionLeaseContract {
    @Autowired RecommendationRuntimeStore store;
    @Autowired RecommendationRunRepository runs;
    @Autowired RecommendationRunEventRepository events;
    @Autowired RecommendationRunEventService eventService;
    @Autowired RecommendationOutboxRepository outbox;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void storesTheFullNormalizedRequestAndAtomicallyClaimsTheInitialAttempt() {
        ToolLoopRequest request = request();
        request.getConfig().setToolWhitelist(new ArrayList<>(List.of("get_user_profile", "final_answer")));
        var lease = store.startAndClaimRecoverableRun(request, "worker-a");
        request.getConfig().setMaxSteps(99);
        request.getConfig().getToolWhitelist().add("issue_coupon");
        assertThat(lease.request().getConfig().getMaxSteps()).isEqualTo(7);
        assertThat(lease.request().getConfig().getToolWhitelist()).containsExactly("get_user_profile", "final_answer");
        assertThat(lease.request().getRequest().getContext()).containsEntry("query", "portable charger");
        assertThat(lease.attempt()).isEqualTo(1);
        assertThat(lease.token()).isNotBlank();
        assertThat(store.findClaimableRunIds(1000)).doesNotContain(request.getRunId());
        assertThat(store.claimRun(request.getRunId(), "worker-b")).isEmpty();
        assertThat(store.renewLease(lease)).isTrue();
    }

    @Test
    void twoInstancesClaimOneQueuedRunOnlyOnce() throws Exception {
        ToolLoopRequest request = request();
        store.startRecoverableRun(request);
        assertThat(store.findClaimableRunIds(1000)).contains(request.getRunId());
        var executor = Executors.newFixedThreadPool(2);
        var start = new CountDownLatch(1);
        try {
            var first = executor.submit(() -> { start.await(); return store.claimRun(request.getRunId(), "worker-a"); });
            var second = executor.submit(() -> { start.await(); return store.claimRun(request.getRunId(), "worker-b"); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
                    .stream().filter(java.util.Optional::isPresent)).hasSize(1);
            assertThat(runs.findById(request.getRunId()).orElseThrow().getExecutionAttempt()).isEqualTo(1);
            assertThat(events.findByRunIdOrderBySequenceAsc(request.getRunId())).hasSize(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void expiredAndReplacedWorkerCannotRenewPublishFailOrBypassThroughLegacyApis() {
        var request = request();
        var old = store.startAndClaimRecoverableRun(request, "same-owner");
        var oldView = view(old, DynamicSubAgentRuntime.RunStatus.RUNNING, true);
        store.persist(oldView, old, null);
        AgentRunEvent oldEvent = event(request.getRunId(), "tool.completed");
        eventService.append(oldEvent, old);
        expire(old.runId());
        assertThat(store.renewLease(old)).isFalse();
        assertThatThrownBy(() -> store.persist(oldView, old, null)).isInstanceOf(StaleExecutionLeaseException.class);
        assertThatThrownBy(() -> store.failRun(old.runId(), "late_failure", old)).isInstanceOf(StaleExecutionLeaseException.class);
        assertThatThrownBy(() -> eventService.append(oldEvent, old)).isInstanceOf(StaleExecutionLeaseException.class);

        var replacement = store.claimRun(old.runId(), "same-owner").orElseThrow();
        assertThat(replacement.attempt()).isEqualTo(2);
        assertThat(replacement.token()).isNotEqualTo(old.token());
        assertThat(store.renewLease(old)).isFalse();
        assertThatThrownBy(() -> store.persist(oldView)).isInstanceOf(StaleExecutionLeaseException.class);
        assertThatThrownBy(() -> store.failRun(old.runId(), "unleased_failure")).isInstanceOf(StaleExecutionLeaseException.class);
        assertThatThrownBy(() -> eventService.append(event(old.runId(), "unleased_event")))
                .isInstanceOf(StaleExecutionLeaseException.class);
        assertThat(store.failUnclaimedRun(old.runId(), "dispatch_failed")).isFalse();
        store.persist(view(replacement, DynamicSubAgentRuntime.RunStatus.RUNNING, true), replacement, null);
        var restored = store.findRunView(old.runId()).orElseThrow();
        assertThat(restored.tasks()).hasSize(2);
        assertThat(restored.tasks().stream().filter(task -> task.taskId().contains(":attempt:1:")))
                .singleElement().satisfies(task -> assertThat(task.status()).isEqualTo(DynamicSubAgentRuntime.TaskStatus.CANCELLED));
        assertThat(restored.tasks().stream().filter(task -> task.taskId().contains(":attempt:2:")))
                .singleElement().satisfies(task -> assertThat(task.status()).isEqualTo(DynamicSubAgentRuntime.TaskStatus.RUNNING));
    }

    @Test
    void oldAttemptTaskIdsCannotBeSubmittedUsingANewLease() {
        var first = store.startAndClaimRecoverableRun(request(), "worker");
        var oldView = view(first, DynamicSubAgentRuntime.RunStatus.RUNNING, true);
        expire(first.runId());
        var second = store.claimRun(first.runId(), "worker").orElseThrow();
        assertThatThrownBy(() -> store.persist(oldView, second, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TASK_ATTEMPT_MISMATCH");
    }

    @Test
    void exhaustingAttemptsAtomicallyFailsTheRunAndRecordsTheFinalEventOnce() {
        var lease = store.startAndClaimRecoverableRun(request(), "worker");
        for (int attempt = 1; attempt <= 3; attempt++) {
            assertThat(lease.attempt()).isEqualTo(attempt);
            expire(lease.runId());
            if (attempt < 3) lease = store.claimRun(lease.runId(), "worker").orElseThrow();
        }
        assertThat(store.claimRun(lease.runId(), "replacement")).isEmpty();
        assertThat(store.claimRun(lease.runId(), "replacement")).isEmpty();
        var row = runs.findById(lease.runId()).orElseThrow();
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getStopReason()).isEqualTo("execution_attempts_exhausted");
        assertThat(events.findByRunIdOrderBySequenceAsc(lease.runId())).extracting(RecommendationRunEventEntity::getName)
                .containsExactly("run.attempt_started", "run.attempt_started", "run.attempt_started", "run.failed");
        assertThat(store.findClaimableRunIds(1000)).doesNotContain(lease.runId());
    }

    @Test
    void terminalProjectionAndFinalEventCommitTogetherAndRollbackTogether() {
        var lease = store.startAndClaimRecoverableRun(request(), "worker");
        long beforeVersion = runs.findById(lease.runId()).orElseThrow().getStateVersion();
        var terminal = view(lease, DynamicSubAgentRuntime.RunStatus.COMPLETED, false);
        AgentRunEvent mismatchedEvent = event("different-run", "run.completed");
        assertThatThrownBy(() -> store.persist(terminal, lease, mismatchedEvent))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(runs.findById(lease.runId()).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(runs.findById(lease.runId()).orElseThrow().getStateVersion()).isEqualTo(beforeVersion);
        assertThat(events.findByRunIdOrderBySequenceAsc(lease.runId())).hasSize(1);
        assertThat(outbox.existsByDedupKey("run:" + lease.runId() + ":status:COMPLETED")).isFalse();

        var finalEvent = event(lease.runId(), "run.completed");
        store.persist(terminal, lease, finalEvent);
        assertThat(runs.findById(lease.runId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(events.findByRunIdOrderBySequenceAsc(lease.runId())).extracting(RecommendationRunEventEntity::getName)
                .containsExactly("run.attempt_started", "run.completed");
        assertThat(finalEvent.getSequence()).isEqualTo(2);
        assertThat(store.renewLease(lease)).isFalse();
    }

    @Test
    void dispatchFailureCanCancelAnUnclaimedRunButCannotCancelAnyClaimedAttempt() {
        var queued = request();
        store.startRecoverableRun(queued);
        assertThatThrownBy(() -> store.failRun(queued.getRunId(), "unleased"))
                .isInstanceOf(StaleExecutionLeaseException.class);
        assertThat(store.failUnclaimedRun(queued.getRunId(), "dispatch_failed")).isTrue();
        assertThat(store.failUnclaimedRun(queued.getRunId(), "dispatch_failed")).isFalse();
        assertThat(store.claimRun(queued.getRunId(), "worker")).isEmpty();
        var claimed = store.startAndClaimRecoverableRun(request(), "worker");
        assertThat(store.failUnclaimedRun(claimed.runId(), "dispatch_failed")).isFalse();
        assertThat(store.failRun(claimed.runId(), "worker_failed", claimed)).isTrue();
        assertThat(store.failRun(claimed.runId(), "worker_failed", claimed)).isFalse();
    }

    @Test
    void nonReadOnlyToolCannotBeQueuedForAutomaticReplay() {
        var unsafe = request();
        unsafe.getConfig().setToolWhitelist(List.of("issue_coupon"));
        assertThatThrownBy(() -> store.startRecoverableRun(unsafe)).isInstanceOf(IllegalArgumentException.class);
        assertThat(runs.existsById(unsafe.getRunId())).isFalse();
    }

    @Test
    void corruptReplayRequestFailsOnceWithoutStarvingOtherQueuedRuns() {
        var corrupted = request();
        var healthy = request();
        store.startRecoverableRun(corrupted);
        store.startRecoverableRun(healthy);
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                runs.findByIdForUpdate(corrupted.getRunId()).orElseThrow().setExecutionRequestJson("{broken-json"));
        assertThat(store.claimRun(corrupted.getRunId(), "worker")).isEmpty();
        assertThat(store.claimRun(corrupted.getRunId(), "worker")).isEmpty();
        assertThat(runs.findById(corrupted.getRunId()).orElseThrow().getStopReason()).isEqualTo("invalid_recovery_request");
        assertThat(events.findByRunIdOrderBySequenceAsc(corrupted.getRunId())).hasSize(1);
        assertThat(store.findClaimableRunIds(1000)).contains(healthy.getRunId()).doesNotContain(corrupted.getRunId());
        assertThat(store.claimRun(healthy.getRunId(), "worker")).isPresent();
    }

    @Test
    void finalStateCannotContradictItsTerminalEventOrReuseANonTerminalEventId() {
        var lease = store.startAndClaimRecoverableRun(request(), "worker");
        var terminal = view(lease, DynamicSubAgentRuntime.RunStatus.COMPLETED, false);
        AgentRunEvent failedEvent = event(lease.runId(), "run.failed");
        failedEvent.setStatus("failed");
        assertThatThrownBy(() -> store.persist(terminal, lease, failedEvent))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("TERMINAL_EVENT_STATUS_MISMATCH");
        var previous = event(lease.runId(), "tool.completed");
        eventService.append(previous, lease);
        var reused = event(lease.runId(), "run.completed");
        reused.setEventId(previous.getEventId());
        assertThatThrownBy(() -> store.persist(terminal, lease, reused))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EVENT_ID_CONFLICT");
        assertThat(runs.findById(lease.runId()).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(events.findByRunIdOrderBySequenceAsc(lease.runId())).extracting(RecommendationRunEventEntity::getName)
                .containsExactly("run.attempt_started", "tool.completed");
    }

    private void expire(String runId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                runs.findByIdForUpdate(runId).orElseThrow().setExecutionLeaseUntil(Instant.EPOCH));
    }

    @Test
    void leasedAppendCannotPublishTerminalEventsWithoutFinalizingTheRun() {
        var lease = store.startAndClaimRecoverableRun(request(), "worker");
        List<AgentRunEvent> forbidden = new ArrayList<>();
        for (String name : List.of("run.completed", "run.failed", "run.blocked", "run.cancelled")) {
            forbidden.add(event(lease.runId(), name));
        }
        for (String type : List.of("run_completed", "run_failed")) {
            var event = event(lease.runId(), "nonterminal_name");
            event.setType(type);
            forbidden.add(event);
        }
        for (AgentRunEvent event : forbidden) {
            assertThatThrownBy(() -> eventService.append(event, lease))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("ATOMIC_RUN_FINALIZATION");
        }
        assertThat(runs.findById(lease.runId()).orElseThrow().getStatus()).isEqualTo("RUNNING");
        assertThat(events.findByRunIdOrderBySequenceAsc(lease.runId()))
                .extracting(RecommendationRunEventEntity::getName).containsExactly("run.attempt_started");
    }

    @Test
    void terminalRetryConfirmsOnlyTheExactPreviouslyCommittedEvent() {
        var lease = store.startAndClaimRecoverableRun(request(), "worker");
        var completed = view(lease, DynamicSubAgentRuntime.RunStatus.COMPLETED, false);
        var committed = event(lease.runId(), "run.completed");
        store.persist(completed, lease, committed);
        long version = runs.findById(lease.runId()).orElseThrow().getStateVersion();
        committed.setSequence(0);
        store.persist(completed, lease, committed);
        assertThat(committed.getSequence()).isEqualTo(2);

        var newFailure = event(lease.runId(), "run.failed");
        newFailure.setStatus("failed");
        assertThatThrownBy(() -> store.persist(completed, lease, newFailure))
                .isInstanceOf(IllegalArgumentException.class);
        var uncommitted = event(lease.runId(), "run.completed");
        assertThatThrownBy(() -> store.persist(completed, lease, uncommitted))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("NOT_COMMITTED");

        var changed = event(lease.runId(), "run.completed");
        changed.setEventId(committed.getEventId());
        changed.setType("different_type");
        assertThatThrownBy(() -> store.persist(completed, lease, changed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EVENT_ID_CONFLICT");
        changed.setType(committed.getType());
        changed.setData(Map.of("changed", true));
        assertThatThrownBy(() -> store.persist(completed, lease, changed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("EVENT_ID_CONFLICT");

        store.persist(view(lease, DynamicSubAgentRuntime.RunStatus.RUNNING, false), lease, null);
        assertThat(runs.findById(lease.runId()).orElseThrow().getStatus()).isEqualTo("COMPLETED");
        assertThat(runs.findById(lease.runId()).orElseThrow().getStateVersion()).isEqualTo(version);
        assertThat(events.findByRunIdOrderBySequenceAsc(lease.runId()))
                .extracting(RecommendationRunEventEntity::getName)
                .containsExactly("run.attempt_started", "run.completed");
    }

    private static ToolLoopRequest request() {
        return ToolLoopRequest.builder().runId(UUID.randomUUID().toString())
                .request(RecommendationRequest.builder().userId("lease-user").scene("homepage")
                        .context(Map.of("query", "portable charger")).build())
                .config(ToolLoopConfig.builder().maxSteps(7)
                        .toolWhitelist(List.of("get_user_profile", "search_products", "final_answer")).build()).build();
    }

    private static AgentRunEvent event(String runId, String name) {
        return AgentRunEvent.builder().requestId(runId).name(name).type("runtime").status("success").build();
    }

    private static DynamicSubAgentRuntime.RunView view(RecommendationExecutionLease lease,
                                                       DynamicSubAgentRuntime.RunStatus status, boolean withTask) {
        long now = System.currentTimeMillis();
        String id = lease.runId() + ":attempt:" + lease.attempt() + ":subagent:1";
        var task = new DynamicSubAgentRuntime.SubAgentTaskView(id, "root:" + lease.runId(), "ROOT", "PROFILE",
                AgentId.PROFILE, "read profile", List.of("get_user_profile"), "get_user_profile", List.of(),
                DynamicSubAgentRuntime.TaskStatus.RUNNING,
                new DynamicSubAgentRuntime.ContextSnapshot(0, "homepage", Map.of(), List.of()), List.of(), "", now, now, 0);
        return new DynamicSubAgentRuntime.RunView(lease.runId(), status, "done", now,
                status == DynamicSubAgentRuntime.RunStatus.RUNNING ? 0 : now,
                withTask ? List.of(task) : List.of(), List.of());
    }
}
