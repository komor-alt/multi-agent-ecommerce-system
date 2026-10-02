package com.ecommerce.runtime.persistence;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.service.ScenePathEnforcer;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

/**
 * Durable projection of the in-process recommendation runtime.
 *
 * <p>The synchronous Agent loop remains the executor for now, while this store
 * makes its lifecycle recoverable and provides an outbox boundary for a future
 * Kafka/RabbitMQ worker transport.</p>
 */
@Service
public class RecommendationRuntimeStore {
    @PersistenceContext
    private EntityManager entityManager;
    private final RecommendationRunRepository runRepository;
    private final RecommendationTaskRepository taskRepository;
    private final RecommendationArtifactRepository artifactRepository;
    private final RecommendationOutboxRepository outboxRepository;
    private final ObjectMapper objectMapper;
    @Autowired(required = false)
    private RecommendationRecoveryProperties recoveryProperties = new RecommendationRecoveryProperties();

    public RecommendationRuntimeStore(
            RecommendationRunRepository runRepository,
            RecommendationTaskRepository taskRepository,
            RecommendationArtifactRepository artifactRepository,
            RecommendationOutboxRepository outboxRepository,
            ObjectMapper objectMapper) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
        this.artifactRepository = artifactRepository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public void startRun(String runId, RecommendationRequest request) {
        insertRun(runId, request, null);
    }

    @Transactional
    public void startRecoverableRun(ToolLoopRequest normalized) {
        validateRecoverableRequest(normalized);
        insertRun(normalized.getRunId(), normalized.getRequest(), normalized);
    }

    /** Avoid the insert/claim gap where a recovery scanner could take a synchronous HTTP request. */
    @Transactional
    public RecommendationExecutionLease startAndClaimRecoverableRun(ToolLoopRequest normalized, String ownerId) {
        validateRecoverableRequest(normalized);
        RecommendationRunEntity run = insertRun(normalized.getRunId(), normalized.getRequest(), normalized);
        return claimLocked(run, ownerId, RecommendationDatabaseClock.now(entityManager)).orElseThrow();
    }

    private RecommendationRunEntity insertRun(String runId, RecommendationRequest request, ToolLoopRequest executionRequest) {
        if (runRepository.existsById(runId)) {
            throw new IllegalStateException("RECOMMENDATION_RUN_ALREADY_EXISTS:" + runId);
        }
        Instant now = RecommendationDatabaseClock.now(entityManager);
        RecommendationRunEntity run = RecommendationRunEntity.builder()
                .id(runId)
                .scene(request == null || request.getScene() == null ? "homepage" : request.getScene())
                .status(DynamicSubAgentRuntime.RunStatus.RUNNING.name())
                .stopReason("")
                .requestJson(writeJson(request == null ? Map.of() : request))
                .recoverable(executionRequest != null)
                .executionRequestJson(executionRequest == null ? null : writeJson(executionRequest))
                .stateVersion(0)
                .createdAt(now)
                .updatedAt(now)
                .build();
        // Insert-only: JpaRepository.save() can merge an existing assigned ID after an exists/insert race.
        // The primary key is the cross-instance arbiter; the loser rolls back with its outbox entry.
        entityManager.persist(run);
        entityManager.flush();
        enqueue("run:" + runId + ":created", "RUN", runId, "RUN_CREATED", run);
        return run;
    }

    @Transactional
    public Optional<RecommendationExecutionLease> claimRun(String runId, String ownerId) {
        RecommendationRunEntity run = runRepository.findByIdForUpdate(runId).orElse(null);
        if (run == null) return Optional.empty();
        return claimLocked(run, ownerId, RecommendationDatabaseClock.now(entityManager));
    }

