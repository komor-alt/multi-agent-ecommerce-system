package com.ecommerce.service;

import com.ecommerce.model.AgentActionDecision;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.EvidenceRecord;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationResponse;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.model.ToolObservation;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class AutonomousAgentLoopService {

    private static final List<String> DEFAULT_WHITELIST = List.of(
            RecommendationPipelineExecutor.GET_USER_PROFILE,
            RecommendationPipelineExecutor.SEARCH_PRODUCTS,
            RecommendationPipelineExecutor.RERANK_PRODUCTS,
            RecommendationPipelineExecutor.CHECK_INVENTORY,
            RecommendationPipelineExecutor.FILTER_PRODUCTS,
            RecommendationPipelineExecutor.GENERATE_COPY,
            RecommendationPipelineExecutor.FINAL_ACTION
    );

    private final RecommendationPipelineExecutor pipelineExecutor;
    private final ABTestService abTestService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AutonomousAgentLoopService(
            RecommendationPipelineExecutor pipelineExecutor,
            ABTestService abTestService,
            ChatClient.Builder chatClientBuilder) {
        this.pipelineExecutor = pipelineExecutor;
        this.abTestService = abTestService;
        this.chatClient = chatClientBuilder.build();
    }

    public AgentLoopResponse run(ToolLoopRequest loopRequest) {
        RecommendationRequest request = loopRequest == null || loopRequest.getRequest() == null
                ? RecommendationRequest.builder().userId("anonymous").build()
                : loopRequest.getRequest();
        ToolLoopConfig config = normalizeConfig(loopRequest == null ? null : loopRequest.getConfig());
        RecommendationPipelineState context = new RecommendationPipelineState(UUID.randomUUID().toString(), request);
        long start = System.nanoTime();
        List<String> thoughts = new ArrayList<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<ToolObservation> observations = new ArrayList<>();
        Map<String, EvidenceRecord> evidences = new LinkedHashMap<>();
        Set<String> fingerprints = new HashSet<>();

        String status = "running";
        String stopReason = "completed";

        for (int step = 1; step <= config.getMaxSteps(); step++) {
            AgentActionDecision decision = plan(context, observations, evidences, config);
            thoughts.add(decision.getThought() == null ? "" : decision.getThought());
            String action = decision.getAction();

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

            ToolExecution execution = executeTool(step, action, trustedArguments, context);
            toolCalls.add(execution.record());
            if (execution.observation() != null) {
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
        return AgentLoopResponse.builder()
                .runId(context.getRunId())
                .status(status)
                .stopReason(stopReason)
                .response(response)
                .thoughts(thoughts)
                .toolCalls(toolCalls)
                .observations(observations)
                .evidences(new ArrayList<>(evidences.values()))
                .totalLatencyMs((System.nanoTime() - start) / 1_000_000.0)
                .build();
    }

    private ToolLoopConfig normalizeConfig(ToolLoopConfig config) {
        ToolLoopConfig value = config == null ? ToolLoopConfig.builder().build() : config;
        if (value.getMaxSteps() <= 0) {
            value.setMaxSteps(8);
        }
        if (value.getToolWhitelist() == null || value.getToolWhitelist().isEmpty()) {
            value.setToolWhitelist(DEFAULT_WHITELIST);
        }
        return value;
    }

    private AgentActionDecision plan(
            RecommendationPipelineState context,
            List<ToolObservation> observations,
            Map<String, EvidenceRecord> evidences,
            ToolLoopConfig config) {
        try {
            String response = chatClient.prompt()
                    .system(plannerSystemPrompt(config))
                    .user(plannerUserPrompt(context, observations, evidences))
                    .call()
                    .content();
            return parseDecision(response);
        } catch (Exception ignored) {
            return fallbackDecision(context);
        }
    }

    private String plannerSystemPrompt(ToolLoopConfig config) {
        return """
                You are a bounded cross-border ecommerce recommendation agent planner.
                Choose exactly one next action from the tool whitelist.
                Return JSON only:
                {"thought":"brief reason","action":"tool_name","arguments":{},"finalAnswer":"","evidenceIds":[]}

                Rules:
                - Prefer cross-border tools: search_cross_border_products, check_fulfillment_inventory, generate_localized_copy.
                - Never call tools outside the whitelist.
                - Use final_answer only after profile, cross-border products, fulfillment inventory, and localized copies exist.
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
        state.put("hasProfile", context.getProfile() != null);
        state.put("rawProductCount", context.getRawProducts() == null ? 0 : context.getRawProducts().size());
        state.put("rankedProductCount", context.getRankedProducts() == null ? 0 : context.getRankedProducts().size());
        state.put("hasInventory", context.getAvailableIds() != null);
        state.put("finalProductCount", context.getFinalProducts() == null ? 0 : context.getFinalProducts().size());
        state.put("copyCount", context.getCopies() == null ? 0 : context.getCopies().size());
        state.put("observations", observations);
        state.put("knownEvidenceIds", evidences.keySet());
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
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.USER_PROFILE_RESULT)) {
            return decision("Need a user profile before ranking cross-border products.", RecommendationPipelineExecutor.GET_USER_PROFILE);
        }
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT)) {
            return decision("Need country/currency eligible candidate products.", RecommendationPipelineExecutor.SEARCH_PRODUCTS);
        }
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.RERANK_RESULT)) {
            return decision("Need to rerank products with the generated profile and cross-border constraints.", RecommendationPipelineExecutor.RERANK_PRODUCTS);
        }
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT)) {
            return decision("Need stock and fulfillment observations before final selection.", RecommendationPipelineExecutor.CHECK_INVENTORY);
        }
        if (context.getFinalProducts() == null) {
            return decision("Need to filter ranked products by fulfillment availability.", RecommendationPipelineExecutor.FILTER_PRODUCTS);
        }
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.LOCALIZED_MARKETING_COPY_RESULT)) {
            return decision("Need localized copy for the selected products.", RecommendationPipelineExecutor.GENERATE_COPY);
        }
        return AgentActionDecision.builder()
                .thought("All required cross-border observations are ready; produce final answer with evidence IDs.")
                .action(RecommendationPipelineExecutor.FINAL_ACTION)
                .finalAnswer("Final cross-border recommendation is ready.")
                .evidenceIds(context.evidenceIds())
                .build();
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
        if (!context.readyForFinalAnswer()) {
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

    private record ToolExecution(
            ToolCallRecord record,
            ToolObservation observation,
            List<EvidenceRecord> evidences
    ) {
    }
}
