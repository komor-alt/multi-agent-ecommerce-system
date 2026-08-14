package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEventEntity;
import com.ecommerce.aftersales.repository.AfterSalesRunEventRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class AfterSalesRunEventService {
    /** 握手事件：SSE 建立后立即发送，不落库、不带 id（不影响 Last-Event-ID），不计入时间线。 */
    static final String STREAM_READY_EVENT = "stream_ready";

    private final AfterSalesRunEventRepository eventRepository;
    private final AfterSalesRunRepository runRepository;
    private final ObjectMapper objectMapper;
    private final Map<String, Object> runLocks = new ConcurrentHashMap<>();
    private final Map<String, CopyOnWriteArrayList<SseEmitter>> subscribers = new ConcurrentHashMap<>();

    public AfterSalesRunEventService(
            AfterSalesRunEventRepository eventRepository,
            AfterSalesRunRepository runRepository,
            ObjectMapper objectMapper) {
        this.eventRepository = eventRepository;
        this.runRepository = runRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AfterSalesRunEventEntity append(
            String runId,
            String type,
            String name,
            String status,
            String summary,
            Map<String, Object> data) {
        Object lock = runLocks.computeIfAbsent(runId, ignored -> new Object());
        synchronized (lock) {
            int sequence = eventRepository.findTopByRunIdOrderBySequenceDesc(runId)
                    .map(event -> event.getSequence() + 1)
                    .orElse(1);
            AfterSalesRunEventEntity event = eventRepository.save(AfterSalesRunEventEntity.builder()
                    .id(UUID.randomUUID().toString())
                    .runId(runId)
                    .sequence(sequence)
                    .type(type)
                    .name(name)
                    .status(status)
                    .summary(summary)
                    .dataJson(writeJson(data))
                    .createdAt(Instant.now())
                    .build());
            publish(runId, event);
            return event;
        }
    }

    @Transactional(readOnly = true)
    public SseEmitter stream(String runId, String lastEventId) {
        if (!runRepository.existsById(runId)) {
            throw new IllegalArgumentException("AGENT_RUN_NOT_FOUND");
        }
        SseEmitter emitter = createEmitter();
        try {
            // 握手事件：连接建立后立即发送以刷新响应，让客户端尽早收到「流已就绪」信号。
            // 不落库、不带 id（浏览器不会更新 Last-Event-ID），不影响持久化 sequence 与时间线计数。
            emitter.send(SseEmitter.event()
                    .name(STREAM_READY_EVENT)
                    .data(streamReadyPayload(runId)));
        } catch (IOException error) {
            emitter.completeWithError(error);
            return emitter;
        }
        Object lock = runLocks.computeIfAbsent(runId, ignored -> new Object());
        synchronized (lock) {
            int lastSequence = resolveLastSequence(runId, lastEventId);
            List<AfterSalesRunEventEntity> history =
                    eventRepository.findByRunIdAndSequenceGreaterThanOrderBySequenceAsc(runId, lastSequence);
            try {
                for (AfterSalesRunEventEntity event : history) {
                    send(emitter, event);
                }
            } catch (IOException error) {
                emitter.completeWithError(error);
                return emitter;
            }

            AfterSalesRunEntity run = runRepository.findById(runId).orElseThrow();
            boolean terminalEvent = eventRepository.findTopByRunIdOrderBySequenceDesc(runId)
                    .map(this::isWorkflowTerminal)
                    .orElse(false);
            if ("FAILED".equals(run.getStatus()) || terminalEvent) {
                emitter.complete();
                return emitter;
            }
            subscribers.computeIfAbsent(runId, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        }

        Runnable remove = () -> subscribers.getOrDefault(runId, new CopyOnWriteArrayList<>()).remove(emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        return emitter;
    }

    /** 测试可覆写：注入能捕获 SSE 帧的 SseEmitter。 */
    protected SseEmitter createEmitter() {
        return new SseEmitter(0L);
    }

    private Map<String, Object> streamReadyPayload(String runId) {
        return Map.of(
                "runId", runId,
                "type", STREAM_READY_EVENT,
                "name", "实时流已就绪",
                "status", "running",
                "timestamp", Instant.now().toString(),
                "data", Map.of("summary", "SSE 连接已建立，可以启动 Agent 运行。")
        );
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> history(String runId) {
        return eventRepository.findByRunIdOrderBySequenceAsc(runId).stream()
                .map(this::payload)
                .toList();
    }

    public void complete(String runId) {
        Object lock = runLocks.computeIfAbsent(runId, ignored -> new Object());
        synchronized (lock) {
            List<SseEmitter> current = subscribers.remove(runId);
            if (current != null) {
                current.forEach(SseEmitter::complete);
            }
        }
    }

    private boolean isWorkflowTerminal(AfterSalesRunEventEntity event) {
        if ("execution_completed".equals(event.getType()) || "error".equals(event.getType())) {
            return true;
        }
        return "approval_recorded".equals(event.getType())
                && "REJECTED".equals(readJson(event.getDataJson()).get("decision"));
    }
    private int resolveLastSequence(String runId, String lastEventId) {
        if (lastEventId == null || lastEventId.isBlank()) {
            return 0;
        }
        return eventRepository.findByRunIdAndId(runId, lastEventId)
                .map(AfterSalesRunEventEntity::getSequence)
                .orElse(0);
    }

    private void publish(String runId, AfterSalesRunEventEntity event) {
        List<SseEmitter> current = subscribers.get(runId);
        if (current == null) {
            return;
        }
        for (SseEmitter emitter : current) {
            try {
                send(emitter, event);
            } catch (Exception error) {
                // 客户端断线属于传输层事件，不能反向击穿审批/执行等业务事务。
                // send 失败表示容器已关闭或进入 ERROR：此时再次 complete 会触发 Tomcat
                // 的 AsyncContext 已完成异常；只把订阅者移除，连接收尾交给容器。
                current.remove(emitter);
            }
        }
    }

    private void send(SseEmitter emitter, AfterSalesRunEventEntity event) throws IOException {
        emitter.send(SseEmitter.event()
                .id(event.getId())
                .name(event.getType())
                .data(payload(event)));
    }

    private Map<String, Object> payload(AfterSalesRunEventEntity event) {
        return Map.of(
                "eventId", event.getId(),
                "runId", event.getRunId(),
                "sequence", event.getSequence(),
                "type", event.getType(),
                "name", event.getName(),
                "status", event.getStatus(),
                "timestamp", event.getCreatedAt().toString(),
                "data", readJson(event.getDataJson())
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("EVENT_SERIALIZATION_FAILED", error);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return objectMapper.readValue(value, new TypeReference<>() {
            });
        } catch (Exception error) {
            return Map.of("summary", "Event payload unavailable");
        }
    }
}


