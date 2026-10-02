package com.ecommerce.service;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.EvidenceRecord;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Runtime for four real specialist agents. They share the same implementation
 * kernel, but each instance has its own identity, tool scope, prompt and local
 * Plan-Act-Observe state. The existing *Agent connector classes remain tools.
 */
public class SpecialistAgentTeam {

    private static final int MAX_LOCAL_STEPS = 4;

    private final RecommendationPipelineExecutor pipelineExecutor;
    private final ScenePathEnforcer scenePathEnforcer;
    private final RecommendationModeResolver modeResolver;
    private final SpecialistPlannerClient plannerClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<AgentId, SpecialistDefinition> definitions = new EnumMap<>(AgentId.class);

    public SpecialistAgentTeam(
            RecommendationPipelineExecutor pipelineExecutor,
            ScenePathEnforcer scenePathEnforcer,
            RecommendationModeResolver modeResolver,
            SpecialistPlannerClient plannerClient) {
        this.pipelineExecutor = pipelineExecutor;
        this.scenePathEnforcer = scenePathEnforcer;
        this.modeResolver = modeResolver;
        this.plannerClient = plannerClient;
        definitions.put(AgentId.PROFILE, new SpecialistDefinition(
                "Build trustworthy user and recent-order context."));
        definitions.put(AgentId.PRODUCT, new SpecialistDefinition(
                "Recall and rerank products; revise candidates after a veto."));
        definitions.put(AgentId.INVENTORY, new SpecialistDefinition(
                "Enforce campaign, fulfillment and inventory constraints; veto unsafe products."));
        definitions.put(AgentId.COPY, new SpecialistDefinition(
                "Generate localized copy only for supervisor-approved final products."));
    }

    public SpecialistRun run(
            AgentTask task,
            RecommendationPipelineState blackboard,
            List<String> whitelist,
            Set<String> fingerprints) {
        return run(task, blackboard, whitelist, fingerprints, new SpecialistRunListener() {});
    }

    public SpecialistRun run(
            AgentTask task,
            RecommendationPipelineState blackboard,
            List<String> whitelist,
            Set<String> fingerprints,
            SpecialistRunListener listener) {
        BoundedSpecialistAgent agent = spawn(task.agent());
        if (agent == null) {
            return SpecialistRun.failed(task.agent(), "unknown_specialist", List.of(), List.of(), List.of(), List.of());
        }
        return agent.run(task, blackboard, whitelist, fingerprints,
                listener == null ? new SpecialistRunListener() {} : listener, null);
    }

    /** Execute one action frozen by the server-side parallel dependency planner. */
    public SpecialistRun runPlannedAction(
            AgentTask task,
            String plannedAction,
            RecommendationPipelineState blackboard,
            List<String> whitelist,
            Set<String> fingerprints,
            SpecialistRunListener listener) {
        BoundedSpecialistAgent agent = spawn(task.agent());
        if (agent == null) {
            return SpecialistRun.failed(task.agent(), "unknown_specialist", List.of(), List.of(), List.of(), List.of());
        }
        String action = scenePathEnforcer.canonicalTool(plannedAction);
        return agent.run(task, blackboard, whitelist, fingerprints,
                listener == null ? new SpecialistRunListener() {} : listener, action);
    }

    /** Every delegation receives a fresh agent instance and therefore fresh local loop state. */
    private BoundedSpecialistAgent spawn(AgentId id) {
        SpecialistDefinition definition = definitions.get(id);
        return definition == null ? null : new BoundedSpecialistAgent(id, definition.roleGoal());
    }

    private final class BoundedSpecialistAgent {
        private final AgentId id;
        private final String roleGoal;

        private BoundedSpecialistAgent(AgentId id, String roleGoal) {
            this.id = id;
            this.roleGoal = roleGoal;
        }

