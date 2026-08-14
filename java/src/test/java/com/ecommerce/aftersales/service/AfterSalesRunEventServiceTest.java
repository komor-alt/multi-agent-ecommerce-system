package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEventEntity;
import com.ecommerce.aftersales.repository.AfterSalesRunEventRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AfterSalesRunEventServiceTest {

    /**
     * 不依赖 Servlet 容器：按发送顺序捕获每一帧的「帧头 + data 载荷」。
     * SseEmitter 的 builder 把 event:/id:/data: 行累积在私有 sb 字段中，
     * build() 返回有序的 data 载荷集合——测试通过它们断言握手事件与历史回放的线格式。
     */
    private static final class RecordingSseEmitter extends SseEmitter {
        private final List<CapturedFrame> frames = new ArrayList<>();
        private boolean completed;

        RecordingSseEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) {
            frames.add(new CapturedFrame(builder));
        }

        @Override
        public void complete() {
            completed = true;
        }
    }

    private static final class DisconnectedSseEmitter extends SseEmitter {
        private boolean disconnected;
        private int sendAttempts;
        private int completionAttempts;

        DisconnectedSseEmitter() {
            super(0L);
        }

        @Override
        public void send(SseEventBuilder builder) throws java.io.IOException {
            sendAttempts++;
            if (disconnected) {
                throw new java.io.IOException("client disconnected");
            }
        }

        @Override
        public void complete() {
            completionAttempts++;
            throw new IllegalStateException("AsyncContext already closed");
        }
    }

    private static final class CapturedFrame {
        /** 帧头（event:/id:/data: 行），来自 builder.build() 中的 TEXT_PLAIN 文本载荷。 */
        private final String headers;
        /** data 载荷（Map），来自 builder.build() 中的非文本载荷。 */
        private final Map<String, Object> payload;

        CapturedFrame(SseEmitter.SseEventBuilder builder) {
            StringBuilder text = new StringBuilder();
            Map<String, Object> data = Map.of();
            for (DataWithMediaType element : builder.build()) {
                if (element.getData() instanceof String line) {
                    text.append(line);
                } else if (element.getData() instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> typed = (Map<String, Object>) map;
                    data = typed;
                }
            }
            this.headers = text.toString();
            this.payload = data;
        }
    }

    private static final class TestableEventService extends AfterSalesRunEventService {
        TestableEventService(AfterSalesRunEventRepository eventRepository,
                             AfterSalesRunRepository runRepository,
                             ObjectMapper objectMapper) {
            super(eventRepository, runRepository, objectMapper);
        }

        @Override
        protected SseEmitter createEmitter() {
            return new RecordingSseEmitter();
        }
    }

    private final AfterSalesRunEventRepository eventRepository = mock(AfterSalesRunEventRepository.class);
    private final AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
    private final AfterSalesRunEventService service =
            new TestableEventService(eventRepository, runRepository, new ObjectMapper());

    @Test
    void streamSendsStreamReadyFirstThenReplaysHistoryWithoutPersisting() {
        when(runRepository.existsById("run-1")).thenReturn(true);
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run("RUNNING")));
        when(eventRepository.findTopByRunIdOrderBySequenceDesc("run-1"))
                .thenReturn(Optional.of(event("e2", 2, "tool_completed")));
        when(eventRepository.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc("run-1", 0))
                .thenReturn(List.of(event("e1", 1, "run_started"), event("e2", 2, "tool_completed")));

        RecordingSseEmitter emitter = (RecordingSseEmitter) service.stream("run-1", null);

        // stream_ready + 2 条历史事件；stream_ready 必须是第一帧。
        assertThat(emitter.frames).hasSize(3);

        CapturedFrame readyFrame = emitter.frames.get(0);
        assertThat(readyFrame.headers).contains("event:stream_ready");
        // 握手事件不带 id：浏览器不会用它更新 Last-Event-ID。
        assertThat(readyFrame.headers).doesNotContain("id:");
        Map<String, Object> readyPayload = readyFrame.payload;
        assertThat(readyPayload.get("type")).isEqualTo("stream_ready");
        assertThat(readyPayload.get("runId")).isEqualTo("run-1");
        // 握手事件没有 eventId / sequence：不占时间线事件计数、不影响持久化 sequence。
        assertThat(readyPayload).doesNotContainKeys("eventId", "sequence");

        CapturedFrame historyFrame1 = emitter.frames.get(1);
        assertThat(historyFrame1.headers).contains("event:run_started");
        assertThat(historyFrame1.headers).contains("id:e1");
        assertThat(historyFrame1.payload.get("sequence")).isEqualTo(1);

        CapturedFrame historyFrame2 = emitter.frames.get(2);
        assertThat(historyFrame2.headers).contains("id:e2");

        // stream_ready 不落库。
        verify(eventRepository, never()).save(any());
    }

    @Test
    void streamReadyIsStillFirstFrameWhenResumingFromLastEventId() {
        when(runRepository.existsById("run-1")).thenReturn(true);
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run("RUNNING")));
        when(eventRepository.findByRunIdAndId("run-1", "e2"))
                .thenReturn(Optional.of(event("e2", 2, "tool_completed")));
        when(eventRepository.findTopByRunIdOrderBySequenceDesc("run-1"))
                .thenReturn(Optional.of(event("e3", 3, "run_completed")));
        when(eventRepository.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc("run-1", 2))
                .thenReturn(List.of(event("e3", 3, "run_completed")));

        RecordingSseEmitter emitter = (RecordingSseEmitter) service.stream("run-1", "e2");

        // 只回放 sequence > 2 的 e3，但 stream_ready 依然是第一帧。
        assertThat(emitter.frames).hasSize(2);
        assertThat(emitter.frames.get(0).headers).contains("event:stream_ready");
        assertThat(emitter.frames.get(1).headers).contains("id:e3");
        verify(eventRepository).findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(eq("run-1"), eq(2));
        verify(eventRepository, never()).save(any());
    }

    @Test
    void streamOnTerminalRunSendsStreamReadyThenHistoryThenCompletes() {
        when(runRepository.existsById("run-1")).thenReturn(true);
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run("FAILED")));
        when(eventRepository.findTopByRunIdOrderBySequenceDesc("run-1"))
                .thenReturn(Optional.of(event("e2", 2, "error")));
        when(eventRepository.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc("run-1", 0))
                .thenReturn(List.of(event("e1", 1, "run_started"), event("e2", 2, "error")));

        RecordingSseEmitter emitter = (RecordingSseEmitter) service.stream("run-1", null);

        assertThat(emitter.frames).hasSize(3);
        assertThat(emitter.frames.get(0).headers).contains("event:stream_ready");
        assertThat(emitter.completed).isTrue();
        verify(eventRepository, never()).save(any());
    }

    @Test
    void disconnectedSubscriberCannotBreakBusinessEventAppend() {
        DisconnectedSseEmitter emitter = new DisconnectedSseEmitter();
        AfterSalesRunEventService localService = new AfterSalesRunEventService(
                eventRepository, runRepository, new ObjectMapper()) {
            @Override
            protected SseEmitter createEmitter() {
                return emitter;
            }
        };
        when(runRepository.existsById("run-1")).thenReturn(true);
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run("RUNNING")));
        when(eventRepository.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc("run-1", 0))
                .thenReturn(List.of());
        when(eventRepository.findTopByRunIdOrderBySequenceDesc("run-1")).thenReturn(Optional.empty());
        when(eventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        localService.stream("run-1", null);
        emitter.disconnected = true;

        assertThatCode(() -> localService.append(
                "run-1", "approval_recorded", "approved", "success", "approved",
                Map.of("summary", "approved"))).doesNotThrowAnyException();
        // 断线前已有 1 次握手发送，append 再尝试 1 次推送即达 2；
        // 断线只移除订阅者、不触发 complete（连接收尾交给容器）。
        assertThat(emitter.sendAttempts).isEqualTo(2);
        assertThat(emitter.completionAttempts).isZero();

        // 断线订阅者被移除后，后续 append 不再尝试发送，业务事务不受影响。
        assertThatCode(() -> localService.append(
                "run-1", "execution_started", "started", "running", "started",
                Map.of("summary", "started"))).doesNotThrowAnyException();
        assertThat(emitter.sendAttempts).isEqualTo(2);
    }

    @Test
    void streamOnMissingRunThrowsWithoutSendingAnything() {
        when(runRepository.existsById("nope")).thenReturn(false);

        try {
            service.stream("nope", null);
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException error) {
            assertThat(error.getMessage()).isEqualTo("AGENT_RUN_NOT_FOUND");
        }
    }

    private AfterSalesRunEntity run(String status) {
        return AfterSalesRunEntity.builder()
                .id("run-1")
                .ticketId("ticket-1")
                .status(status)
                .maxSteps(6)
                .stepCount(0)
                .build();
    }

    private AfterSalesRunEventEntity event(String id, int sequence, String type) {
        return AfterSalesRunEventEntity.builder()
                .id(id)
                .runId("run-1")
                .sequence(sequence)
                .type(type)
                .name("n")
                .status("running")
                .summary("s")
                .dataJson("{\"summary\":\"s\"}")
                .createdAt(Instant.now())
                .build();
    }
}
