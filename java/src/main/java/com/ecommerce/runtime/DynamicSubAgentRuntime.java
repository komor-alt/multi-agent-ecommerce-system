package com.ecommerce.runtime;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.Product;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.service.RecommendationPipelineState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-request runtime for dynamically spawned specialist tasks.
 *
 * <p>The runtime records parent/child identity, dependency edges, an immutable
 * context snapshot, lifecycle transitions and result artifacts. It deliberately
 * contains no Spring or domain-tool dispatch code, so the same task model can be
 * reused by future specialist types. All lifecycle writes and snapshots share this
 * instance's monitor: task status, artifact references and the artifact collection
 * are published together. No tool, LLM or persistence I/O runs under this monitor.</p>
 */
public final class DynamicSubAgentRuntime {
    private static final String ROOT_ROLE = "ROOT";

    private final String runId;
    private final int executionAttempt;
    private final AtomicLong sequence = new AtomicLong();
    private final Map<String, MutableTask> tasks = new ConcurrentHashMap<>();
    private final Map<String, SubAgentArtifact> artifacts = new ConcurrentHashMap<>();
    private final long createdAtEpochMs = System.currentTimeMillis();
    private volatile RunStatus runStatus = RunStatus.RUNNING;
    private volatile String runStopReason = "";
    private volatile long completedAtEpochMs;
    private List<String> frontier = List.of();

    public DynamicSubAgentRuntime(String runId) {
        this(runId, 0);
    }

    public DynamicSubAgentRuntime(String runId, int executionAttempt) {
        this.runId = requireText(runId, "runId");
        if (executionAttempt < 0) throw new IllegalArgumentException("Invalid execution attempt");
        this.executionAttempt = executionAttempt;
    }

    public int executionAttempt() {
        return executionAttempt;
    }

    public synchronized TaskHandle spawn(SubAgentSpec spec, RecommendationPipelineState state) {
        List<TaskHandle> handles = spawnBatch(List.of(spec), state);
        return handles.get(0);
    }

    /** Spawn siblings from one dependency frontier so they can run concurrently. */
    public synchronized List<TaskHandle> spawnBatch(
            List<SubAgentSpec> specs,
            RecommendationPipelineState state) {
        if (specs == null || specs.isEmpty()) {
            return List.of();
        }
        if (runStatus != RunStatus.RUNNING) throw new IllegalStateException("Run is already terminal");
        List<String> dependencies = List.copyOf(frontier);
        ContextSnapshot snapshot = ContextSnapshot.capture(state);
        List<TaskHandle> handles = new ArrayList<>();
        for (SubAgentSpec spec : specs) {
            Objects.requireNonNull(spec, "spec");
            String taskId = runId + (executionAttempt > 0 ? ":attempt:" + executionAttempt : "")
                    + ":subagent:" + sequence.incrementAndGet();
            MutableTask task = new MutableTask(
                    taskId,
                    "root:" + runId,
                    ROOT_ROLE,
                    spec,
                    dependencies,
                    snapshot,
                    System.currentTimeMillis());
            tasks.put(taskId, task);
            handles.add(new TaskHandle(taskId, snapshot));
        }
        frontier = handles.stream().map(TaskHandle::taskId).toList();
        return List.copyOf(handles);
    }

    public synchronized void markRunning(TaskHandle handle) {
        MutableTask task = requireTask(handle);
        synchronized (task) {
            if (runStatus != RunStatus.RUNNING) throw new IllegalStateException("Run is already terminal");
            if (task.status != TaskStatus.PENDING) {
                throw new IllegalStateException("Task " + task.taskId + " cannot start from " + task.status);
            }
            task.status = TaskStatus.RUNNING;
            task.startedAtEpochMs = System.currentTimeMillis();
        }
    }

