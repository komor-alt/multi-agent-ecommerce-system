package com.ecommerce.runtime.persistence;

import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Database cursor tailing: replay and live delivery use the same ordered, committed log. */
@Service
@EnableConfigurationProperties(RecommendationEventProperties.class)
public class RecommendationRunEventService {
    static final String STREAM_READY_EVENT = "stream_ready";

    private final RecommendationRunEventRepository eventRepository;
    private final RecommendationRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final RecommendationEventProperties properties;
    private final Clock clock;
    private final ExecutorService sender;
    private final Map<String, Subscription> subscribers = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder failed = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    @PersistenceContext
    private EntityManager entityManager;

    public RecommendationRunEventService(RecommendationRunEventRepository eventRepository,
                                         RecommendationRunRepository runRepository,
                                         ObjectMapper objectMapper) {
        this(eventRepository, runRepository, objectMapper, new RecommendationEventProperties());
    }

    @Autowired
    public RecommendationRunEventService(RecommendationRunEventRepository eventRepository,
                                         RecommendationRunRepository runRepository,
                                         ObjectMapper objectMapper,
                                         RecommendationEventProperties properties) {
        this(eventRepository, runRepository, objectMapper, properties, Clock.systemUTC(), createSender(properties));
    }

    RecommendationRunEventService(RecommendationRunEventRepository eventRepository,
                                  RecommendationRunRepository runRepository,
                                  ObjectMapper objectMapper,
                                  RecommendationEventProperties properties,
                                  Clock clock,
                                  ExecutorService sender) {
        properties.validate();
        this.eventRepository = eventRepository;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
        this.sender = sender;
    }

    @Transactional
    public RecommendationRunEventEntity append(AgentRunEvent source) {
        return appendInternal(source, null, false);
    }

    @Transactional
    public RecommendationRunEventEntity append(AgentRunEvent source, RecommendationExecutionLease lease) {
        return appendInternal(source, lease, true);
    }

    private RecommendationRunEventEntity appendInternal(AgentRunEvent source, RecommendationExecutionLease lease,
                                                        boolean leased) {
        if (source == null || source.getRequestId() == null || source.getEventId() == null) {
            throw new IllegalArgumentException("RECOMMENDATION_EVENT_RUN_AND_EVENT_ID_REQUIRED");
        }
        // The database lock is shared by all instances and held until commit. Assigning the
        // cursor here prevents a later-committing event from appearing behind an SSE cursor.
        RecommendationRunEntity run = runRepository.findByIdForUpdate(source.getRequestId())
                .orElseThrow(() -> new IllegalArgumentException("RECOMMENDATION_RUN_NOT_FOUND"));
        // Fence before duplicate/terminal handling: an expired worker cannot append through either API.
        if (leased) {
            if (lease == null) throw new StaleExecutionLeaseException(source.getRequestId());
            lease.assertCurrent(run, RecommendationDatabaseClock.now(entityManager));
        } else if (run.isRecoverable()) {
            throw new StaleExecutionLeaseException(source.getRequestId());
        }
        if (run.isRecoverable() && isTerminalEvent(source)) {
            // Only RuntimeStore may atomically commit a recoverable Run's terminal state and event.
            throw new IllegalArgumentException("RECOMMENDATION_TERMINAL_EVENT_REQUIRES_ATOMIC_RUN_FINALIZATION");
        }
        RecommendationRunEventEntity existing = eventRepository.findByRunIdAndId(
                source.getRequestId(), source.getEventId()).orElse(null);
        if (existing != null) {
            source.setSequence(existing.getSequence());
            return existing;
        }
        if (!DynamicSubAgentRuntime.RunStatus.RUNNING.name().equals(run.getStatus())) {
            throw new IllegalStateException("RECOMMENDATION_EVENT_RUN_TERMINAL");
        }
        int sequence = Math.addExact(eventRepository.findTopByRunIdOrderBySequenceDesc(source.getRequestId())
                .map(RecommendationRunEventEntity::getSequence).orElse(0), 1);
        source.setSequence(sequence);
        return eventRepository.save(RecommendationRunEventEntity.builder()
                .id(source.getEventId()).runId(source.getRequestId()).sequence(sequence)
                .type(source.getType() == null ? "event" : source.getType())
                .name(source.getName() == null ? "event" : source.getName())
                .status(source.getStatus() == null ? "running" : source.getStatus())
                .summary(source.getSummary()).dataJson(writeJson(source.getData()))
                .elapsedMs(source.getElapsedMs())
                .createdAt(source.getTimestamp() == null ? clock.instant() : source.getTimestamp()).build());
    }

