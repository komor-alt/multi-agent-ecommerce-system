package com.ecommerce.runtime.persistence;

import com.ecommerce.model.AgentRunEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RecommendationRunEventDeliveryTest {
    private final RecommendationRunEventRepository events = mock(RecommendationRunEventRepository.class);
    private final RecommendationRunRepository runs = mock(RecommendationRunRepository.class);
    private final List<RecommendationRunEventEntity> committed = new ArrayList<>();
    private final AtomicReference<String> status = new AtomicReference<>("RUNNING");
    private final MutableClock clock = new MutableClock();
    private final QueuedExecutor executor = new QueuedExecutor();
    private final RecommendationEventProperties properties = new RecommendationEventProperties();

    @BeforeEach
    void repositories() {
        properties.setPageSize(2);
        when(runs.existsById("run-1")).thenReturn(true);
        when(runs.findStatusValueById("run-1")).thenAnswer(ignored -> Optional.of(status.get()));
        when(events.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(eq("run-1"), anyInt(), any(Pageable.class)))
                .thenAnswer(invocation -> committed.stream()
                        .filter(event -> event.getSequence() > invocation.<Integer>getArgument(1))
                        .limit(invocation.<Pageable>getArgument(2).getPageSize()).toList());
    }

    @Test
    void replayAndLiveEventsShareOnePagedCursorAndTerminalDrainsEveryPage() {
        committed.addAll(List.of(event(1), event(2), event(3), event(4), event(5)));
        when(events.findByRunIdAndId("run-1", "event-1")).thenReturn(Optional.of(event(1)));
        TestService reader = service();
        RecordingEmitter emitter = (RecordingEmitter) reader.stream("run-1", "event-1");
        assertThat(emitter.headers).isEmpty(); // Registration never sends on an HTTP/agent thread.
        executor.runAll();
        assertThat(emitter.ids()).containsExactly("event-2", "event-3");
        assertThat(emitter.headers.get(0)).contains("event:stream_ready").doesNotContain("id:");

        committed.add(event(6)); // Represents a commit on a different application instance.
        status.set("COMPLETED");
        reader.complete("run-1");
        executor.runAll();
        assertThat(emitter.ids()).containsExactly("event-2", "event-3", "event-4", "event-5");
        assertThat(emitter.completed).isFalse();
        reader.poll();
        executor.runAll();
        assertThat(emitter.ids()).containsExactly("event-2", "event-3", "event-4", "event-5", "event-6");
        assertThat(emitter.completed).isTrue();
        assertThat(reader.snapshot()).containsEntry("subscribers", 0);
    }

    @Test
    void terminalCommitAfterAnEmptyPageDoesNotLoseFinalEvent() {
        AtomicBoolean firstPage = new AtomicBoolean(true);
        when(events.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(eq("run-1"), anyInt(), any(Pageable.class)))
                .thenAnswer(invocation -> {
                    if (firstPage.getAndSet(false)) {
                        // The SELECT saw no events; final event and terminal status commit just afterwards.
                        committed.add(event(1));
                        status.set("COMPLETED");
                        return List.of();
                    }
                    return List.copyOf(committed);
                });
        TestService reader = service();
        RecordingEmitter emitter = (RecordingEmitter) reader.stream("run-1", null);
        executor.runAll();
        assertThat(emitter.completed).isFalse();
        reader.poll();
        executor.runAll();
        assertThat(emitter.ids()).containsExactly("event-1");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    void completionHintWhileRunIsRunningDoesNotCloseStream() {
        TestService reader = service();
        RecordingEmitter emitter = (RecordingEmitter) reader.stream("run-1", null);
        reader.complete("run-1");
        reader.poll();
        assertThat(executor.tasks).hasSize(1); // One outstanding delivery per subscription.
        executor.runAll();
        assertThat(emitter.completed).isFalse();
        assertThat(reader.snapshot()).containsEntry("subscribers", 1);
    }

    @Test
    void admissionAndTimeoutBoundSubscriptionsAndHeartbeatDoesNotAdvanceCursor() {
        properties.setMaxSubscribers(1);
        properties.setMaxSubscribersPerRun(1);
        TestService reader = service();
        RecordingEmitter emitter = (RecordingEmitter) reader.stream("run-1", null);
        assertThatThrownBy(() -> reader.stream("run-1", null))
                .isInstanceOf(RejectedExecutionException.class).hasMessage("RECOMMENDATION_SSE_SUBSCRIBER_LIMIT");
        executor.runAll();
        clock.advance(properties.getHeartbeatIntervalMs());
        reader.poll();
        executor.runAll();
        assertThat(emitter.headers.get(1)).contains(":heartbeat").doesNotContain("id:");
        clock.advance(properties.getStreamTimeoutMs());
        reader.poll();
        executor.runAll();
        assertThat(emitter.completed).isTrue();
        assertThat(reader.snapshot()).containsEntry("subscribers", 0).containsEntry("rejectedSubscriptions", 1L);
        reader.stream("run-1", null);
        assertThat(reader.snapshot()).containsEntry("subscribers", 1);
        reader.shutdown();
        assertThat(reader.snapshot()).containsEntry("subscribers", 0);
        assertThat(executor.isShutdown()).isTrue();
    }

    @Test
    void disconnectedClientIsRemovedAndDoesNotAffectAnotherSubscription() {
        committed.add(event(1));
        TestService reader = service();
        RecordingEmitter bad = (RecordingEmitter) reader.stream("run-1", null);
        bad.failSend = true;
        RecordingEmitter good = (RecordingEmitter) reader.stream("run-1", null);
        executor.runAll();
        assertThat(bad.error).isInstanceOf(IOException.class);
        assertThat(good.ids()).containsExactly("event-1");
        assertThat(reader.snapshot()).containsEntry("subscribers", 1).containsEntry("failedStreams", 1L);
    }

    @Test
    void boundedExecutorRejectionRetriesOnNextPollWithoutSendingInline() {
        executor.reject = true;
        TestService reader = service();
        RecordingEmitter emitter = (RecordingEmitter) reader.stream("run-1", null);
        assertThat(emitter.headers).isEmpty();
        executor.reject = false;
        reader.poll();
        executor.runAll();
        assertThat(emitter.headers).hasSize(1);
    }

    @Test
    void invalidOrForeignCursorAndUnboundedHistoryRequestsAreRejected() {
        TestService reader = service();
        assertThatThrownBy(() -> reader.stream("run-1", "foreign-event"))
                .hasMessage("RECOMMENDATION_EVENT_CURSOR_NOT_FOUND");
        assertThatThrownBy(() -> reader.history("run-1", -1, 10)).hasMessage("RECOMMENDATION_EVENT_PAGE_INVALID");
        assertThatThrownBy(() -> reader.history("run-1", 0, properties.getHistoryMaxPageSize() + 1))
                .hasMessage("RECOMMENDATION_EVENT_PAGE_INVALID");
        committed.addAll(List.of(event(1), event(2), event(3)));
        assertThat(reader.history("run-1", 1, 1)).extracting(row -> row.get("eventId")).containsExactly("event-2");
    }

    @Test
    void appendAllocatesCommittedDatabaseSequenceAndRepeatedEventIsIdempotent() {
        RecommendationRunEntity run = RecommendationRunEntity.builder().id("run-1").status("RUNNING").build();
        when(runs.findByIdForUpdate("run-1")).thenReturn(Optional.of(run));
        when(events.findTopByRunIdOrderBySequenceDesc("run-1")).thenReturn(Optional.of(event(7)));
        when(events.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        TestService writer = service();
        AgentRunEvent source = AgentRunEvent.builder().requestId("run-1").eventId("new-event").sequence(999).build();
        RecommendationRunEventEntity saved = writer.append(source);
        assertThat(saved.getSequence()).isEqualTo(8);
        assertThat(source.getSequence()).isEqualTo(8);
        when(events.findByRunIdAndId("run-1", "new-event")).thenReturn(Optional.of(saved));
        run.setStatus("COMPLETED");
        assertThat(writer.append(source)).isSameAs(saved);
        verify(events).save(any());
        assertThatThrownBy(() -> writer.append(AgentRunEvent.builder().requestId("run-1").build()))
                .hasMessage("RECOMMENDATION_EVENT_RUN_TERMINAL");
    }

    private TestService service() {
        return new TestService(events, runs, properties, clock, executor);
    }

    static RecommendationRunEventEntity event(int sequence) {
        return RecommendationRunEventEntity.builder().id("event-" + sequence).runId("run-1")
                .sequence(sequence).type("runtime").name("progress").status("running")
                .dataJson("{}").createdAt(Instant.parse("2026-09-25T00:00:00Z")).build();
    }

    static final class TestService extends RecommendationRunEventService {
        TestService(RecommendationRunEventRepository events, RecommendationRunRepository runs,
                    RecommendationEventProperties properties, Clock clock, QueuedExecutor executor) {
            super(events, runs, new ObjectMapper(), properties, clock, executor);
        }

        @Override
        protected SseEmitter createEmitter() {
            return new RecordingEmitter();
        }
    }

    static final class RecordingEmitter extends SseEmitter {
        final List<String> headers = new ArrayList<>();
        final List<Map<?, ?>> payloads = new ArrayList<>();
        boolean completed;
        boolean failSend;
        Throwable error;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (failSend) throw new IOException("client disconnected");
            StringBuilder header = new StringBuilder();
            for (DataWithMediaType item : builder.build()) {
                if (item.getData() instanceof String text) header.append(text);
                if (item.getData() instanceof Map<?, ?> data) payloads.add(data);
            }
            headers.add(header.toString());
        }

        List<Object> ids() {
            return payloads.stream().filter(row -> row.containsKey("eventId"))
                    .map(row -> (Object) row.get("eventId")).toList();
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable failure) {
            error = failure;
            completed = true;
        }
    }

    static final class QueuedExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean stopped;
        boolean reject;

        void runAll() {
            while (!tasks.isEmpty()) tasks.removeFirst().run();
        }

        @Override
        public void execute(Runnable task) {
            if (stopped || reject) throw new RejectedExecutionException();
            tasks.add(task);
        }

        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; List<Runnable> pending = List.copyOf(tasks); tasks.clear(); return pending; }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }

    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-25T00:00:00Z");
        void advance(long milliseconds) { now = now.plusMillis(milliseconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