    public synchronized void finish(
            TaskHandle handle,
            boolean success,
            String stopReason,
            List<ToolObservation> observations) {
        MutableTask task = requireTask(handle);
        synchronized (task) {
            if (runStatus != RunStatus.RUNNING) return;
            if (task.status != TaskStatus.RUNNING) {
                throw new IllegalStateException("Task " + task.taskId + " cannot finish from " + task.status);
            }
            List<String> artifactIds = createArtifacts(task, observations);
            task.artifactIds = artifactIds;
            task.stopReason = stopReason == null ? "" : stopReason;
            task.status = success ? TaskStatus.COMPLETED : TaskStatus.FAILED;
            task.completedAtEpochMs = System.currentTimeMillis();
        }
    }

    public synchronized List<String> artifactIds(String taskId) {
        MutableTask task = tasks.get(taskId);
        return task == null ? List.of() : task.view().artifactIds();
    }

    public synchronized List<String> dependencies(String taskId) {
        MutableTask task = tasks.get(taskId);
        return task == null ? List.of() : task.view().dependencies();
    }

    public synchronized List<SubAgentTaskView> taskViews() {
        return tasks.values().stream()
                .map(MutableTask::view)
                .sorted(java.util.Comparator.comparing(SubAgentTaskView::taskId))
                .toList();
    }

    public synchronized List<SubAgentArtifact> artifactViews() {
        return artifacts.values().stream()
                .sorted(java.util.Comparator.comparing(SubAgentArtifact::artifactId))
                .toList();
    }

    public synchronized void finishRun(String status, String stopReason) {
        if (runStatus != RunStatus.RUNNING) return;
        runStatus = switch (status == null ? "" : status) {
            case "completed" -> RunStatus.COMPLETED;
            case "blocked" -> RunStatus.BLOCKED;
            case "failed" -> RunStatus.FAILED;
            default -> RunStatus.FAILED;
        };
        runStopReason = stopReason == null ? "" : stopReason;
        completedAtEpochMs = System.currentTimeMillis();
        for (MutableTask task : tasks.values()) {
            synchronized (task) {
                if (task.status == TaskStatus.RUNNING || task.status == TaskStatus.PENDING) {
                    task.status = TaskStatus.CANCELLED;
                    task.stopReason = runStopReason;
                    task.completedAtEpochMs = completedAtEpochMs;
                }
            }
        }
    }

    public synchronized RunView runView() {
        return new RunView(
                runId,
                runStatus,
                runStopReason,
                createdAtEpochMs,
                completedAtEpochMs,
                taskViews(),
                artifactViews());
    }

    private List<String> createArtifacts(MutableTask task, List<ToolObservation> observations) {
        if (observations == null || observations.isEmpty()) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        for (int index = 0; index < observations.size(); index++) {
            ToolObservation observation = observations.get(index);
            if (observation == null) continue;
            String artifactId = task.taskId + ":artifact:" + (index + 1);
            SubAgentArtifact artifact = new SubAgentArtifact(
                    artifactId,
                    task.taskId,
                    task.spec.role(),
                    observation.getToolName(),
                    task.context.candidateVersion(),
                    immutableMap(observation.getData()),
                    observation.getEvidenceIds() == null ? List.of() : List.copyOf(observation.getEvidenceIds()),
                    System.currentTimeMillis());
            artifacts.put(artifactId, artifact);
            ids.add(artifactId);
        }
        return List.copyOf(ids);
    }

