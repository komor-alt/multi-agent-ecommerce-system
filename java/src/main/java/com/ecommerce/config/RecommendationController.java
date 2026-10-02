package com.ecommerce.config;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.BehaviorEventRequest;
import com.ecommerce.model.EvaluationReport;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationResponse;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.model.ToolLoopResponse;
import com.ecommerce.orchestrator.SupervisorOrchestrator;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.runtime.DynamicSubAgentRuntimeRegistry;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.runtime.persistence.RecommendationPersistenceMonitor;
import com.ecommerce.service.ABTestService;
import com.ecommerce.service.AgentConcurrencyGuard;
import com.ecommerce.service.AgentRunRejectedException;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.ConstrainedToolLoopService;
import com.ecommerce.service.DemoDataService;
import com.ecommerce.service.MetricsCollector;
import com.ecommerce.service.RecommendationEvaluator;
import com.ecommerce.service.RedisFeatureStoreService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/v1")
public class RecommendationController {
    private static final Logger log = LoggerFactory.getLogger(RecommendationController.class);

    private final SupervisorOrchestrator orchestrator;
    private final ABTestService abTestService;
    private final AutonomousAgentLoopService autonomousAgentLoopService;
    private final MetricsCollector metricsCollector;
    private final RecommendationEvaluator evaluator;
    private final ConstrainedToolLoopService toolLoopService;
    private final RedisFeatureStoreService featureStoreService;
    private final DemoDataService demoDataService;
    private final AgentConcurrencyGuard concurrencyGuard;
    private final DynamicSubAgentRuntimeRegistry subAgentRuntimeRegistry;
    private final RecommendationRunEventService recommendationRunEventService;
    private final RecommendationPersistenceMonitor recommendationPersistenceMonitor;
    private final Executor sseExecutor;

    public RecommendationController(
            SupervisorOrchestrator orchestrator,
            ABTestService abTestService,
            AutonomousAgentLoopService autonomousAgentLoopService,
            MetricsCollector metricsCollector,
            RecommendationEvaluator evaluator,
            ConstrainedToolLoopService toolLoopService,
            RedisFeatureStoreService featureStoreService,
            DemoDataService demoDataService,
            AgentConcurrencyGuard concurrencyGuard,
            DynamicSubAgentRuntimeRegistry subAgentRuntimeRegistry,
            RecommendationRunEventService recommendationRunEventService,
            RecommendationPersistenceMonitor recommendationPersistenceMonitor,
            @Qualifier("sseExecutor") Executor sseExecutor) {
        this.orchestrator = orchestrator;
        this.abTestService = abTestService;
        this.autonomousAgentLoopService = autonomousAgentLoopService;
        this.metricsCollector = metricsCollector;
        this.evaluator = evaluator;
        this.toolLoopService = toolLoopService;
        this.featureStoreService = featureStoreService;
        this.demoDataService = demoDataService;
        this.concurrencyGuard = concurrencyGuard;
        this.subAgentRuntimeRegistry = subAgentRuntimeRegistry;
        this.recommendationRunEventService = recommendationRunEventService;
        this.recommendationPersistenceMonitor = recommendationPersistenceMonitor;
        this.sseExecutor = sseExecutor;
    }

    @PostMapping("/recommend")
    public RecommendationResponse recommend(@RequestBody RecommendationRequest request) {
        try (AgentConcurrencyGuard.GuardLease ignored = acquireOrReject("recommend")) {
            AgentLoopResponse agentResponse = autonomousAgentLoopService.run(
                    ToolLoopRequest.builder().request(request).build());
            RecommendationResponse response = requireCompletedRecommendation(agentResponse);
            metricsCollector.recordRecommendation(response);
            return response;
        }
    }

    @PostMapping(value = "/recommend/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter recommendStream(@RequestBody RecommendationRequest request) {
        return startStream(ToolLoopRequest.builder().request(request).build(), "recommend_stream");
    }