    private static boolean isTerminalEvent(AgentRunEvent event) {
        return "run_completed".equals(event.getType()) || "run_failed".equals(event.getType())
                || "run.completed".equals(event.getName()) || "run.failed".equals(event.getName())
                || "run.blocked".equals(event.getName()) || "run.cancelled".equals(event.getName());
    }

    public SseEmitter stream(String runId, String lastEventId) {
        if (!runRepository.existsById(runId)) {
            throw new IllegalArgumentException("RECOMMENDATION_RUN_NOT_FOUND");
        }
        int cursor = resolveLastSequence(runId, lastEventId);
        SseEmitter emitter = createEmitter();
        Subscription subscription = new Subscription(runId, emitter, cursor, clock.millis());
        synchronized (subscribers) {
            if (closed.get()) throw new IllegalStateException("RECOMMENDATION_EVENT_SERVICE_CLOSED");
            long runSubscribers = subscribers.values().stream().filter(s -> s.runId.equals(runId)).count();
            if (subscribers.size() >= properties.getMaxSubscribers()
                    || runSubscribers >= properties.getMaxSubscribersPerRun()) {
                rejected.increment();
                throw new RejectedExecutionException("RECOMMENDATION_SSE_SUBSCRIBER_LIMIT");
            }
            subscribers.put(subscription.id, subscription);
        }
        emitter.onCompletion(() -> remove(subscription));
        emitter.onTimeout(() -> remove(subscription));
        emitter.onError(ignored -> remove(subscription));
        schedule(subscription);
        return emitter;
    }

    /** Compatibility entry point; large histories must be fetched with the cursor overload. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String runId) {
        return history(runId, 0, properties.getHistoryMaxPageSize());
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String runId, int afterSequence, int limit) {
        if (afterSequence < 0 || limit < 1 || limit > properties.getHistoryMaxPageSize()) {
            throw new IllegalArgumentException("RECOMMENDATION_EVENT_PAGE_INVALID");
        }
        if (!runRepository.existsById(runId)) {
            throw new IllegalArgumentException("RECOMMENDATION_RUN_NOT_FOUND");
        }
        return eventRepository.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(
                runId, afterSequence, PageRequest.of(0, limit)).stream().map(this::payload).toList();
    }

    @Scheduled(fixedDelayString = "${agent.events.poll-interval-ms:250}")
    public void poll() {
        for (Subscription subscription : subscribers.values()) {
            if (expired(subscription)) {
                remove(subscription);
                try {
                    sender.execute(() -> finishEmitter(subscription.emitter));
                } catch (RejectedExecutionException ignored) {
                    // The servlet emitter timeout also closes this connection. Never block the scheduler.
                }
            } else {
                schedule(subscription);
            }
        }
    }

    /** A hint only: the final page must be drained after the database status becomes terminal. */
    public void complete(String runId) {
        subscribers.values().stream().filter(s -> s.runId.equals(runId)).forEach(this::schedule);
    }

    public Map<String, Object> snapshot() {
        int activeSenders = sender instanceof ThreadPoolExecutor pool ? pool.getActiveCount() : 0;
        int queuedSends = sender instanceof ThreadPoolExecutor pool ? pool.getQueue().size() : 0;
        return Map.of("subscribers", subscribers.size(), "activeSenders", activeSenders,
                "queuedSends", queuedSends, "deliveredEvents", delivered.sum(),
                "failedStreams", failed.sum(), "rejectedSubscriptions", rejected.sum());
    }

    protected SseEmitter createEmitter() {
        return new SseEmitter(properties.getStreamTimeoutMs());
    }

    private void schedule(Subscription subscription) {
        if (closed.get() || subscription.closed.get() || !subscription.scheduled.compareAndSet(false, true)) return;
        try {
            sender.execute(() -> {
                try {
                    tail(subscription);
                } catch (Exception error) {
                    failed.increment();
                    remove(subscription);
                    try {
                        subscription.emitter.completeWithError(error);
                    } catch (Exception ignored) {
                        // The servlet container may have already closed an errored connection.
                    }
                } finally {
                    subscription.scheduled.set(false);
                }
            });
        } catch (RejectedExecutionException ignored) {
            subscription.scheduled.set(false); // Retry on a later tick, without queuing unbounded work.
        }
    }