    private Optional<RecommendationExecutionLease> claimLocked(RecommendationRunEntity run, String ownerId, Instant now) {
        recoveryProperties.validate();
        if (ownerId == null || ownerId.isBlank() || ownerId.length() > 255) {
            throw new IllegalArgumentException("RECOMMENDATION_EXECUTION_OWNER_REQUIRED");
        }
        if (!run.isRecoverable() || !"RUNNING".equals(run.getStatus())) return Optional.empty();
        if (run.getExecutionToken() != null && run.getExecutionLeaseUntil() != null
                && run.getExecutionLeaseUntil().isAfter(now)) return Optional.empty();
        if (run.getExecutionAttempt() >= recoveryProperties.getMaxAttempts()) {
            failLocked(run, "execution_attempts_exhausted", now);
            return Optional.empty();
        }
        ToolLoopRequest request;
        try {
            request = readJson(run.getExecutionRequestJson(), ToolLoopRequest.class);
            validateRecoverableRequest(request);
            if (!run.getId().equals(request.getRunId())) throw new IllegalStateException("RECOMMENDATION_REPLAY_RUN_MISMATCH");
        } catch (IllegalArgumentException | IllegalStateException invalidRequest) {
            // A corrupt/obsolete replay payload must leave the queue, rather than failing every scan forever.
            // Deliberately keep database operations outside this catch: transient DB errors must not be misclassified.
            failLocked(run, "invalid_recovery_request", now);
            return Optional.empty();
        }
        cancelActiveTasks(run.getId(), "execution_attempt_recovered", now);
        run.setExecutionAttempt(Math.addExact(run.getExecutionAttempt(), 1));
        run.setExecutionToken(UUID.randomUUID().toString());
        run.setExecutionOwner(ownerId);
        run.setExecutionLeaseUntil(now.plus(recoveryProperties.getLeaseDuration()));
        run.setStateVersion(run.getStateVersion() + 1);
        appendEventLocked(run, AgentRunEvent.builder().requestId(run.getId())
                .name("run.attempt_started").type("run_attempt_started").status("running")
                .summary("Recommendation execution attempt claimed")
                .data(Map.of("attempt", run.getExecutionAttempt())).build(), now);
        enqueue("run:" + run.getId() + ":attempt:" + run.getExecutionAttempt(), "RUN", run.getId(),
                "RUN_ATTEMPT_STARTED", Map.of("runId", run.getId(), "attempt", run.getExecutionAttempt()));
        return Optional.of(new RecommendationExecutionLease(run.getId(), run.getExecutionToken(),
                run.getExecutionAttempt(), ownerId, request));
    }

    @Transactional(timeoutString = "${agent.recovery.renewal-timeout-seconds:3}")
    public boolean renewLease(RecommendationExecutionLease lease) {
        if (lease == null) return false;
        RecommendationRunEntity run = runRepository.findByIdForUpdate(lease.runId()).orElse(null);
        if (run == null || !"RUNNING".equals(run.getStatus())) return false;
        Instant now = RecommendationDatabaseClock.now(entityManager);
        if (!lease.isCurrent(run, now)) return false;
        run.setExecutionLeaseUntil(now.plus(recoveryProperties.getLeaseDuration()));
        return true;
    }

    @Transactional(readOnly = true)
    public List<String> findClaimableRunIds(int limit) {
        if (limit < 1) throw new IllegalArgumentException("Recommendation recovery limit must be positive");
        return runRepository.findClaimableIds(RecommendationDatabaseClock.now(entityManager), PageRequest.of(0, limit));
    }

    private void validateRecoverableRequest(ToolLoopRequest request) {
        if (request == null || request.getRunId() == null || request.getRunId().isBlank()
                || request.getRequest() == null || request.getConfig() == null || request.getConfig().getMaxSteps() < 1
                || request.getConfig().getToolWhitelist() == null
                || !ScenePathEnforcer.DEFAULT_WHITELIST.containsAll(request.getConfig().getToolWhitelist())) {
            throw new IllegalArgumentException("RECOMMENDATION_RECOVERY_REQUIRES_NORMALIZED_READ_ONLY_REQUEST");
        }
    }