    private MutableTask requireTask(TaskHandle handle) {
        Objects.requireNonNull(handle, "handle");
        MutableTask task = tasks.get(handle.taskId());
        if (task == null) throw new IllegalArgumentException("Unknown sub-agent task " + handle.taskId());
        return task;
    }

    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return Map.of();
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value;
    }

    public enum TaskStatus {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED
    }

    public enum RunStatus {
        RUNNING,
        COMPLETED,
        BLOCKED,
        FAILED
    }

    public record SubAgentSpec(
            String role,
            AgentId agent,
            String goal,
            List<String> toolScopes,
            int maxSteps,
            String plannedAction) {
        public SubAgentSpec {
            role = requireText(role, "role");
            Objects.requireNonNull(agent, "agent");
            goal = requireText(goal, "goal");
            toolScopes = toolScopes == null ? List.of() : List.copyOf(toolScopes);
            if (maxSteps <= 0) throw new IllegalArgumentException("maxSteps must be positive");
        }
    }

    public record TaskHandle(String taskId, ContextSnapshot context) {}

    public record ContextSnapshot(
            long candidateVersion,
            String scene,
            Map<String, Object> state,
            List<String> evidenceIds) {
        public ContextSnapshot {
            state = immutableMap(state);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }

        public static ContextSnapshot capture(RecommendationPipelineState source) {
            Objects.requireNonNull(source, "source");
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("userId", source.getRequest().getUserId());
            values.put("rawProductIds", productIds(source.getRawProducts()));
            values.put("rankedProductIds", productIds(source.getRankedProducts()));
            values.put("availableProductIds", source.getAvailableIds() == null
                    ? List.of() : source.getAvailableIds().stream().sorted().toList());
            values.put("finalProductIds", productIds(source.getFinalProducts()));
            values.put("hasProfile", source.getProfile() != null);
            values.put("hasCampaignConstraints", source.getCampaignConstraints() != null);
            values.put("hasOrderContext", source.getOrderContext() != null);
            values.put("unhandledVeto", source.hasUnhandledVeto());
            values.put("vetoedProductIds", source.vetoedProductIds().stream().sorted().toList());
            return new ContextSnapshot(
                    source.getCandidateVersion(),
                    source.getRequest().getScene(),
                    values,
                    source.evidenceIds());
        }

        private static List<String> productIds(List<Product> products) {
            return products == null ? List.of() : products.stream().map(Product::getProductId).toList();
        }
    }

    public record SubAgentArtifact(
            String artifactId,
            String taskId,
            String producerRole,
            String type,
            long baseCandidateVersion,
            Map<String, Object> data,
            List<String> evidenceIds,
            long createdAtEpochMs) {
        public SubAgentArtifact {
            data = immutableMap(data);
            evidenceIds = evidenceIds == null ? List.of() : List.copyOf(evidenceIds);
        }
    }

    public record SubAgentTaskView(
            String taskId,
            String parentAgentId,
            String parentRole,
            String role,
            AgentId agent,
            String goal,
            List<String> toolScopes,
            String plannedAction,
            List<String> dependencies,
            TaskStatus status,
            ContextSnapshot context,
            List<String> artifactIds,
            String stopReason,
            long createdAtEpochMs,
            long startedAtEpochMs,
            long completedAtEpochMs) {}

    public record RunView(
            String runId,
            RunStatus status,
            String stopReason,
            long createdAtEpochMs,
            long completedAtEpochMs,
            List<SubAgentTaskView> tasks,
            List<SubAgentArtifact> artifacts) {}

    private static final class MutableTask {
        private final String taskId;
        private final String parentAgentId;
        private final String parentRole;
        private final SubAgentSpec spec;
        private final List<String> dependencies;
        private final ContextSnapshot context;
        private final long createdAtEpochMs;
        private volatile TaskStatus status = TaskStatus.PENDING;
        private volatile List<String> artifactIds = List.of();
        private volatile String stopReason = "";
        private volatile long startedAtEpochMs;
        private volatile long completedAtEpochMs;

        private MutableTask(
                String taskId,
                String parentAgentId,
                String parentRole,
                SubAgentSpec spec,
                List<String> dependencies,
                ContextSnapshot context,
                long createdAtEpochMs) {
            this.taskId = taskId;
            this.parentAgentId = parentAgentId;
            this.parentRole = parentRole;
            this.spec = spec;
            this.dependencies = List.copyOf(dependencies);
            this.context = context;
            this.createdAtEpochMs = createdAtEpochMs;
        }

        private SubAgentTaskView view() {
            return new SubAgentTaskView(
                    taskId,
                    parentAgentId,
                    parentRole,
                    spec.role(),
                    spec.agent(),
                    spec.goal(),
                    spec.toolScopes(),
                    spec.plannedAction(),
                    dependencies,
                    status,
                    context,
                    List.copyOf(artifactIds),
                    stopReason,
                    createdAtEpochMs,
                    startedAtEpochMs,
                    completedAtEpochMs);
        }
    }
}