        private SpecialistRun run(
                AgentTask task,
                RecommendationPipelineState blackboard,
                List<String> whitelist,
                Set<String> fingerprints,
                SpecialistRunListener listener,
                String plannedAction) {
            List<String> thoughts = new ArrayList<>();
            List<ToolCallRecord> calls = new ArrayList<>();
            List<ToolObservation> observations = new ArrayList<>();
            List<EvidenceRecord> evidences = new ArrayList<>();
            int limit = Math.max(1, Math.min(MAX_LOCAL_STEPS, task.maxToolSteps()));

            for (int localStep = 1; localStep <= limit; localStep++) {
                blackboard.checkActive();
                List<String> candidates = plannedAction == null
                        ? scenePathEnforcer.executableTools(id, blackboard.getRequest().getScene(), blackboard)
                        : localStep == 1 ? List.of(plannedAction) : List.of();
                if (candidates.isEmpty()) {
                    return SpecialistRun.completed(id, "handoff", thoughts, calls, observations, evidences);
                }
                String action = plannedAction == null
                        ? chooseAction(task, blackboard, candidates, localStep)
                        : plannedAction;
                thoughts.add(id + " planned " + action + " from " + candidates);

                if (!candidates.contains(action) || !scenePathEnforcer.isToolAllowedFor(id, action)) {
                    calls.add(blocked(task.startSequence() + calls.size(), action, "specialist_scope_violation"));
                    return SpecialistRun.failed(id, "specialist_scope_violation", thoughts, calls, observations, evidences);
                }
                if (!whitelist.contains(action)) {
                    calls.add(blocked(task.startSequence() + calls.size(), action, "tool_not_whitelisted"));
                    return SpecialistRun.failed(id, "tool_not_whitelisted", thoughts, calls, observations, evidences);
                }

                Map<String, Object> arguments = pipelineExecutor.trustedArguments(action, blackboard);
                String fingerprint = id + ":" + action + ":" + arguments;
                if (!fingerprints.add(fingerprint)) {
                    calls.add(blocked(task.startSequence() + calls.size(), action, "duplicate_tool_call"));
                    return SpecialistRun.failed(id, "duplicate_tool_call", thoughts, calls, observations, evidences);
                }

                long start = System.nanoTime();
                int sequence = task.startSequence() + calls.size();
                listener.onToolStarted(id, sequence, action, arguments);
                try {
                    ToolObservation observation = pipelineExecutor.executeTool(action, blackboard);
                    ToolCallRecord record = ToolCallRecord.builder()
                            .sequence(sequence)
                            .toolName(action).arguments(arguments).status("success")
                            .resultSummary(observation.getSummary())
                            .latencyMs((System.nanoTime() - start) / 1_000_000.0).build();
                    calls.add(record);
                    observations.add(observation);
                    evidences.addAll(pipelineExecutor.evidenceRecords(observation));
                    listener.onToolCompleted(id, record, observation);
                    Object vetoed = observation.getData() == null
                            ? List.of() : observation.getData().getOrDefault("vetoedProductIds", List.of());
                    if (vetoed instanceof List<?> ids && !ids.isEmpty()) {
                        return SpecialistRun.completed(id, "veto", thoughts, calls, observations, evidences);
                    }
                    // Candidate production and fulfillment filtering are dependency barriers:
                    // return control so inventory and rerank can form the next safe batch.
                    if (ScenePathEnforcer.SEARCH_PRODUCTS.equals(action)
                            || ScenePathEnforcer.CHECK_FULFILLMENT.equals(action)) {
                        return SpecialistRun.completed(id, "parallel_barrier", thoughts, calls, observations, evidences);
                    }
                } catch (Exception error) {
                    if (error instanceof RunDeadlineExceededException deadline) throw deadline;
                    boolean stale = error instanceof StaleAgentResultException;
                    ToolCallRecord record = ToolCallRecord.builder()
                            .sequence(sequence)
                            .toolName(action).arguments(arguments).status(stale ? "stale_rejected" : "failed")
                            .errorMessage(error.getMessage())
                            .latencyMs((System.nanoTime() - start) / 1_000_000.0).build();
                    calls.add(record);
                    listener.onToolCompleted(id, record, null);
                    if (stale) {
                        return SpecialistRun.completed(
                                id, "stale_result_rejected", thoughts, calls, observations, evidences);
                    }
                    return SpecialistRun.failed(id, "tool_failed", thoughts, calls, observations, evidences);
                }
            }
            return SpecialistRun.completed(id, "local_step_limit", thoughts, calls, observations, evidences);
        }