    @PostMapping("/recommend/tool-loop")
    public ToolLoopResponse recommendWithToolLoop(@RequestBody ToolLoopRequest request) {
        try (AgentConcurrencyGuard.GuardLease ignored = acquireOrReject("tool_loop")) {
            ToolLoopResponse response = toolLoopService.run(request);
            metricsCollector.recordToolCalls(response.getToolCalls());
            if (response.getResponse() != null) {
                metricsCollector.recordRecommendation(response.getResponse());
            }
            return response;
        }
    }

    @PostMapping("/recommend/agent-loop")
    public AgentLoopResponse recommendWithAgentLoop(@RequestBody ToolLoopRequest request) {
        try (AgentConcurrencyGuard.GuardLease ignored = acquireOrReject("agent_loop")) {
            AgentLoopResponse response = autonomousAgentLoopService.run(request);
            metricsCollector.recordToolCalls(response.getToolCalls());
            if (response.getResponse() != null) {
                metricsCollector.recordRecommendation(response.getResponse());
            }
            return response;
        }
    }

    @PostMapping(value = "/recommend/agent-loop/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter recommendWithAgentLoopStream(@RequestBody ToolLoopRequest request) {
        return startStream(request, "agent_loop_stream");
    }

    /** Network delivery runs independently; disconnecting must not abort an admitted run. */
    private SseEmitter startStream(ToolLoopRequest request, String type) {
        AgentConcurrencyGuard.GuardLease lease = acquireOrReject(type);
        ToolLoopRequest prepared = null;
        SseEmitter emitter = null;
        try {
            prepared = autonomousAgentLoopService.prepareRun(request);
            emitter = recommendationRunEventService.stream(prepared.getRunId(), null);
            ToolLoopRequest admitted = prepared;
            sseExecutor.execute(() -> {
                try (lease) {
                    AgentLoopResponse response = autonomousAgentLoopService.runPrepared(admitted);
                    if (response != null) {
                        metricsCollector.recordToolCalls(response.getToolCalls());
                        metricsCollector.recordRecommendation(response.getResponse());
                    }
                } catch (RuntimeException error) {
                    log.warn("Recommendation run {} failed: {}", admitted.getRunId(), error.getClass().getSimpleName());
                    failDispatchSafely(admitted.getRunId(), "execution_failed", error);
                }
            });
            return emitter;
        } catch (RuntimeException error) {
            lease.close();
            if (prepared != null) {
                failDispatchSafely(prepared.getRunId(), "dispatch_rejected", error);
            }
            if (emitter != null) {
                try {
                    emitter.completeWithError(error);
                } catch (RuntimeException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            }
            if (error instanceof RejectedExecutionException) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Agent runtime busy");
            }
            throw error;
        }
    }

    private void failDispatchSafely(String runId, String reason, RuntimeException original) {
        try {
            autonomousAgentLoopService.failPreparedRun(runId, reason);
        } catch (RuntimeException cleanupError) {
            original.addSuppressed(cleanupError);
            log.error("Unable to persist failed recommendation run {}: {}", runId,
                    cleanupError.getClass().getSimpleName());
        }
    }

    @PostMapping("/evaluations/smoke")
    public Map<String, Object> smokeEvaluation(@RequestBody RecommendationRequest request) {
        try (AgentConcurrencyGuard.GuardLease ignored = acquireOrReject("smoke_evaluation")) {
            RecommendationResponse response = requireCompletedRecommendation(autonomousAgentLoopService.run(
                    ToolLoopRequest.builder().request(request).build()));
            metricsCollector.recordRecommendation(response);
            EvaluationReport report = evaluator.evaluate(request, response);
            return Map.of("evaluation", report, "response", response);
        }
    }

    /** Explicit deterministic baseline; it is intentionally not the product default. */
    @PostMapping("/recommend/workflow-baseline")
    public RecommendationResponse recommendWithWorkflowBaseline(@RequestBody RecommendationRequest request) {
        try (AgentConcurrencyGuard.GuardLease ignored = acquireOrReject("workflow_baseline")) {
            RecommendationResponse response = orchestrator.recommend(request);
            metricsCollector.recordRecommendation(response);
            return response;
        }
    }

    @GetMapping("/data/catalog")
    public List<Product> catalog() {
        return demoDataService.catalog();
    }

