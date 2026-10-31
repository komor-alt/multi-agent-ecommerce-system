package com.ecommerce.eval.benchmark;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/** Test-only run-scoped fault state kept outside model-visible request data. */
public final class AgentBenchmarkFaultRegistry {
    private static final Map<String, State> ACTIVE = new ConcurrentHashMap<>();

    private AgentBenchmarkFaultRegistry() {}

    public static Scope bind(String runId, AgentBenchmarkDataset.FaultPlan plan) {
        if (runId == null || runId.isBlank()) throw new IllegalArgumentException("BENCHMARK_RUN_ID_REQUIRED");
        State state = new State(Objects.requireNonNull(plan, "plan"));
        if (ACTIVE.putIfAbsent(runId, state) != null) {
            throw new IllegalStateException("BENCHMARK_FAULT_SCOPE_ALREADY_BOUND: " + runId);
        }
        return new Scope(runId, state);
    }

    static State state(String runId) {
        return ACTIVE.get(runId);
    }

    static int activeBindings() {
        return ACTIVE.size();
    }

    static final class State {
        private final AgentBenchmarkDataset.FaultPlan plan;
        private final Map<String, AtomicInteger> invocations = new ConcurrentHashMap<>();

        private State(AgentBenchmarkDataset.FaultPlan plan) {
            this.plan = plan;
        }

        AgentBenchmarkDataset.FaultPlan plan() {
            return plan;
        }

        int nextOccurrence(String tool) {
            return invocations.computeIfAbsent(tool, ignored -> new AtomicInteger()).incrementAndGet();
        }

        int currentOccurrence(String tool) {
            AtomicInteger value = invocations.get(tool);
            return value == null ? 0 : value.get();
        }
    }

    public static final class Scope implements AutoCloseable {
        private final String runId;
        private final State state;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Scope(String runId, State state) {
            this.runId = runId;
            this.state = state;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) ACTIVE.remove(runId, state);
        }
    }
}
