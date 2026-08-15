package com.ecommerce.service;

import com.ecommerce.model.AgentActionDecision;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.EvidenceRecord;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationPlan;
import com.ecommerce.model.MarketContext;
import com.ecommerce.model.FulfillmentContext;
import com.ecommerce.model.PlanMetrics;
import com.ecommerce.model.Product;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.RecommendationResponse;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.model.ToolObservation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

@Service
public class AutonomousAgentLoopService {

    private static final List<String> DEFAULT_WHITELIST = ScenePathEnforcer.DEFAULT_WHITELIST;


    private final RecommendationPipelineExecutor pipelineExecutor;
    private final ABTestService abTestService;
    private final ChatClient chatClient;
    private final ScenePathEnforcer scenePathEnforcer;
    private final RecommendationModeResolver modeResolver;
    private final ObjectMapper objectMapper = new ObjectMapper();
    @Value("$" + "{agent.recommend.max-llm-calls:0}")
    private int configuredMaxLlmCalls = 0;

    public AutonomousAgentLoopService(
            RecommendationPipelineExecutor pipelineExecutor,
            ABTestService abTestService,
            ChatClient.Builder chatClientBuilder) {
        this(pipelineExecutor, abTestService, chatClientBuilder, new ScenePathEnforcer(),
                new RecommendationModeResolver("RULES", ""));
    }

    @Autowired
    public AutonomousAgentLoopService(
            RecommendationPipelineExecutor pipelineExecutor,
            ABTestService abTestService,
            ChatClient.Builder chatClientBuilder,
            ScenePathEnforcer scenePathEnforcer,
            RecommendationModeResolver modeResolver) {
        this.pipelineExecutor = pipelineExecutor;
        this.abTestService = abTestService;
        this.chatClient = chatClientBuilder.build();
        this.scenePathEnforcer = scenePathEnforcer;
        this.modeResolver = modeResolver;
    }

    public AgentLoopResponse run(ToolLoopRequest loopRequest) {
        return run(loopRequest, event -> {});
    }