    @GetMapping("/data/catalog/summary")
    public Map<String, Object> catalogSummary() {
        return demoDataService.catalogSummary();
    }

    @PostMapping("/data/seed-behaviors")
    public Map<String, Object> seedBehaviors() {
        return demoDataService.seedBehaviors();
    }

    @PostMapping("/users/{userId}/behaviors")
    public Map<String, Object> recordBehavior(
            @PathVariable String userId,
            @RequestBody BehaviorEventRequest request) {
        return featureStoreService.recordBehavior(userId, request.getBehaviorType(), request.getProductId(), request.getMetadata());
    }

    @GetMapping("/users/{userId}/features")
    public Map<String, Object> getUserFeatures(@PathVariable String userId) {
        RecommendationRequest request = RecommendationRequest.builder().userId(userId).build();
        return featureStoreService.getUserFeatures(userId, request);
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> snapshot = new LinkedHashMap<>(metricsCollector.snapshot());
        snapshot.put("runtime_guard", concurrencyGuard.snapshot());
        snapshot.put("recommendation_persistence", recommendationPersistenceMonitor.snapshot());
        snapshot.put("recommendation_events", recommendationRunEventService.snapshot());
        return snapshot;
    }

    /** Inspect the live or recently completed child-task DAG for one Agent run. */
    @GetMapping("/agent-runs/{runId}/subagents")
    public DynamicSubAgentRuntime.RunView subAgentRun(@PathVariable String runId) {
        return subAgentRuntimeRegistry.find(runId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "Unknown Agent run: " + runId));
    }

    /** Replay persisted events and then continue streaming live events. */
    @GetMapping(value = "/agent-runs/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter recommendationRunEvents(
            @PathVariable String runId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        try {
            return recommendationRunEventService.stream(runId, lastEventId);
        } catch (IllegalArgumentException error) {
            throw eventRequestError(error);
        } catch (RejectedExecutionException error) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Event subscriber capacity reached");
        }
    }

    @GetMapping("/agent-runs/{runId}/event-history")
    public List<Map<String, Object>> recommendationRunEventHistory(
            @PathVariable String runId,
            @RequestParam(defaultValue = "0") int afterSequence,
            @RequestParam(defaultValue = "100") int limit) {
        try {
            return recommendationRunEventService.history(runId, afterSequence, limit);
        } catch (IllegalArgumentException error) {
            throw eventRequestError(error);
        }
    }

    private ResponseStatusException eventRequestError(IllegalArgumentException error) {
        HttpStatus status = "RECOMMENDATION_RUN_NOT_FOUND".equals(error.getMessage())
                ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return new ResponseStatusException(status, error.getMessage());
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "healthy", "language", "java");
    }

    @GetMapping("/experiments")
    public Map<String, Object> getExperiments() {
        return Map.of(
                "rec_strategy", Map.of(
                        "name", "Cross-border recommendation strategy experiment",
                        "groups", Map.of("control", "connector_filter", "treatment_llm", "llm_cross_border_rerank")
                )
        );
    }

    @PostMapping("/experiments/{experimentId}/outcome")
    public Map<String, String> recordOutcome(
            @PathVariable String experimentId,
            @RequestBody Map<String, Object> body) {
        return Map.of(
                "status", "accepted",
                "experimentId", experimentId,
                "note", "Java demo keeps static hash buckets; outcome persistence is reserved for Thompson Sampling expansion."
        );
    }

    private AgentConcurrencyGuard.GuardLease acquireOrReject(String requestType) {
        AgentConcurrencyGuard.GuardLease lease = concurrencyGuard.tryAcquire(requestType);
        if (!lease.isAcquired()) {
            metricsCollector.recordRejectedRun(requestType);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Agent runtime busy: " + lease.getRejectReason());
        }
        return lease;
    }

    private RecommendationResponse requireCompletedRecommendation(AgentLoopResponse result) {
        if (result == null || result.getResponse() == null || !"completed".equals(result.getStatus())) {
            String reason = result == null ? "empty_agent_result" : result.getStopReason();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Multi-agent recommendation did not complete: " + reason);
        }
        return result.getResponse();
    }
}