    /**
     * Failure fallback independent of a possibly corrupted in-memory projection. Locking the run also
     * serializes with event appenders and parallel snapshot writers. Task cancellation, terminal event
     * and outbox records commit together, or none of them do. Does not resume interrupted execution.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failRun(String runId, String reason) {
        RecommendationRunEntity run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalArgumentException("RECOMMENDATION_RUN_NOT_FOUND:" + runId));
        rejectUnleasedWrite(run);
        return failLocked(run, reason, RecommendationDatabaseClock.now(entityManager));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failRun(String runId, String reason, RecommendationExecutionLease lease) {
        RecommendationRunEntity run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalArgumentException("RECOMMENDATION_RUN_NOT_FOUND:" + runId));
        Instant now = RecommendationDatabaseClock.now(entityManager);
        requireLease(run, lease, now);
        return failLocked(run, reason, now);
    }

    /** Only the not-yet-claimed queue entry can be failed by an admission/dispatch error. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean failUnclaimedRun(String runId, String reason) {
        RecommendationRunEntity run = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new IllegalArgumentException("RECOMMENDATION_RUN_NOT_FOUND:" + runId));
        if (!run.isRecoverable() || run.getExecutionAttempt() != 0 || run.getExecutionToken() != null) return false;
        return failLocked(run, reason, RecommendationDatabaseClock.now(entityManager));
    }

    private boolean failLocked(RecommendationRunEntity run, String reason, Instant now) {
        String runId = run.getId();
        if (!DynamicSubAgentRuntime.RunStatus.RUNNING.name().equals(run.getStatus())) return false;
        String stopReason = reason == null || reason.isBlank() ? "unexpected_runtime_failure" : reason;
        cancelActiveTasks(runId, stopReason, now);
        run.setStatus(DynamicSubAgentRuntime.RunStatus.FAILED.name());
        run.setStopReason(stopReason);
        run.setCompletedAt(now);
        run.setStateVersion(run.getStateVersion() + 1);
        enqueue("run:" + runId + ":status:FAILED", "RUN", runId, "RUN_STATUS_CHANGED",
                Map.of("runId", runId, "status", "FAILED", "stopReason", stopReason));
        appendEventLocked(run, AgentRunEvent.builder().requestId(runId).type("run_failed")
                .name("run.failed").status("failed").summary("Recommendation Agent Loop failed")
                .data(Map.of("stopReason", stopReason)).build(), now);
        return true;
    }

    private void cancelActiveTasks(String runId, String stopReason, Instant now) {
        for (RecommendationTaskEntity task : taskRepository.findByRunIdOrderByCreatedAtAsc(runId)) {
            if (!DynamicSubAgentRuntime.TaskStatus.PENDING.name().equals(task.getStatus())
                    && !DynamicSubAgentRuntime.TaskStatus.RUNNING.name().equals(task.getStatus())) continue;
            task.setStatus(DynamicSubAgentRuntime.TaskStatus.CANCELLED.name());
            task.setStopReason(stopReason);
            task.setCompletedAt(now);
            task.setUpdatedAt(now);
            enqueue("task:" + task.getId() + ":status:CANCELLED", "TASK", task.getId(),
                    "TASK_STATUS_CHANGED", Map.of("runId", runId, "taskId", task.getId(),
                            "status", "CANCELLED", "stopReason", stopReason));
        }
    }

    private void appendEventLocked(RecommendationRunEntity run, AgentRunEvent source, Instant now) {
        if (source.getEventId() == null || !run.getId().equals(source.getRequestId())) {
            throw new IllegalArgumentException("RECOMMENDATION_TERMINAL_EVENT_INVALID");
        }
        RecommendationRunEventEntity existing = entityManager.find(RecommendationRunEventEntity.class, source.getEventId());
        if (existing != null) {
            confirmCommittedEvent(run, source, existing);
            return;
        }
        int previousSequence = entityManager.createQuery(
                        "select coalesce(max(event.sequence), 0) from RecommendationRunEventEntity event "
                                + "where event.runId = :runId", Integer.class)
                .setParameter("runId", run.getId()).getSingleResult();
        source.setSequence(Math.addExact(previousSequence, 1));
        entityManager.persist(RecommendationRunEventEntity.builder()
                .id(source.getEventId()).runId(run.getId()).sequence(source.getSequence())
                .type(source.getType() == null ? "event" : source.getType())
                .name(source.getName() == null ? "event" : source.getName())
                .status(source.getStatus() == null ? "running" : source.getStatus())
                .summary(source.getSummary()).dataJson(writeJson(source.getData() == null ? Map.of() : source.getData()))
                .elapsedMs(Math.max(0, Duration.between(run.getCreatedAt(), now).toMillis()))
                .createdAt(now).build());
    }

    private void confirmCommittedEvent(RecommendationRunEntity run, AgentRunEvent source,
                                       RecommendationRunEventEntity existing) {
        if (existing == null) throw new IllegalArgumentException("RECOMMENDATION_TERMINAL_EVENT_NOT_COMMITTED");
        if (!run.getId().equals(source.getRequestId()) || !run.getId().equals(existing.getRunId())) {
            throw new IllegalArgumentException("RECOMMENDATION_EVENT_RUN_MISMATCH");
        }
        if (!Objects.equals(existing.getType(), source.getType() == null ? "event" : source.getType())
                || !Objects.equals(existing.getName(), source.getName())
                || !Objects.equals(existing.getStatus(), source.getStatus())
                || !Objects.equals(readJson(existing.getDataJson(), Object.class),
                readJson(writeJson(source.getData() == null ? Map.of() : source.getData()), Object.class))) {
            throw new IllegalArgumentException("RECOMMENDATION_EVENT_ID_CONFLICT");
        }
        source.setSequence(existing.getSequence());
    }

    /** Persist a complete runtime projection in one transaction. */
    @Transactional
    public void persist(DynamicSubAgentRuntime.RunView view) {
        RecommendationRunEntity run = runRepository.findByIdForUpdate(view.runId())
                .orElseThrow(() -> new IllegalArgumentException("RECOMMENDATION_RUN_NOT_FOUND:" + view.runId()));
        rejectUnleasedWrite(run);
        persistLocked(view, run);
    }

