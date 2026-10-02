package com.ecommerce.runtime;

import com.ecommerce.config.RecommendationOrchestrationProperties;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Bounded in-memory index that makes active and recent sub-agent runs observable. */
@Component
public class DynamicSubAgentRuntimeRegistry {
    private final int maxRetainedRuns;
    private final RecommendationRuntimeStore runtimeStore;
    private final Map<String, DynamicSubAgentRuntime> runtimes = new LinkedHashMap<>();

    public DynamicSubAgentRuntimeRegistry(RecommendationOrchestrationProperties properties) {
        this(properties, null);
    }

    @Autowired
    public DynamicSubAgentRuntimeRegistry(
            RecommendationOrchestrationProperties properties,
            RecommendationRuntimeStore runtimeStore) {
        this.maxRetainedRuns = properties.getMaxRetainedRuns();
        this.runtimeStore = runtimeStore;
    }

    public synchronized DynamicSubAgentRuntime create(String runId) {
        return create(runId, 0);
    }

    public synchronized DynamicSubAgentRuntime create(String runId, int attempt) {
        DynamicSubAgentRuntime existing = runtimes.get(runId);
        if (existing != null && existing.runView().status() == DynamicSubAgentRuntime.RunStatus.RUNNING
                && attempt <= existing.executionAttempt()) {
            throw new IllegalStateException("Sub-agent run is already active: " + runId);
        }
        DynamicSubAgentRuntime runtime = new DynamicSubAgentRuntime(runId, attempt);
        runtimes.put(runId, runtime);
        trimCompletedRuns();
        return runtime;
    }

    public Optional<DynamicSubAgentRuntime.RunView> find(String runId) {
        // The database includes all attempts and remains authoritative after takeover.
        if (runtimeStore != null) {
            Optional<DynamicSubAgentRuntime.RunView> persisted = runtimeStore.findRunView(runId);
            if (persisted.isPresent()) return persisted;
        }
        DynamicSubAgentRuntime runtime;
        synchronized (this) {
            runtime = runtimes.get(runId);
        }
        if (runtime != null) return Optional.of(runtime.runView());
        return Optional.empty();
    }

    public synchronized void finish(
            DynamicSubAgentRuntime runtime,
            String status,
            String stopReason) {
        if (runtime == null) return;
        runtime.finishRun(status, stopReason);
        trimCompletedRuns();
    }

    private void trimCompletedRuns() {
        if (runtimes.size() <= maxRetainedRuns) return;
        Iterator<Map.Entry<String, DynamicSubAgentRuntime>> iterator = runtimes.entrySet().iterator();
        while (runtimes.size() > maxRetainedRuns && iterator.hasNext()) {
            Map.Entry<String, DynamicSubAgentRuntime> entry = iterator.next();
            if (entry.getValue().runView().status() != DynamicSubAgentRuntime.RunStatus.RUNNING) {
                iterator.remove();
            }
        }
    }
}