    private void tail(Subscription subscription) throws IOException {
        if (subscription.closed.get()) return;
        if (expired(subscription)) {
            remove(subscription);
            finishEmitter(subscription.emitter);
            return;
        }
        // Read terminal status BEFORE the page. All appends take the same run row lock and
        // reject terminal runs, so this page includes every event committed before finalization.
        String status = runRepository.findStatusValueById(subscription.runId)
                .orElseThrow(() -> new IllegalArgumentException("RECOMMENDATION_RUN_NOT_FOUND"));
        if (!subscription.ready) {
            subscription.emitter.send(SseEmitter.event().name(STREAM_READY_EVENT).data(Map.of(
                    "runId", subscription.runId, "status", status, "timestamp", clock.instant().toString())));
            subscription.ready = true;
            subscription.lastSentAt = clock.millis();
        }
        List<RecommendationRunEventEntity> page = eventRepository
                .findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(
                        subscription.runId, subscription.cursor, PageRequest.of(0, properties.getPageSize()));
        for (RecommendationRunEventEntity event : page) {
            if (subscription.closed.get()) return;
            subscription.emitter.send(SseEmitter.event().id(event.getId()).name(event.getName()).data(payload(event)));
            subscription.cursor = event.getSequence();
            subscription.lastSentAt = clock.millis();
            delivered.increment();
        }
        if (!DynamicSubAgentRuntime.RunStatus.RUNNING.name().equals(status)
                && page.size() < properties.getPageSize()) {
            remove(subscription);
            finishEmitter(subscription.emitter);
        } else if (clock.millis() - subscription.lastSentAt >= properties.getHeartbeatIntervalMs()) {
            subscription.emitter.send(SseEmitter.event().comment("heartbeat"));
            subscription.lastSentAt = clock.millis();
        }
    }

    private boolean expired(Subscription subscription) {
        return clock.millis() - subscription.startedAt >= properties.getStreamTimeoutMs();
    }

    private void remove(Subscription subscription) {
        subscription.closed.set(true);
        subscribers.remove(subscription.id, subscription);
    }

    private static void finishEmitter(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception ignored) {
            // Completion is idempotent from the service's perspective.
        }
    }

    @PreDestroy
    public void shutdown() {
        List<Subscription> current;
        synchronized (subscribers) {
            closed.set(true);
            current = List.copyOf(subscribers.values());
        }
        for (Subscription subscription : current) {
            remove(subscription);
            try {
                sender.execute(() -> finishEmitter(subscription.emitter));
            } catch (RejectedExecutionException ignored) {
                // Container shutdown closes the remaining HTTP connections.
            }
        }
        sender.shutdownNow();
    }

    private static ExecutorService createSender(RecommendationEventProperties properties) {
        properties.validate();
        AtomicInteger sequence = new AtomicInteger();
        return new ThreadPoolExecutor(properties.getSenderThreads(), properties.getSenderThreads(),
                0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(properties.getSenderQueueCapacity()), runnable -> {
                    Thread thread = new Thread(runnable, "recommendation-sse-" + sequence.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    private Map<String, Object> payload(RecommendationRunEventEntity event) {
        return Map.of("eventId", event.getId(), "runId", event.getRunId(), "sequence", event.getSequence(),
                "type", event.getType(), "name", event.getName(), "status", event.getStatus(),
                "summary", event.getSummary() == null ? "" : event.getSummary(), "elapsedMs", event.getElapsedMs(),
                "timestamp", event.getCreatedAt().toString(), "data", readJson(event.getDataJson()));
    }

    private int resolveLastSequence(String runId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) return 0;
        return eventRepository.findByRunIdAndId(runId, lastEventId).map(RecommendationRunEventEntity::getSequence)
                .orElseThrow(() -> new IllegalArgumentException("RECOMMENDATION_EVENT_CURSOR_NOT_FOUND"));
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception error) {
            throw new IllegalStateException("RECOMMENDATION_EVENT_SERIALIZATION_FAILED", error);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {});
        } catch (Exception error) {
            return Map.of("serializationError", true);
        }
    }

    private static final class Subscription {
        private final String id = UUID.randomUUID().toString();
        private final String runId;
        private final SseEmitter emitter;
        private final long startedAt;
        private final AtomicBoolean scheduled = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();
        private int cursor;
        private long lastSentAt;
        private boolean ready;

        private Subscription(String runId, SseEmitter emitter, int cursor, long now) {
            this.runId = runId;
            this.emitter = emitter;
            this.cursor = cursor;
            this.startedAt = now;
            this.lastSentAt = now;
        }
    }
}