    public AgentLoopResponse run(ToolLoopRequest loopRequest, Consumer<AgentRunEvent> eventSink) {
        RecommendationRequest request = loopRequest == null || loopRequest.getRequest() == null
                ? RecommendationRequest.builder().userId("anonymous").build()
                : loopRequest.getRequest();
        ToolLoopConfig config = normalizeConfig(loopRequest == null ? null : loopRequest.getConfig());
        String callerRunId = loopRequest == null ? null : loopRequest.getRunId();
        RecommendationPipelineState context = new RecommendationPipelineState(
                callerRunId == null || callerRunId.isBlank() ? UUID.randomUUID().toString() : callerRunId, request);
        context.setLlmBudget(new LlmCallBudget(configuredMaxLlmCalls));
        long start = System.nanoTime();
        List<String> thoughts = new ArrayList<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<ToolObservation> observations = new ArrayList<>();
        Map<String, EvidenceRecord> evidences = new LinkedHashMap<>();
        Set<String> fingerprints = new HashSet<>();
        AtomicInteger eventSequence = new AtomicInteger();
        emit(eventSink, context, eventSequence, "run_started", "run.started", "running",
                "Recommendation Agent Loop started", Map.of("scene", scenePathEnforcer.normalizeScene(request.getScene()), "llmMetrics", context.getLlmBudget().snapshot()), start);

        String status = "running";
        String stopReason = "completed";

        for (int step = 1; step <= config.getMaxSteps(); step++) {
            AgentActionDecision decision = plan(context, observations, evidences, config, step);
            thoughts.add(decision.getThought() == null ? "" : decision.getThought());
            String action = scenePathEnforcer.canonicalTool(decision.getAction());
            emit(eventSink, context, eventSequence, "model_completed", "planner.decision", "success",
                    decision.getThought(), Map.of("step", step, "action", action), start);

            if (!config.getToolWhitelist().contains(action)) {
                toolCalls.add(blockedCall(step, action, "tool is not in whitelist"));
                status = "blocked";
                stopReason = "tool_not_whitelisted";
                break;
            }

            if (RecommendationPipelineExecutor.FINAL_ACTION.equals(action)) {
                ToolCallRecord finalRecord = handleFinalAnswer(step, decision, context, evidences);
                toolCalls.add(finalRecord);
                if ("success".equals(finalRecord.getStatus())) {
                    status = "completed";
                    stopReason = "final_answer";
                } else {
                    status = "blocked";
                    stopReason = finalRecord.getErrorMessage();
                }
                break;
            }

            Map<String, Object> trustedArguments = pipelineExecutor.trustedArguments(action, context);
            String fingerprint = action + ":" + trustedArguments;
            if (fingerprints.contains(fingerprint)) {
                toolCalls.add(blockedCall(step, action, "duplicate tool call detected"));
                status = "blocked";
                stopReason = "duplicate_tool_call";
                break;
            }
            fingerprints.add(fingerprint);

            emit(eventSink, context, eventSequence, "tool_started", "tool.started", "running",
                    "Executing " + action, Map.of("step", step, "tool", action, "arguments", trustedArguments), start);
            ToolExecution execution = executeTool(step, action, trustedArguments, context);
            toolCalls.add(execution.record());
            emit(eventSink, context, eventSequence,
                    "failed".equals(execution.record().getStatus()) ? "error" : "tool_completed",
                    "failed".equals(execution.record().getStatus()) ? "tool.failed" : "tool.completed",
                    execution.record().getStatus(), execution.record().getResultSummary() == null ? action : execution.record().getResultSummary(),
                    Map.of("step", step, "tool", action, "latencyMs", execution.record().getLatencyMs()), start);
            if (execution.observation() != null) {
                emit(eventSink, context, eventSequence, "retrieval_completed", "observation", "success",
                        execution.observation().getSummary(), Map.of("step", step, "tool", action,
                                "evidenceIds", execution.observation().getEvidenceIds()), start);
                observations.add(execution.observation());
                for (EvidenceRecord evidence : execution.evidences()) {
                    evidences.put(evidence.getEvidenceId(), evidence);
                }
            }
            if ("failed".equals(execution.record().getStatus())) {
                status = "failed";
                stopReason = "tool_failed";
                break;
            }
        }

        if ("running".equals(status)) {
            status = "blocked";
            stopReason = "max_steps_exceeded";
        }

        RecommendationResponse response = "completed".equals(status) ? buildResponse(context, start) : null;
        RecommendationPlan plan = "completed".equals(status) ? buildPlan(context, toolCalls, start) : null;
        if (plan != null) {
            emit(eventSink, context, eventSequence, "run_completed", "run.completed", "success",
                    "Recommendation plan completed", Map.of(
                            "final_answer", plan,
                            "response", plan,
                            "metrics", Map.of("toolCalls", plan.getMetrics().getToolCalls(),
                                    "steps", plan.getMetrics().getSteps(),
                                    "latencyMs", plan.getMetrics().getLatencyMs()),
                            "dataSources", context.getDataSources()), start);
        }
        return AgentLoopResponse.builder()
                .runId(context.getRunId())
                .status(status)
                .stopReason(stopReason)
                .response(response)
                .plan(plan)
                .thoughts(thoughts)
                .toolCalls(toolCalls)
                .observations(observations)
                .evidences(new ArrayList<>(evidences.values()))
                .totalLatencyMs((System.nanoTime() - start) / 1_000_000.0)
                .llmMetrics(context.getLlmBudget().snapshot())
                .build();
    }

    private ToolLoopConfig normalizeConfig(ToolLoopConfig config) {
        ToolLoopConfig value = config == null ? ToolLoopConfig.builder().build() : config;
        if (value.getMaxSteps() <= 0) {
            value.setMaxSteps(8);
        }
        value.setToolWhitelist(scenePathEnforcer.normalizeWhitelist(value.getToolWhitelist()));
        return value;
    }