    @Transactional
    public void persist(DynamicSubAgentRuntime.RunView view, RecommendationExecutionLease lease,
                        AgentRunEvent terminalEvent) {
        RecommendationRunEntity run = runRepository.findByIdForUpdate(view.runId())
                .orElseThrow(() -> new IllegalArgumentException("RECOMMENDATION_RUN_NOT_FOUND:" + view.runId()));
        Instant now = RecommendationDatabaseClock.now(entityManager);
        requireLease(run, lease, now);
        if (!"RUNNING".equals(run.getStatus())) {
            // A commit acknowledgement may have been lost. A successful retry must confirm the
            // exact durable event; otherwise callers could publish a new, uncommitted terminal.
            if (terminalEvent != null) {
                validateTerminalEvent(DynamicSubAgentRuntime.RunStatus.valueOf(run.getStatus()), terminalEvent);
                RecommendationRunEventEntity existing = terminalEvent.getEventId() == null ? null
                        : entityManager.find(RecommendationRunEventEntity.class, terminalEvent.getEventId());
                confirmCommittedEvent(run, terminalEvent, existing);
            }
            return;
        }
        if (view.status() == DynamicSubAgentRuntime.RunStatus.RUNNING && terminalEvent != null) {
            throw new IllegalArgumentException("RECOMMENDATION_TERMINAL_EVENT_REQUIRES_TERMINAL_STATE");
        }
        String prefix = view.runId() + ":attempt:" + lease.attempt() + ":subagent:";
        if (view.tasks().stream().anyMatch(task -> !task.taskId().startsWith(prefix))) {
            throw new IllegalArgumentException("RECOMMENDATION_TASK_ATTEMPT_MISMATCH");
        }
        persistLocked(view, run);
        if (view.status() != DynamicSubAgentRuntime.RunStatus.RUNNING) {
            String status = view.status().name().toLowerCase(java.util.Locale.ROOT);
            AgentRunEvent event = terminalEvent == null ? AgentRunEvent.builder().requestId(view.runId())
                    .type("run_" + status).name("run." + status).status(status)
                    .summary("Recommendation run " + status)
                    .data(Map.of("stopReason", view.stopReason() == null ? "" : view.stopReason())).build() : terminalEvent;
            validateTerminalEvent(view.status(), event);
            appendEventLocked(run, event, now);
        }
    }