        private String chooseAction(
                AgentTask task,
                RecommendationPipelineState blackboard,
                List<String> candidates,
                int localStep) {
            String fallback = candidates.get(0);
            if (!modeResolver.llmEnabled() || plannerClient == null || blackboard.getLlmBudget() == null
                    || !blackboard.getLlmBudget().tryAcquire("specialist_" + id.name().toLowerCase(),
                    blackboard.getRunId() + ":" + id + ":" + task.taskId() + ":" + localStep)) {
                return fallback;
            }
            try {
                Map<String, Object> state = new LinkedHashMap<>();
                state.put("taskId", task.taskId());
                state.put("goal", task.goal());
                state.put("scene", scenePathEnforcer.normalizeScene(blackboard.getRequest().getScene()));
                state.put("candidateActions", candidates);
                if (task.contextSnapshot() == null) {
                    state.put("knownEvidenceIds", blackboard.getKnownEvidenceIds());
                    state.put("vetoedProductIds", blackboard.vetoedProductIds());
                } else {
                    state.put("contextSnapshot", task.contextSnapshot());
                }
                String raw = plannerClient.complete(
                        "You are the " + id + " specialist. " + roleGoal
                                + " Choose one candidate action. Return JSON only: {\"action\":\"...\",\"thought\":\"...\"}.",
                        objectMapper.writeValueAsString(state));
                String cleaned = raw == null ? "" : raw.trim();
                if (cleaned.startsWith("```")) {
                    cleaned = cleaned.substring(cleaned.indexOf('\n') + 1, cleaned.lastIndexOf("```"));
                }
                Map<String, Object> parsed = objectMapper.readValue(cleaned, new TypeReference<>() {});
                String proposed = scenePathEnforcer.canonicalTool(String.valueOf(parsed.getOrDefault("action", "")));
                return candidates.contains(proposed) ? proposed : fallback;
            } catch (Exception ignored) {
                return fallback;
            }
        }
    }

    private ToolCallRecord blocked(int sequence, String tool, String reason) {
        return ToolCallRecord.builder().sequence(sequence).toolName(tool).arguments(Map.of())
                .status("blocked").resultSummary(reason).errorMessage(reason).latencyMs(0.0).build();
    }

    public record AgentTask(
            String taskId,
            AgentId agent,
            String goal,
            int startSequence,
            int maxToolSteps,
            DynamicSubAgentRuntime.ContextSnapshot contextSnapshot) {
        public AgentTask(String taskId, AgentId agent, String goal, int startSequence, int maxToolSteps) {
            this(taskId, agent, goal, startSequence, maxToolSteps, null);
        }
    }

    public record SpecialistRun(
            AgentId agent,
            boolean success,
            String stopReason,
            List<String> thoughts,
            List<ToolCallRecord> toolCalls,
            List<ToolObservation> observations,
            List<EvidenceRecord> evidences) {
        static SpecialistRun completed(AgentId agent, String reason, List<String> thoughts,
                                       List<ToolCallRecord> calls, List<ToolObservation> observations,
                                       List<EvidenceRecord> evidences) {
            return new SpecialistRun(agent, true, reason, List.copyOf(thoughts), List.copyOf(calls),
                    List.copyOf(observations), List.copyOf(evidences));
        }

        static SpecialistRun failed(AgentId agent, String reason, List<String> thoughts,
                                    List<ToolCallRecord> calls, List<ToolObservation> observations,
                                    List<EvidenceRecord> evidences) {
            return new SpecialistRun(agent, false, reason, List.copyOf(thoughts), List.copyOf(calls),
                    List.copyOf(observations), List.copyOf(evidences));
        }
    }

    @FunctionalInterface
    public interface SpecialistPlannerClient {
        String complete(String systemPrompt, String userPrompt);
    }

    public interface SpecialistRunListener {
        default void onToolStarted(AgentId agent, int sequence, String tool, Map<String, Object> arguments) {}
        default void onToolCompleted(AgentId agent, ToolCallRecord call, ToolObservation observation) {}
    }

    private record SpecialistDefinition(String roleGoal) {}
}
