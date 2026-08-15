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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@RestController
@RequestMapping("/api/v1")
public class RecommendationController {

    private final SupervisorOrchestrator orchestrator;
    private final ABTestService abTestService;
    private final AutonomousAgentLoopService autonomousAgentLoopService;
    private final MetricsCollector metricsCollector;
    private final RecommendationEvaluator evaluator;
    private final ConstrainedToolLoopService toolLoopService;
    private final RedisFeatureStoreService featureStoreService;
    private final DemoDataService demoDataService;
    private final AgentConcurrencyGuard concurrencyGuard;
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
        this.sseExecutor = sseExecutor;
    }

    @PostMapping("/recommend")
    public RecommendationResponse recommend(@RequestBody RecommendationRequest request) {
        try (AgentConcurrencyGuard.GuardLease ignored = acquireOrReject("recommend")) {
            RecommendationResponse response = orchestrator.recommend(request);
            metricsCollector.recordRecommendation(response);
            return response;
        }
    }

    @PostMapping(value = "/recommend/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter recommendStream(@RequestBody RecommendationRequest request) {
        AgentConcurrencyGuard.GuardLease lease = acquireOrReject("recommend_stream");
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try (lease) {
                RecommendationResponse response = orchestrator.recommend(request, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .id(event.getEventId())
                                .name(event.getName())
                                .data(event));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                metricsCollector.recordRecommendation(response);
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, sseExecutor);
        return emitter;
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
        AgentConcurrencyGuard.GuardLease lease = acquireOrReject("agent_loop_stream");
        SseEmitter emitter = new SseEmitter(0L);
        CompletableFuture.runAsync(() -> {
            try (lease) {
                AgentLoopResponse response = autonomousAgentLoopService.run(request, event -> {
                    try {
                        emitter.send(SseEmitter.event()
                                .id(event.getEventId())
                                .name(event.getName())
                                .data(event));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
                metricsCollector.recordToolCalls(response.getToolCalls());
                if (response.getResponse() != null) {
                    metricsCollector.recordRecommendation(response.getResponse());
                }
                emitter.complete();
            } catch (Exception error) {
                emitter.completeWithError(error);
            }
        }, sseExecutor);
        return emitter;
    }

    @PostMapping("/evaluations/smoke")
    public Map<String, Object> smokeEvaluation(@RequestBody RecommendationRequest request) {
        try (AgentConcurrencyGuard.GuardLease ignored = acquireOrReject("smoke_evaluation")) {
            RecommendationResponse response = orchestrator.recommend(request);
            metricsCollector.recordRecommendation(response);
            EvaluationReport report = evaluator.evaluate(request, response);
            return Map.of("evaluation", report, "response", response);
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
        return snapshot;
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
}