    private static void validateTerminalEvent(DynamicSubAgentRuntime.RunStatus status, AgentRunEvent event) {
        boolean valid = switch (status) {
            case COMPLETED -> "run.completed".equals(event.getName())
                    && ("completed".equals(event.getStatus()) || "success".equals(event.getStatus()));
            case FAILED -> "run.failed".equals(event.getName()) && "failed".equals(event.getStatus());
            case BLOCKED -> ("run.blocked".equals(event.getName()) || "run.failed".equals(event.getName()))
                    && "blocked".equals(event.getStatus());
            default -> false;
        };
        if (!valid) throw new IllegalArgumentException("RECOMMENDATION_TERMINAL_EVENT_STATUS_MISMATCH");
    }

    private static void requireLease(RecommendationRunEntity run, RecommendationExecutionLease lease, Instant now) {
        if (lease == null) throw new StaleExecutionLeaseException(run.getId());
        lease.assertCurrent(run, now);
    }

    private static void rejectUnleasedWrite(RecommendationRunEntity run) {
        if (run.isRecoverable()) throw new StaleExecutionLeaseException(run.getId());
    }

    private void persistLocked(DynamicSubAgentRuntime.RunView view, RecommendationRunEntity run) {
        // Final metadata and child set are frozen. Late parallel snapshots may still arrive after completion.
        if (!DynamicSubAgentRuntime.RunStatus.RUNNING.name().equals(run.getStatus())) return;
        if (canAdvanceRun(run.getStatus(), view.status())) {
            run.setStatus(view.status().name());
            run.setStopReason(view.stopReason());
            run.setCompletedAt(toInstant(view.completedAtEpochMs()));
        }
        run.setStateVersion(run.getStateVersion() + 1);
        runRepository.save(run);

        for (DynamicSubAgentRuntime.SubAgentTaskView task : view.tasks()) {
            RecommendationTaskEntity entity = taskRepository.findById(task.taskId()).orElse(null);
            if (entity != null && !view.runId().equals(entity.getRunId())) {
                throw new IllegalStateException("RECOMMENDATION_TASK_RUN_MISMATCH:" + task.taskId());
            }
            String previousStatus = entity == null ? null : entity.getStatus();
            boolean statusAccepted = entity == null || canAdvanceTask(previousStatus, task.status());
            if (entity == null) {
                entity = RecommendationTaskEntity.builder()
                        .id(task.taskId())
                        .runId(view.runId())
                        .parentAgentId(task.parentAgentId())
                        .parentRole(task.parentRole())
                        .role(task.role())
                        .agent(task.agent().name())
                        .goal(task.goal())
                        .toolScopesJson(writeJson(task.toolScopes()))
                        .plannedAction(task.plannedAction())
                        .dependenciesJson(writeJson(task.dependencies()))
                        .contextJson(writeJson(task.context()))
                        .candidateVersion(task.context().candidateVersion())
                        .createdAt(toInstant(task.createdAtEpochMs()))
                        .build();
            }
            if (statusAccepted) {
                entity.setStatus(task.status().name());
                entity.setArtifactIdsJson(writeJson(task.artifactIds()));
                entity.setStopReason(task.stopReason());
                entity.setStartedAt(toInstant(task.startedAtEpochMs()));
                entity.setCompletedAt(toInstant(task.completedAtEpochMs()));
                entity.setUpdatedAt(Instant.now());
                taskRepository.save(entity);
            }

            if (previousStatus == null) {
                enqueue("task:" + task.taskId() + ":created", "TASK", task.taskId(),
                        "TASK_CREATED", task);
            }
            if (statusAccepted && !task.status().name().equals(previousStatus)) {
                enqueue("task:" + task.taskId() + ":status:" + task.status(), "TASK", task.taskId(),
                        "TASK_STATUS_CHANGED", Map.of(
                                "runId", view.runId(),
                                "taskId", task.taskId(),
                                "status", task.status().name(),
                                "stopReason", task.stopReason() == null ? "" : task.stopReason()));
            }
        }

        for (DynamicSubAgentRuntime.SubAgentArtifact artifact : view.artifacts()) {
            RecommendationArtifactEntity existing = artifactRepository.findById(artifact.artifactId()).orElse(null);
            if (existing != null) {
                if (!view.runId().equals(existing.getRunId()) || !sameArtifact(existing, artifact)) {
                    throw new IllegalStateException("RECOMMENDATION_ARTIFACT_IMMUTABLE:" + artifact.artifactId());
                }
                continue;
            }
            RecommendationTaskEntity producer = taskRepository.findById(artifact.taskId()).orElseThrow(
                    () -> new IllegalStateException("RECOMMENDATION_ARTIFACT_TASK_MISSING:" + artifact.taskId()));
            List<String> acceptedArtifactIds = readJson(producer.getArtifactIdsJson(), new TypeReference<List<String>>() {});
            if (!view.runId().equals(producer.getRunId()) || !acceptedArtifactIds.contains(artifact.artifactId())
                    || !Objects.equals(producer.getRole(), artifact.producerRole())
                    || producer.getCandidateVersion() != artifact.baseCandidateVersion()) {
                throw new IllegalStateException("RECOMMENDATION_ARTIFACT_PROVENANCE_MISMATCH:" + artifact.artifactId());
            }
            RecommendationArtifactEntity entity = artifactRepository.save(
                    RecommendationArtifactEntity.builder()
                            .id(artifact.artifactId())
                            .runId(view.runId())
                            .taskId(artifact.taskId())
                            .producerRole(artifact.producerRole())
                            .artifactType(artifact.type())
                            .baseCandidateVersion(artifact.baseCandidateVersion())
                            .dataJson(writeJson(artifact.data()))
                            .evidenceIdsJson(writeJson(artifact.evidenceIds()))
                            .createdAt(toInstant(artifact.createdAtEpochMs()))
                            .build());
            enqueue("artifact:" + artifact.artifactId() + ":created", "ARTIFACT", artifact.artifactId(),
                    "ARTIFACT_CREATED", entity);
        }

        if (view.status() != DynamicSubAgentRuntime.RunStatus.RUNNING) {
            enqueue("run:" + view.runId() + ":status:" + view.status(), "RUN", view.runId(),
                    "RUN_STATUS_CHANGED", Map.of(
                            "runId", view.runId(),
                            "status", view.status().name(),
                            "stopReason", view.stopReason() == null ? "" : view.stopReason()));
        }
    }

