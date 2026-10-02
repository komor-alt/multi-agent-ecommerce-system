package com.ecommerce.service;

import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.runtime.persistence.RecommendationPersistenceMonitor;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Low-cardinality business metrics; run/user identifiers stay out of labels. */
@Component
public class RuntimeTelemetry {
    private static final Logger log = LoggerFactory.getLogger(RuntimeTelemetry.class);
    private final MeterRegistry meters;
    private final RecommendationPersistenceMonitor persistence;
    private final Map<String, AtomicLong> databaseGauges = Map.of(
            "runningRuns", new AtomicLong(), "runningTasks", new AtomicLong(),
            "pendingTasks", new AtomicLong(), "outboxPending", new AtomicLong(),
            "outboxPublished", new AtomicLong(), "outboxDeadLetter", new AtomicLong(),
            "outboxInFlight", new AtomicLong(), "outboxExpiredLeases", new AtomicLong(),
            "outboxOldestUnfinishedAgeSeconds", new AtomicLong());

    public RuntimeTelemetry(MeterRegistry meters, RecommendationPersistenceMonitor persistence,
                            AgentConcurrencyGuard guard, RecommendationRunEventService events) {
        this.meters = meters;
        this.persistence = persistence;
        databaseGauges.forEach((key, value) -> meters.gauge("agent.persistence",
                java.util.List.of(io.micrometer.core.instrument.Tag.of("state", key)), value));
        meters.gauge("agent.runs.active", guard, value -> value.activeRunCount());
        meters.gauge("agent.runs.capacity", guard, value -> value.capacity());
        for (String key : Set.of("subscribers", "activeSenders", "queuedSends", "failedStreams", "rejectedSubscriptions")) {
            meters.gauge("agent.events", java.util.List.of(io.micrometer.core.instrument.Tag.of("state", key)),
                    events, value -> ((Number) value.snapshot().getOrDefault(key, 0)).doubleValue());
        }
    }

    public void recordRun(String scene, String outcome, long nanos, int steps) {
        String safeScene = Set.of("homepage", "campaign", "retention").contains(scene == null ? "" : scene)
                ? scene : "other";
        String safeOutcome = Set.of("completed", "blocked", "failed", "timeout").contains(outcome == null ? "" : outcome)
                ? outcome : "failed";
        meters.counter("agent.runs", "scene", safeScene, "outcome", safeOutcome).increment();
        Timer.builder("agent.run.duration").tags("scene", safeScene, "outcome", safeOutcome)
                .publishPercentileHistogram().serviceLevelObjectives(
                        Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(60))
                .register(meters).record(nanos, TimeUnit.NANOSECONDS);
        meters.summary("agent.run.steps", "scene", safeScene).record(steps);
    }

    public void recordTool(ToolCallRecord call) {
        String tool = call.getToolName() != null && ScenePathEnforcer.DEFAULT_WHITELIST.contains(call.getToolName())
                ? call.getToolName() : "other";
        String outcome = Set.of("success", "failed", "blocked", "stale_rejected").contains(call.getStatus() == null ? "" : call.getStatus())
                ? call.getStatus() : "other";
        Timer.builder("agent.tool.duration").tags("tool", tool, "outcome", outcome)
                .publishPercentileHistogram().register(meters)
                .record(Duration.ofNanos(Math.max(0L, (long) (call.getLatencyMs() * 1_000_000))));
    }

    @Scheduled(fixedDelayString = "${agent.metrics.refresh-ms:10000}")
    public void refreshPersistence() {
        try {
            Map<String, Object> snapshot = persistence.snapshot();
            databaseGauges.forEach((key, value) -> value.set(((Number) snapshot.getOrDefault(key, 0)).longValue()));
        } catch (RuntimeException error) {
            meters.counter("agent.persistence.refresh.errors").increment();
            log.warn("Unable to refresh persistence metrics: {}", error.getClass().getSimpleName());
        }
    }
}
