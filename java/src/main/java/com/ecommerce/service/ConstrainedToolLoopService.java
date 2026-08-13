package com.ecommerce.service;

import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationResponse;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.model.ToolLoopResponse;
import com.ecommerce.model.ToolObservation;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class ConstrainedToolLoopService {

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

    public ConstrainedToolLoopService(RecommendationPipelineExecutor pipelineExecutor, ABTestService abTestService) {
        this.pipelineExecutor = pipelineExecutor;
        this.abTestService = abTestService;
    }

    public ToolLoopResponse run(ToolLoopRequest loopRequest) {
        RecommendationRequest request = loopRequest == null || loopRequest.getRequest() == null
                ? RecommendationRequest.builder().userId("anonymous").build()
                : loopRequest.getRequest();
        ToolLoopConfig config = normalizeConfig(loopRequest == null ? null : loopRequest.getConfig());
        String runId = UUID.randomUUID().toString();
        long start = System.nanoTime();
        RecommendationPipelineState context = new RecommendationPipelineState(runId, request);
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        Set<String> fingerprints = new HashSet<>();
        String status = "running";
        String stopReason = "completed";

        for (int step = 1; step <= config.getMaxSteps(); step++) {
            String toolName = selectNextTool(context);
            if (toolName == null) {
                status = "completed";
                stopReason = "fixed_stop_condition";
                break;
            }
            if (!config.getToolWhitelist().contains(toolName)) {
                toolCalls.add(blockedCall(step, toolName, "tool is not in whitelist"));
                status = "blocked";
                stopReason = "tool_not_whitelisted";
                break;
            }
            Map<String, Object> arguments = pipelineExecutor.trustedArguments(toolName, context);
            String fingerprint = toolName + ":" + arguments;
            if (!fingerprints.add(fingerprint)) {
                toolCalls.add(blockedCall(step, toolName, "duplicate tool call detected"));
                status = "blocked";
                stopReason = "duplicate_tool_call";
                break;
            }

            ToolCallRecord record = executeTool(step, toolName, arguments, context);
            toolCalls.add(record);
            if ("failed".equals(record.getStatus())) {
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
        return ToolLoopResponse.builder()
                .runId(runId)
                .status(status)
                .stopReason(stopReason)
                .response(response)
                .toolCalls(toolCalls)
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

    private String selectNextTool(RecommendationPipelineState context) {
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.USER_PROFILE_RESULT)) {
            return RecommendationPipelineExecutor.GET_USER_PROFILE;
        }
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.CROSS_BORDER_RECALL_RESULT)) {
            return RecommendationPipelineExecutor.SEARCH_PRODUCTS;
        }
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.RERANK_RESULT)) {
            return RecommendationPipelineExecutor.RERANK_PRODUCTS;
        }
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.FULFILLMENT_INVENTORY_RESULT)) {
            return RecommendationPipelineExecutor.CHECK_INVENTORY;
        }
        if (context.getFinalProducts() == null) {
            return RecommendationPipelineExecutor.FILTER_PRODUCTS;
        }
        if (!context.getAgentResults().containsKey(RecommendationPipelineExecutor.LOCALIZED_MARKETING_COPY_RESULT)) {
            return RecommendationPipelineExecutor.GENERATE_COPY;
        }
        return null;
    }

    private ToolCallRecord executeTool(int sequence, String toolName, Map<String, Object> arguments, RecommendationPipelineState context) {
        long start = System.nanoTime();
        try {
            ToolObservation observation = pipelineExecutor.executeTool(toolName, context);
            return ToolCallRecord.builder()
                    .sequence(sequence)
                    .toolName(toolName)
                    .arguments(arguments)
                    .status("success")
                    .resultSummary(observation.getSummary())
                    .latencyMs((System.nanoTime() - start) / 1_000_000.0)
                    .build();
        } catch (Exception e) {
            return ToolCallRecord.builder()
                    .sequence(sequence)
                    .toolName(toolName)
                    .arguments(arguments)
                    .status("failed")
                    .errorMessage(e.getMessage())
                    .latencyMs((System.nanoTime() - start) / 1_000_000.0)
                    .build();
        }
    }

    private ToolCallRecord blockedCall(int sequence, String toolName, String reason) {
        return ToolCallRecord.builder()
                .sequence(sequence)
                .toolName(toolName)
                .arguments(Map.of())
                .status("blocked")
                .errorMessage(reason)
                .resultSummary(reason)
                .latencyMs(0.0)
                .build();
    }

    private RecommendationResponse buildResponse(RecommendationPipelineState context, long start) {
        String experimentGroup = abTestService.assign(pipelineExecutor.safeUserId(context.getRequest()))
                .getOrDefault("group", "control")
                .toString();
        return pipelineExecutor.buildResponse(context, experimentGroup, start);
    }
}