    @Transactional(readOnly = true)
    public Optional<DynamicSubAgentRuntime.RunView> findRunView(String runId) {
        return runRepository.findById(runId).map(run -> {
            List<DynamicSubAgentRuntime.SubAgentTaskView> tasks = taskRepository
                    .findByRunIdOrderByCreatedAtAsc(runId).stream()
                    .map(this::taskView)
                    .toList();
            List<DynamicSubAgentRuntime.SubAgentArtifact> artifacts = artifactRepository
                    .findByRunIdOrderByCreatedAtAsc(runId).stream()
                    .map(this::artifactView)
                    .toList();
            return new DynamicSubAgentRuntime.RunView(
                    run.getId(),
                    DynamicSubAgentRuntime.RunStatus.valueOf(run.getStatus()),
                    run.getStopReason(),
                    toEpochMillis(run.getCreatedAt()),
                    toEpochMillis(run.getCompletedAt()),
                    tasks,
                    artifacts);
        });
    }

    private DynamicSubAgentRuntime.SubAgentTaskView taskView(RecommendationTaskEntity task) {
        return new DynamicSubAgentRuntime.SubAgentTaskView(
                task.getId(),
                task.getParentAgentId(),
                task.getParentRole(),
                task.getRole(),
                AgentId.valueOf(task.getAgent()),
                task.getGoal(),
                readJson(task.getToolScopesJson(), new TypeReference<List<String>>() {}),
                task.getPlannedAction(),
                readJson(task.getDependenciesJson(), new TypeReference<List<String>>() {}),
                DynamicSubAgentRuntime.TaskStatus.valueOf(task.getStatus()),
                readJson(task.getContextJson(), DynamicSubAgentRuntime.ContextSnapshot.class),
                readJson(task.getArtifactIdsJson(), new TypeReference<List<String>>() {}),
                task.getStopReason(),
                toEpochMillis(task.getCreatedAt()),
                toEpochMillis(task.getStartedAt()),
                toEpochMillis(task.getCompletedAt()));
    }

