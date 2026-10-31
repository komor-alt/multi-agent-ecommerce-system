package com.ecommerce.eval.benchmark;

import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.runtime.persistence.RecommendationRunEntity;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.runtime.persistence.RecommendationRunRepository;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Benchmark-only persisted handoff between independent recommendation runs.
 * The production API has no cross-run inheritance contract; this harness makes
 * every inherited source explicit and verifiable instead of pretending that a
 * concatenated conversation fixture is durable state management. It reads the
 * persisted Run request, RunView tasks/artifacts, and terminal event cursor; it
 * does not expose or resume a pipeline checkpoint.
 */
final class AgentBenchmarkCrossTurnHarness {
    static final String STATE_KEY = "benchmarkCrossTurnState";

    private final RecommendationRuntimeStore runtimeStore;
    private final RecommendationRunRepository runRepository;
    private final RecommendationRunEventService eventService;
    private final ObjectMapper mapper;

    AgentBenchmarkCrossTurnHarness(
            RecommendationRuntimeStore runtimeStore,
            RecommendationRunRepository runRepository,
            RecommendationRunEventService eventService,
            ObjectMapper mapper) {
        this.runtimeStore = runtimeStore;
        this.runRepository = runRepository;
        this.eventService = eventService;
        this.mapper = mapper;
    }

    RecommendationRequest continueFrom(String sourceRunId, TurnUpdate update) {
        Objects.requireNonNull(update, "update");
        RecommendationRunEntity source = runRepository.findById(sourceRunId)
                .orElseThrow(() -> new IllegalArgumentException("CROSS_TURN_SOURCE_RUN_NOT_FOUND"));
        if ("RUNNING".equals(source.getStatus())) {
            throw new IllegalStateException("CROSS_TURN_SOURCE_RUN_NOT_TERMINAL");
        }
        RecommendationRequest previous = readRequest(source.getRequestJson());
        DynamicSubAgentRuntime.RunView runView = runtimeStore.findRunView(sourceRunId)
                .orElseThrow(() -> new IllegalStateException("CROSS_TURN_SOURCE_VIEW_MISSING"));
        List<Map<String, Object>> events = eventService.history(sourceRunId);
        if (events.isEmpty()) throw new IllegalStateException("CROSS_TURN_SOURCE_EVENTS_MISSING");

        List<String> overwritten = new ArrayList<>();
        Map<String, Object> context = new LinkedHashMap<>(
                previous.getContext() == null ? Map.of() : previous.getContext());
        context.remove(STATE_KEY);
        update.contextUpdates().forEach((key, value) -> {
            if (context.containsKey(key) && !Objects.equals(context.get(key), value)) {
                overwritten.add("context." + key);
            }
            context.put(key, value);
        });
        if (update.intentText() != null) {
            if (context.containsKey("intentText")
                    && !Objects.equals(context.get("intentText"), update.intentText())) {
                overwritten.add("context.intentText");
            }
            context.put("intentText", update.intentText());
        }

        String country = latest("country", previous.getCountry(), update.country(), overwritten);
        String region = latest("region", previous.getRegion(), update.region(), overwritten);
        String locale = latest("locale", previous.getLocale(), update.locale(), overwritten);
        String currency = latest("currency", previous.getCurrency(), update.currency(), overwritten);
        String platform = latest("platform", previous.getPlatform(), update.platform(), overwritten);
        int numItems = update.numItems() == null ? previous.getNumItems() : update.numItems();
        if (update.numItems() != null && previous.getNumItems() != update.numItems()) {
            overwritten.add("numItems");
        }

        Map<String, Object> lastEvent = events.get(events.size() - 1);
        List<Map<String, Object>> artifactSources = runView.artifacts().stream().map(artifact -> Map.<String, Object>of(
                "artifactId", artifact.artifactId(),
                "type", artifact.type(),
                "baseCandidateVersion", artifact.baseCandidateVersion(),
                "evidenceIds", artifact.evidenceIds())).toList();
        List<Map<String, Object>> taskSources = runView.tasks().stream().map(task -> Map.<String, Object>of(
                "taskId", task.taskId(),
                "role", task.role(),
                "status", task.status().name(),
                "artifactIds", task.artifactIds())).toList();
        Map<String, Object> runRevision = new LinkedHashMap<>();
        runRevision.put("stateVersion", source.getStateVersion());
        runRevision.put("executionAttempt", source.getExecutionAttempt());
        runRevision.put("requestInherited", true);
        Map<String, Object> handoff = new LinkedHashMap<>();
        handoff.put("sourceRunId", sourceRunId);
        handoff.put("sourceStatus", source.getStatus());
        handoff.put("sourceRunRevision", runRevision);
        handoff.put("sourceEventId", lastEvent.get("eventId"));
        handoff.put("sourceEventSequence", lastEvent.get("sequence"));
        handoff.put("sourceEventName", lastEvent.get("name"));
        handoff.put("sourceTasks", taskSources);
        handoff.put("sourceArtifacts", artifactSources);
        handoff.put("overriddenKeys", List.copyOf(overwritten));
        context.put(STATE_KEY, Map.copyOf(handoff));

        return RecommendationRequest.builder()
                .userId(previous.getUserId())
                .scene(previous.getScene())
                .numItems(numItems)
                .platform(platform)
                .region(region)
                .country(country)
                .locale(locale)
                .currency(currency)
                .context(Map.copyOf(context))
                .build();
    }

    private RecommendationRequest readRequest(String json) {
        try {
            return mapper.readValue(json, RecommendationRequest.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("CROSS_TURN_SOURCE_REQUEST_INVALID", error);
        }
    }

    private static String latest(String field, String previous, String update, List<String> overwritten) {
        if (update == null) return previous;
        if (!Objects.equals(previous, update)) overwritten.add(field);
        return update;
    }

    record TurnUpdate(String intentText, String country, String region, String locale,
                      String currency, String platform, Integer numItems,
                      Map<String, Object> contextUpdates) {
        TurnUpdate {
            contextUpdates = contextUpdates == null ? Map.of() : Map.copyOf(contextUpdates);
        }
    }
}