    private AgentActionDecision plan(
            RecommendationPipelineState context,
            List<ToolObservation> observations,
            Map<String, EvidenceRecord> evidences,
            ToolLoopConfig config, int step) {
        AgentActionDecision fallback = fallbackDecision(context);
        if (!modeResolver.llmEnabled()) {
            return fallback;
        }
        if (context.getLlmBudget() != null && !context.getLlmBudget().tryAcquire("recommendation_planner", context.getRunId() + ":planner:" + step)) {
            return fallback;
        }
try {
            String response = chatClient.prompt()
                    .system(plannerSystemPrompt(config))
                    .user(plannerUserPrompt(context, observations, evidences))
                    .call()
                    .content();
            AgentActionDecision proposed = parseDecision(response);
            String canonicalAction = scenePathEnforcer.canonicalTool(proposed.getAction());
            if (!scenePathEnforcer.isExpectedStep(context.getRequest().getScene(), canonicalAction, context)) {
                return fallback;
            }
            return AgentActionDecision.builder()
                    .thought(proposed.getThought())
                    .action(canonicalAction)
                    .arguments(proposed.getArguments() == null ? Map.of() : proposed.getArguments())
                    .finalAnswer(proposed.getFinalAnswer())
                    .evidenceIds(proposed.getEvidenceIds() == null ? List.of() : proposed.getEvidenceIds())
                    .build();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private String plannerSystemPrompt(ToolLoopConfig config) {
        return """
                You are a bounded cross-border ecommerce recommendation agent planner.
                Choose exactly one next action from the tool whitelist.
                Return JSON only:
                {"thought":"brief reason","action":"tool_name","arguments":{},"finalAnswer":"","evidenceIds":[]}

                Rules:
                - Use only canonical tools: search_products, check_fulfillment, check_inventory, rerank, and scene-specific copy.
                - Never call tools outside the whitelist.
                - Follow the server-provided scenePath and expectedNextAction. Different scenes require different evidence.
                - Do not invent product IDs, warehouse fields, country support, currency, locale, or evidence IDs.
                - Arguments are advisory; the server will rebuild trusted userId, platform, region, country, locale, and currency.
                Tool whitelist: %s
                """.formatted(config.getToolWhitelist());
    }

    private String plannerUserPrompt(
            RecommendationPipelineState context,
            List<ToolObservation> observations,
            Map<String, EvidenceRecord> evidences) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("request", pipelineExecutor.crossBorderState(context.getRequest()));
        state.put("goal", "Produce a market-safe RecommendationPlan for the requested scene and user.");
        state.put("requiredCapabilities", scenePathEnforcer.requiredCapabilities(context.getRequest().getScene()));
        state.put("optionalCapabilities", scenePathEnforcer.optionalCapabilities(context.getRequest().getScene()));
        state.put("completionConditions", scenePathEnforcer.completionConditions(context.getRequest().getScene()));
        state.put("scenePath", scenePathEnforcer.pathFor(context.getRequest().getScene()));
        state.put("expectedNextAction", scenePathEnforcer.expectedNextStep(context.getRequest().getScene(), context));
        state.put("hasProfile", context.getProfile() != null);
        state.put("rawProductCount", context.getRawProducts() == null ? 0 : context.getRawProducts().size());
        state.put("rankedProductCount", context.getRankedProducts() == null ? 0 : context.getRankedProducts().size());
        state.put("hasInventory", context.getAvailableIds() != null);
        state.put("finalProductCount", context.getFinalProducts() == null ? 0 : context.getFinalProducts().size());
        state.put("copyCount", context.getCopies() == null ? 0 : context.getCopies().size());
        state.put("observations", observations);
        state.put("knownEvidenceIds", evidences.keySet());
        state.put("dataSources", context.getDataSources());
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            return state.toString();
        }
    }

    private AgentActionDecision parseDecision(String raw) throws Exception {
        String cleaned = raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
            cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
        }
        Map<String, Object> data = objectMapper.readValue(cleaned, new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = (Map<String, Object>) data.getOrDefault("arguments", Map.of());
        @SuppressWarnings("unchecked")
        List<String> evidenceIds = (List<String>) data.getOrDefault("evidenceIds", List.of());
        return AgentActionDecision.builder()
                .thought(String.valueOf(data.getOrDefault("thought", "")))
                .action(String.valueOf(data.getOrDefault("action", "")))
                .arguments(arguments)
                .finalAnswer(String.valueOf(data.getOrDefault("finalAnswer", "")))
                .evidenceIds(evidenceIds)
                .build();
    }

    private AgentActionDecision fallbackDecision(RecommendationPipelineState context) {
        String action = scenePathEnforcer.expectedNextStep(context.getRequest().getScene(), context);
        if (RecommendationPipelineExecutor.FINAL_ACTION.equals(action)) {
            return AgentActionDecision.builder()
                    .thought("Required scene evidence is complete; produce the RecommendationPlan.")
                    .action(action)
                    .finalAnswer("Recommendation plan is ready.")
                    .evidenceIds(context.evidenceIds())
                    .build();
        }
        return decision("Scene planner selected the next required evidence tool: " + action, action);
    }
    private AgentActionDecision decision(String thought, String action) {
        return AgentActionDecision.builder()
                .thought(thought)
                .action(action)
                .arguments(Map.of())
                .evidenceIds(List.of())
                .build();
    }

