package com.ecommerce.orchestrator;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationResponse;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.ABTestService;
import com.ecommerce.service.RecommendationPipelineExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Supervisor orchestrator: bounded parallel dispatch + aggregation for cross-border recommendation agents.
 */
@Service
public class SupervisorOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(SupervisorOrchestrator.class);

    private final RecommendationPipelineExecutor pipelineExecutor;
    private final ABTestService abTestService;

    public SupervisorOrchestrator(RecommendationPipelineExecutor pipelineExecutor, ABTestService abTestService) {
        this.pipelineExecutor = pipelineExecutor;
        this.abTestService = abTestService;
    }

    public RecommendationResponse recommend(RecommendationRequest request) {
        return recommend(request, event -> {});
    }

    public RecommendationResponse recommend(RecommendationRequest incomingRequest, Consumer<AgentRunEvent> eventSink) {
        RecommendationRequest request = incomingRequest == null ? RecommendationRequest.builder().userId("anonymous").build() : incomingRequest;
        String requestId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        Map<String, AgentResult> agentResults = new HashMap<>();
        AtomicInteger sequence = new AtomicInteger(0);

        log.info("[Supervisor] start request={} user={} country={} currency={}", requestId, request.getUserId(), request.countryOrDefault(), request.currencyOrDefault());
        emit(eventSink, requestId, sequence, "run_started", "run.started", "running",
                "Received cross-border recommendation request",
                mergeContext(request, Map.of("user_id", pipelineExecutor.safeUserId(request), "scene", safeScene(request), "num_items", request.getNumItems())),
                start);

        String experimentGroup = abTestService.assign(pipelineExecutor.safeUserId(request)).getOrDefault("group", "control").toString();
        emit(eventSink, requestId, sequence, "model_completed", "experiment.assigned", "success",
                "A/B bucket assigned",
                mergeContext(request, Map.of("group", experimentGroup)),
                start);

        emit(eventSink, requestId, sequence, "model_started", "phase.started", "running",
                "Phase 1: user profile parsing and cross-border product recall",
                mergeContext(request, Map.of("phase", "profile_and_cross_border_recall")),
                start);
        emit(eventSink, requestId, sequence, "model_started", "agent.started", "running",
                "UserProfileAgent started",
                mergeContext(request, Map.of("key", RecommendationPipelineExecutor.USER_PROFILE_RESULT)),
                start);
        emit(eventSink, requestId, sequence, "model_started", "agent.started", "running",
                "ProductRecAgent cross-border recall started",
                mergeContext(request, Map.of("key", RecommendationPipelineExecutor.SEARCH_PRODUCTS)),
                start);

        CompletableFuture<AgentResult> profileFuture = pipelineExecutor.userProfileAsync(request);
        CompletableFuture<AgentResult> recFuture = pipelineExecutor.productRecallAsync(request);

        AgentResult profileResult = profileFuture.join();
        AgentResult recResult = recFuture.join();
        agentResults.put(RecommendationPipelineExecutor.USER_PROFILE_RESULT, profileResult);
        agentResults.put(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT, recResult);
        emitAgentCompleted(eventSink, requestId, sequence, request, RecommendationPipelineExecutor.USER_PROFILE_RESULT, profileResult, start);
        emitAgentCompleted(eventSink, requestId, sequence, request, RecommendationPipelineExecutor.SEARCH_PRODUCTS, recResult, start);

        UserProfile profile = pipelineExecutor.extractProfile(profileResult);
        List<Product> rawProducts = pipelineExecutor.extractProducts(recResult, List.of());
        emit(eventSink, requestId, sequence, "model_completed", "phase.completed", "success",
                "Phase 1 completed, recalled " + rawProducts.size() + " cross-border candidates",
                mergeContext(request, Map.of("phase", "profile_and_cross_border_recall", "candidate_count", rawProducts.size())),
                start);

        emit(eventSink, requestId, sequence, "model_started", "phase.started", "running",
                "Phase 2: LLM rerank and inventory/fulfillment check",
                mergeContext(request, Map.of("phase", "rerank_and_fulfillment")),
                start);
        emit(eventSink, requestId, sequence, "model_started", "agent.started", "running",
                "ProductRecAgent LLM rerank started",
                mergeContext(request, Map.of("key", RecommendationPipelineExecutor.RERANK_PRODUCTS)),
                start);
        emit(eventSink, requestId, sequence, "model_started", "agent.started", "running",
                "InventoryAgent fulfillment check started",
                mergeContext(request, Map.of("key", RecommendationPipelineExecutor.CHECK_INVENTORY)),
                start);

        CompletableFuture<AgentResult> rerankFuture = pipelineExecutor.rerankAsync(request, profile, rawProducts);
        CompletableFuture<AgentResult> inventoryFuture = pipelineExecutor.inventoryAsync(request, rawProducts);

        AgentResult rerankResult = rerankFuture.join();
        AgentResult inventoryResult = inventoryFuture.join();
        agentResults.put(RecommendationPipelineExecutor.RERANK_RESULT, rerankResult);
        agentResults.put(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT, inventoryResult);
        emitAgentCompleted(eventSink, requestId, sequence, request, RecommendationPipelineExecutor.RERANK_PRODUCTS, rerankResult, start);
        emitAgentCompleted(eventSink, requestId, sequence, request, RecommendationPipelineExecutor.CHECK_INVENTORY, inventoryResult, start);

        List<Product> rankedProducts = pipelineExecutor.extractProducts(rerankResult, rawProducts);
        Set<String> availableIds = pipelineExecutor.extractAvailableIds(inventoryResult);
        List<Product> finalProducts = pipelineExecutor.filterAvailableProducts(rankedProducts, availableIds, request.getNumItems());
        emit(eventSink, requestId, sequence, "model_completed", "phase.completed", "success",
                "Phase 2 completed, fulfillment filter kept " + finalProducts.size() + " products",
                mergeContext(request, Map.of("phase", "rerank_and_fulfillment", "final_product_count", finalProducts.size())),
                start);

        emit(eventSink, requestId, sequence, "model_started", "phase.started", "running",
                "Phase 3: localized marketing copy generation",
                mergeContext(request, Map.of("phase", "localized_copy")),
                start);
        emit(eventSink, requestId, sequence, "model_started", "agent.started", "running",
                "MarketingCopyAgent localized copy started",
                mergeContext(request, Map.of("key", RecommendationPipelineExecutor.GENERATE_COPY)),
                start);

        AgentResult copyResult = pipelineExecutor.marketingCopyAsync(request, profile, finalProducts).join();
        agentResults.put(RecommendationPipelineExecutor.LOCALIZED_MARKETING_COPY_RESULT, copyResult);
        emitAgentCompleted(eventSink, requestId, sequence, request, RecommendationPipelineExecutor.GENERATE_COPY, copyResult, start);

        List<Map<String, String>> copies = pipelineExecutor.extractCopies(copyResult);

        double totalLatency = (System.nanoTime() - start) / 1_000_000.0;
        log.info("[Supervisor] complete request={} latency={}ms products={}", requestId, String.format("%.1f", totalLatency), finalProducts.size());

        RecommendationResponse response = RecommendationResponse.builder()
                .requestId(requestId)
                .userId(pipelineExecutor.safeUserId(request))
                .platform(request.platformOrDefault())
                .region(request.regionOrDefault())
                .country(request.countryOrDefault())
                .locale(request.localeOrDefault())
                .currency(request.currencyOrDefault())
                .products(finalProducts)
                .marketingCopies(copies)
                .experimentGroup(experimentGroup)
                .agentResults(agentResults)
                .totalLatencyMs(totalLatency)
                .build();

        emit(eventSink, requestId, sequence, "run_completed", "run.completed", "success",
                "Final cross-border recommendation generated",
                mergeContext(request, Map.of("product_count", finalProducts.size(), "copy_count", copies.size(), "total_latency_ms", totalLatency)),
                start);
        return response;
    }

    private void emitAgentCompleted(Consumer<AgentRunEvent> eventSink, String requestId, AtomicInteger sequence,
                                    RecommendationRequest request, String key, AgentResult result, long start) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("key", key);
        data.put("success", result.isSuccess());
        data.put("latency_ms", result.getLatencyMs());
        data.put("confidence", result.getConfidence());
        if (result.getError() != null) {
            data.put("error", result.getError());
        }
        emit(eventSink, requestId, sequence, "model_completed", "agent.completed",
                result.isSuccess() ? "success" : "failed",
                key + " completed in " + String.format("%.1f", result.getLatencyMs()) + " ms",
                mergeContext(request, data),
                start);
    }

    private void emit(Consumer<AgentRunEvent> eventSink, String requestId, AtomicInteger sequence,
                      String type, String name, String status, String summary,
                      Map<String, Object> data, long start) {
        if (eventSink == null) {
            return;
        }
        eventSink.accept(AgentRunEvent.builder()
                .requestId(requestId)
                .sequence(sequence.incrementAndGet())
                .type(type)
                .name(name)
                .status(status)
                .summary(summary)
                .data(data)
                .elapsedMs((System.nanoTime() - start) / 1_000_000.0)
                .build());
    }

    private Map<String, Object> mergeContext(RecommendationRequest request, Map<String, Object> data) {
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.put("platform", request.platformOrDefault());
        merged.put("region", request.regionOrDefault());
        merged.put("country", request.countryOrDefault());
        merged.put("locale", request.localeOrDefault());
        merged.put("currency", request.currencyOrDefault());
        merged.putAll(data);
        return merged;
    }

    private String safeScene(RecommendationRequest request) {
        return request.getScene() == null || request.getScene().isBlank() ? "homepage" : request.getScene();
    }
}