    private DynamicSubAgentRuntime.SubAgentArtifact artifactView(RecommendationArtifactEntity artifact) {
        return new DynamicSubAgentRuntime.SubAgentArtifact(
                artifact.getId(),
                artifact.getTaskId(),
                artifact.getProducerRole(),
                artifact.getArtifactType(),
                artifact.getBaseCandidateVersion(),
                readJson(artifact.getDataJson(), new TypeReference<Map<String, Object>>() {}),
                readJson(artifact.getEvidenceIdsJson(), new TypeReference<List<String>>() {}),
                toEpochMillis(artifact.getCreatedAt()));
    }

    private boolean sameArtifact(RecommendationArtifactEntity persisted,
                                 DynamicSubAgentRuntime.SubAgentArtifact incoming) {
        return Objects.equals(persisted.getTaskId(), incoming.taskId())
                && Objects.equals(persisted.getProducerRole(), incoming.producerRole())
                && Objects.equals(persisted.getArtifactType(), incoming.type())
                && persisted.getBaseCandidateVersion() == incoming.baseCandidateVersion()
                && toEpochMillis(persisted.getCreatedAt()) == incoming.createdAtEpochMs()
                && readJson(persisted.getDataJson(), Object.class).equals(readJson(writeJson(incoming.data()), Object.class))
                && readJson(persisted.getEvidenceIdsJson(), Object.class).equals(incoming.evidenceIds());
    }

    private void enqueue(String dedupKey, String aggregateType, String aggregateId,
                         String eventType, Object payload) {
        if (outboxRepository.existsByDedupKey(dedupKey)) return;
        outboxRepository.save(RecommendationOutboxEntity.builder()
                .id(UUID.randomUUID().toString())
                .dedupKey(dedupKey)
                .aggregateType(aggregateType)
                .aggregateId(aggregateId)
                .eventType(eventType)
                .payloadJson(writeJson(payload))
                .status("PENDING")
                .attemptCount(0)
                .createdAt(Instant.now())
                .build());
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("RECOMMENDATION_RUNTIME_SERIALIZATION_FAILED", error);
        }
    }

    private <T> T readJson(String value, Class<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception error) {
            throw new IllegalStateException("RECOMMENDATION_RUNTIME_DESERIALIZATION_FAILED", error);
        }
    }

    private <T> T readJson(String value, TypeReference<T> type) {
        try {
            return objectMapper.readValue(value, type);
        } catch (Exception error) {
            throw new IllegalStateException("RECOMMENDATION_RUNTIME_DESERIALIZATION_FAILED", error);
        }
    }

    private static Instant toInstant(long epochMillis) {
        return epochMillis <= 0 ? null : Instant.ofEpochMilli(epochMillis);
    }

    private static long toEpochMillis(Instant value) {
        return value == null ? 0 : value.toEpochMilli();
    }

    private static boolean canAdvanceTask(
            String persistedStatus,
            DynamicSubAgentRuntime.TaskStatus incomingStatus) {
        if (persistedStatus == null) return true;
        DynamicSubAgentRuntime.TaskStatus persisted =
                DynamicSubAgentRuntime.TaskStatus.valueOf(persistedStatus);
        if (isTerminal(persisted)) return false;
        if (persisted == incomingStatus) return true;
        return taskStatusRank(incomingStatus) > taskStatusRank(persisted);
    }

    private static boolean canAdvanceRun(
            String persistedStatus,
            DynamicSubAgentRuntime.RunStatus incomingStatus) {
        if (persistedStatus == null) return true;
        DynamicSubAgentRuntime.RunStatus persisted =
                DynamicSubAgentRuntime.RunStatus.valueOf(persistedStatus);
        return persisted == DynamicSubAgentRuntime.RunStatus.RUNNING
                || persisted == incomingStatus;
    }

    private static boolean isTerminal(DynamicSubAgentRuntime.TaskStatus status) {
        return status == DynamicSubAgentRuntime.TaskStatus.COMPLETED
                || status == DynamicSubAgentRuntime.TaskStatus.FAILED
                || status == DynamicSubAgentRuntime.TaskStatus.CANCELLED;
    }

    private static int taskStatusRank(DynamicSubAgentRuntime.TaskStatus status) {
        return switch (status) {
            case PENDING -> 0;
            case RUNNING -> 1;
            case COMPLETED, FAILED, CANCELLED -> 2;
        };
    }
}