    private ToolExecution executeTool(int sequence, String action, Map<String, Object> arguments, RecommendationPipelineState context) {
        long start = System.nanoTime();
        try {
            ToolObservation observation = pipelineExecutor.executeTool(action, context);
            ToolCallRecord record = ToolCallRecord.builder()
                    .sequence(sequence)
                    .toolName(action)
                    .arguments(arguments)
                    .status("success")
                    .resultSummary(observation.getSummary())
                    .latencyMs((System.nanoTime() - start) / 1_000_000.0)
                    .build();
            return new ToolExecution(record, observation, pipelineExecutor.evidenceRecords(observation));
        } catch (Exception e) {
            ToolCallRecord record = ToolCallRecord.builder()
                    .sequence(sequence)
                    .toolName(action)
                    .arguments(arguments)
                    .status("failed")
                    .errorMessage(e.getMessage())
                    .latencyMs((System.nanoTime() - start) / 1_000_000.0)
                    .build();
            return new ToolExecution(record, null, List.of());
        }
    }

    private ToolCallRecord handleFinalAnswer(
            int sequence,
            AgentActionDecision decision,
            RecommendationPipelineState context,
            Map<String, EvidenceRecord> evidences) {
        if (!context.readyForFinalAnswer() || !scenePathEnforcer.isPathComplete(context.getRequest().getScene(), context)) {
            return blockedCall(sequence, RecommendationPipelineExecutor.FINAL_ACTION, "insufficient_context_for_final_answer");
        }
        List<String> evidenceIds = decision.getEvidenceIds() == null || decision.getEvidenceIds().isEmpty()
                ? context.evidenceIds()
                : decision.getEvidenceIds();
        List<String> missing = evidenceIds.stream()
                .filter(id -> !evidences.containsKey(id) && !context.getKnownEvidenceIds().contains(id))
                .toList();
        if (!missing.isEmpty()) {
            return blockedCall(sequence, RecommendationPipelineExecutor.FINAL_ACTION, "invalid_evidence_ids: " + missing);
        }
        return ToolCallRecord.builder()
                .sequence(sequence)
                .toolName(RecommendationPipelineExecutor.FINAL_ACTION)
                .arguments(Map.of("evidenceIds", evidenceIds, "crossBorder", pipelineExecutor.crossBorderState(context.getRequest())))
                .status("success")
                .resultSummary(decision.getFinalAnswer() == null || decision.getFinalAnswer().isBlank()
                        ? "final answer accepted"
                        : decision.getFinalAnswer())
                .latencyMs(0.0)
                .build();
    }

    private ToolCallRecord blockedCall(int sequence, String toolName, String reason) {
        return ToolCallRecord.builder()
                .sequence(sequence)
                .toolName(toolName)
                .arguments(Map.of())
                .status("blocked")
                .resultSummary(reason)
                .errorMessage(reason)
                .latencyMs(0.0)
                .build();
    }

    private RecommendationResponse buildResponse(RecommendationPipelineState context, long start) {
        String experimentGroup = abTestService.assign(pipelineExecutor.safeUserId(context.getRequest()))
                .getOrDefault("group", "control")
                .toString();
        return pipelineExecutor.buildResponse(context, experimentGroup, start);
    }

