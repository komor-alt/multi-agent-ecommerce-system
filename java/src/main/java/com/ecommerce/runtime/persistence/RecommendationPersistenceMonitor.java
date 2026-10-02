package com.ecommerce.runtime.persistence;

import com.ecommerce.runtime.DynamicSubAgentRuntime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

/** Small operational snapshot for backlog and stuck-run alerting. */
@Service
public class RecommendationPersistenceMonitor {
    private final RecommendationRunRepository runRepository;
    private final RecommendationTaskRepository taskRepository;
    private final RecommendationOutboxRepository outboxRepository;
    private final Clock clock;

    @Autowired
    public RecommendationPersistenceMonitor(
            RecommendationRunRepository runRepository,
            RecommendationTaskRepository taskRepository,
            RecommendationOutboxRepository outboxRepository) {
        this(runRepository, taskRepository, outboxRepository, Clock.systemUTC());
    }

    RecommendationPersistenceMonitor(
            RecommendationRunRepository runRepository,
            RecommendationTaskRepository taskRepository,
            RecommendationOutboxRepository outboxRepository,
            Clock clock) {
        this.runRepository = runRepository;
        this.taskRepository = taskRepository;
        this.outboxRepository = outboxRepository;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> snapshot() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("runningRuns", runRepository.countByStatus(
                DynamicSubAgentRuntime.RunStatus.RUNNING.name()));
        values.put("runningTasks", taskRepository.countByStatus(
                DynamicSubAgentRuntime.TaskStatus.RUNNING.name()));
        values.put("pendingTasks", taskRepository.countByStatus(
                DynamicSubAgentRuntime.TaskStatus.PENDING.name()));
        Instant now = clock.instant();
        RecommendationOutboxRepository.BacklogSummary outbox = outboxRepository.summarizeBacklog(now);
        values.put("outboxPending", outbox.getPending());
        values.put("outboxPublished", outbox.getPublished());
        values.put("outboxDeadLetter", outbox.getDeadLetter());
        values.put("outboxInFlight", outbox.getInFlight());
        values.put("outboxExpiredLeases", outbox.getExpiredLeases());
        values.put("outboxOldestUnfinishedAgeSeconds", outbox.getOldestUnfinishedCreatedAt() == null ? 0L
                : Math.max(0L, Duration.between(outbox.getOldestUnfinishedCreatedAt(), now).getSeconds()));
        return values;
    }
}