    private RecommendationPlan buildPlan(RecommendationPipelineState context, List<ToolCallRecord> calls, long start) {
        RecommendationRequest request = context.getRequest();
        List<Product> products = context.getFinalProducts() == null ? List.of() : context.getFinalProducts();
        List<String> segments = context.getProfile() == null || context.getProfile().getSegments() == null
                || context.getProfile().getSegments().isEmpty()
                ? List.of(scenePathEnforcer.normalizeScene(request.getScene())) : context.getProfile().getSegments();
        int toolCalls = (int) calls.stream()
                .filter(call -> !RecommendationPipelineExecutor.FINAL_ACTION.equals(call.getToolName()))
                .filter(call -> "success".equals(call.getStatus())).count();
        double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
        int maxDeliveryDays = products.stream().mapToInt(Product::getDeliveryDays).max().orElse(0);
        String warehouse = products.stream().map(Product::getWarehouseRegion).filter(java.util.Objects::nonNull)
                .distinct().collect(java.util.stream.Collectors.joining(","));
        Set<String> selectedProductIds = products.stream().map(Product::getProductId).collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> fulfillmentItems = context.getFulfillment().entrySet().stream()
                .filter(entry -> selectedProductIds.contains(entry.getKey()))
                .map(this::fulfillmentItem)
                .toList();
        List<Map<String, Object>> fulfillmentRestrictions = context.getFulfillment().entrySet().stream()
                .map(this::fulfillmentItem)
                .filter(item -> Boolean.TRUE.equals(item.get("restricted")))
                .toList();
        List<String> fitReasons = products.stream()
                .map(product -> product.getProductId() + " matches scene=" + scenePathEnforcer.normalizeScene(request.getScene())
                        + " and segment=" + segments.get(0)).toList();
        List<String> marketReasons = products.stream()
                .map(product -> product.getProductId() + " supports " + request.countryOrDefault()
                        + " on " + request.platformOrDefault() + " in " + request.currencyOrDefault()).toList();
        List<String> fulfillmentReasons = products.stream()
                .map(product -> product.getProductId() + " ships from " + product.getWarehouseRegion()
                        + " with deliveryDays=" + product.getDeliveryDays()).toList();
        Map<String, Object> strategy = new LinkedHashMap<>();
        strategy.put("mode", "bounded_autonomous_agent_loop");
        strategy.put("scenePath", scenePathEnforcer.pathFor(request.getScene()));
        strategy.put("requiredCapabilities", scenePathEnforcer.requiredCapabilities(request.getScene()));
        strategy.put("optionalCapabilities", scenePathEnforcer.optionalCapabilities(request.getScene()));
        strategy.put("completionConditions", scenePathEnforcer.completionConditions(request.getScene()));
        strategy.put("dataSources", context.getDataSources());
        strategy.put("reason", strategyReason(request.getScene()));

        return RecommendationPlan.builder()
                .scene(scenePathEnforcer.normalizeScene(request.getScene()))
                .market(MarketContext.builder().platform(request.platformOrDefault()).region(request.regionOrDefault())
                        .country(request.countryOrDefault()).locale(request.localeOrDefault())
                        .currency(request.currencyOrDefault()).supportedCountries(List.of(request.countryOrDefault())).build())
                .userSegment(segments.get(0))
                .products(products)
                .strategy(strategy)
                .fulfillment(FulfillmentContext.builder().warehouseRegion(warehouse).deliveryDays(maxDeliveryDays)
                        .status(products.isEmpty() ? "no_eligible_products" : "market_eligible")
                        .items(fulfillmentItems)
                        .restrictions(fulfillmentRestrictions).build())
                .marketingCopies(context.getCopies() == null ? List.of() : context.getCopies())
                .evidenceIds(context.evidenceIds())
                .fitReasons(fitReasons)
                .marketReasons(marketReasons)
                .fulfillmentReasons(fulfillmentReasons)
                .metrics(PlanMetrics.builder()
                        .toolCalls(toolCalls).steps(calls.size()).latencyMs(latencyMs)
                        .llmCallCount(context.getLlmBudget() == null ? 0 : context.getLlmBudget().getCallCount())
                        .promptTokens(null).completionTokens(null).estimatedCost(null)
                        .usageStatus("unavailable").build())
                .build();
    }

    private Map<String, Object> fulfillmentItem(Map.Entry<String, Object> entry) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("productId", entry.getKey());
        if (entry.getValue() instanceof Map<?, ?> details) {
            details.forEach((key, value) -> item.put(String.valueOf(key), value));
        }
        return item;
    }
    private String strategyReason(String scene) {
        return switch (scenePathEnforcer.normalizeScene(scene)) {
            case ScenePathEnforcer.SCENE_CAMPAIGN -> "Apply campaign and market eligibility before localized campaign copy.";
            case ScenePathEnforcer.SCENE_RETENTION -> "Use user and recent order context for a localized win-back plan.";
            default -> "Keep homepage ranking concise and omit unnecessary copy generation.";
        };
    }

    private void emit(Consumer<AgentRunEvent> sink, RecommendationPipelineState context,
                      AtomicInteger sequence, String type, String name, String status,
                      String summary, Map<String, Object> data, long start) {
        if (sink == null) return;
        sink.accept(AgentRunEvent.builder()
                .requestId(context.getRunId())
                .sequence(sequence.incrementAndGet())
                .type(type).name(name).status(status)
                .summary(summary == null ? name : summary)
                .data(data == null ? Map.of() : data)
                .elapsedMs((System.nanoTime() - start) / 1_000_000.0)
                .build());
    }
    private record ToolExecution(
            ToolCallRecord record,
            ToolObservation observation,
            List<EvidenceRecord> evidences
    ) {
    }
}
