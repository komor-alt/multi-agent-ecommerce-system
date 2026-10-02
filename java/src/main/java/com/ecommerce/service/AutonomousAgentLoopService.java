package com.ecommerce.service;

import com.ecommerce.config.RecommendationOrchestrationProperties;
import com.ecommerce.config.RuntimeLimitsProperties;
import com.ecommerce.model.AgentActionDecision;
import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentMessage;
import com.ecommerce.model.AgentMessageType;
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
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.runtime.DynamicSubAgentRuntimeRegistry;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import com.ecommerce.runtime.persistence.RecommendationExecutionLease;
import com.ecommerce.runtime.persistence.RecommendationRecoveryProperties;
import com.ecommerce.runtime.persistence.StaleExecutionLeaseException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Service
public class AutonomousAgentLoopService {
    private static final Logger log = LoggerFactory.getLogger(AutonomousAgentLoopService.class);

    private static final List<String> DEFAULT_WHITELIST = ScenePathEnforcer.DEFAULT_WHITELIST;


    private final RecommendationPipelineExecutor pipelineExecutor;
    private final ABTestService abTestService;
    private final ChatClient chatClient;
    private final ScenePathEnforcer scenePathEnforcer;
    private final RecommendationModeResolver modeResolver;
    private final SupervisorLlmClient supervisorLlmClient;
    private final SpecialistAgentTeam specialistAgentTeam;
    private final SpecialistParallelPlanner parallelPlanner;
    private final Executor orchestrationExecutor;
    private final DynamicSubAgentRuntimeRegistry subAgentRuntimeRegistry;
    private final RecommendationRuntimeStore runtimeStore;
    private final RecommendationRunEventService persistentEventService;
    private RuntimeLimitsProperties runtimeLimits = new RuntimeLimitsProperties();
    private RuntimeTelemetry telemetry;
    private RecommendationRecoveryProperties recoveryProperties;
    private RecommendationLeaseHeartbeatService heartbeatService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final int configuredMaxLlmCalls;

    public AutonomousAgentLoopService(
            RecommendationPipelineExecutor pipelineExecutor,
            ABTestService abTestService,
            ChatClient.Builder chatClientBuilder) {
        this(pipelineExecutor, abTestService, chatClientBuilder, new ScenePathEnforcer(),
                new RecommendationModeResolver("RULES", ""), 0, null,
                new RecommendationOrchestrationProperties(), Runnable::run, null);
    }

    @Autowired
    public AutonomousAgentLoopService(
            RecommendationPipelineExecutor pipelineExecutor,
            ABTestService abTestService,
            ChatClient.Builder chatClientBuilder,
            ScenePathEnforcer scenePathEnforcer,
            RecommendationModeResolver modeResolver,
            @Value("${agent.supervisor.max-llm-calls:0}") int supervisorMaxLlmCalls,
            RecommendationOrchestrationProperties orchestrationProperties,
            @Qualifier("supervisorOrchestrationExecutor") Executor orchestrationExecutor,
            DynamicSubAgentRuntimeRegistry subAgentRuntimeRegistry,
            RecommendationRuntimeStore runtimeStore,
            RecommendationRunEventService persistentEventService,
            RuntimeLimitsProperties runtimeLimits,
            RuntimeTelemetry telemetry,
            RecommendationRecoveryProperties recoveryProperties,
            RecommendationLeaseHeartbeatService heartbeatService) {
        this(pipelineExecutor, abTestService, chatClientBuilder, scenePathEnforcer, modeResolver,
                supervisorMaxLlmCalls, null, orchestrationProperties, orchestrationExecutor,
                subAgentRuntimeRegistry, runtimeStore, persistentEventService);
        this.runtimeLimits = runtimeLimits;
        this.telemetry = telemetry;
        this.recoveryProperties = recoveryProperties;
        this.heartbeatService = heartbeatService;
    }

    public AutonomousAgentLoopService(
            RecommendationPipelineExecutor pipelineExecutor,
            ABTestService abTestService,
            ChatClient.Builder chatClientBuilder,
            ScenePathEnforcer scenePathEnforcer,
            RecommendationModeResolver modeResolver,
            int supervisorMaxLlmCalls,
            SupervisorLlmClient supervisorLlmClient) {
        this(pipelineExecutor, abTestService, chatClientBuilder, scenePathEnforcer, modeResolver,
                supervisorMaxLlmCalls, supervisorLlmClient,
                new RecommendationOrchestrationProperties(), Runnable::run, null);
    }

    public AutonomousAgentLoopService(
            RecommendationPipelineExecutor pipelineExecutor,
            ABTestService abTestService,
            ChatClient.Builder chatClientBuilder,
            ScenePathEnforcer scenePathEnforcer,
            RecommendationModeResolver modeResolver,
            int supervisorMaxLlmCalls,
            SupervisorLlmClient supervisorLlmClient,
            RecommendationOrchestrationProperties orchestrationProperties,
            Executor orchestrationExecutor) {
        this(pipelineExecutor, abTestService, chatClientBuilder, scenePathEnforcer, modeResolver,
                supervisorMaxLlmCalls, supervisorLlmClient, orchestrationProperties,
                orchestrationExecutor, null);
    }

    public AutonomousAgentLoopService(
            RecommendationPipelineExecutor pipelineExecutor,
            ABTestService abTestService,
            ChatClient.Builder chatClientBuilder,
            ScenePathEnforcer scenePathEnforcer,
            RecommendationModeResolver modeResolver,
            int supervisorMaxLlmCalls,
            SupervisorLlmClient supervisorLlmClient,
            RecommendationOrchestrationProperties orchestrationProperties,
            Executor orchestrationExecutor,
            DynamicSubAgentRuntimeRegistry subAgentRuntimeRegistry) {
        this(pipelineExecutor, abTestService, chatClientBuilder, scenePathEnforcer, modeResolver,
                supervisorMaxLlmCalls, supervisorLlmClient, orchestrationProperties,
                orchestrationExecutor, subAgentRuntimeRegistry, null, null);
    }

    public AutonomousAgentLoopService(
            RecommendationPipelineExecutor pipelineExecutor,
            ABTestService abTestService,
            ChatClient.Builder chatClientBuilder,
            ScenePathEnforcer scenePathEnforcer,
            RecommendationModeResolver modeResolver,
            int supervisorMaxLlmCalls,
            SupervisorLlmClient supervisorLlmClient,
            RecommendationOrchestrationProperties orchestrationProperties,
            Executor orchestrationExecutor,
            DynamicSubAgentRuntimeRegistry subAgentRuntimeRegistry,
            RecommendationRuntimeStore runtimeStore,
            RecommendationRunEventService persistentEventService) {
        this.pipelineExecutor = pipelineExecutor;
        this.abTestService = abTestService;
        this.chatClient = chatClientBuilder.build();
        this.scenePathEnforcer = scenePathEnforcer;
        this.modeResolver = modeResolver;
        this.configuredMaxLlmCalls = Math.max(0, supervisorMaxLlmCalls);
        RecommendationOrchestrationProperties safeProperties = orchestrationProperties == null
                ? new RecommendationOrchestrationProperties() : orchestrationProperties;
        this.orchestrationExecutor = orchestrationExecutor == null ? Runnable::run : orchestrationExecutor;
        this.subAgentRuntimeRegistry = subAgentRuntimeRegistry;
        this.runtimeStore = runtimeStore;
        this.persistentEventService = persistentEventService;
        this.parallelPlanner = new SpecialistParallelPlanner(scenePathEnforcer, safeProperties);
        this.supervisorLlmClient = supervisorLlmClient != null
                ? supervisorLlmClient
                : (systemPrompt, userPrompt) -> this.chatClient.prompt()
                        .system(systemPrompt)
                        .user(userPrompt)
                        .call()
                        .content();
        this.specialistAgentTeam = new SpecialistAgentTeam(
                pipelineExecutor, scenePathEnforcer, modeResolver, this.supervisorLlmClient::complete);
    }

    public AgentLoopResponse run(ToolLoopRequest loopRequest) {
        return run(loopRequest, event -> {});
    }

    public AgentLoopResponse run(ToolLoopRequest loopRequest, Consumer<AgentRunEvent> eventSink) {
        if (managedExecutionEnabled()) {
            RecommendationExecutionLease lease = runtimeStore.startAndClaimRecoverableRun(
                    normalizeRequest(loopRequest), recoveryProperties.getWorkerId());
            return runClaimed(lease, eventSink);
        }
        return runInternal(loopRequest, eventSink, false, null, null);
    }

    /** Create the durable run before handing the connection to the event reader. */
    public ToolLoopRequest prepareRun(ToolLoopRequest source) {
        ToolLoopRequest prepared = normalizeRequest(source);
        if (managedExecutionEnabled()) runtimeStore.startRecoverableRun(prepared);
        else runtimeStore.startRun(prepared.getRunId(), prepared.getRequest());
        return prepared;
    }

    private ToolLoopRequest normalizeRequest(ToolLoopRequest source) {
        RecommendationRequest request = source == null || source.getRequest() == null
                ? RecommendationRequest.builder().userId("anonymous").build() : source.getRequest();
        validateRequest(request);
        String runId = source == null || source.getRunId() == null || source.getRunId().isBlank()
                ? UUID.randomUUID().toString() : source.getRunId();
        validateRunId(runId);
        return ToolLoopRequest.builder().runId(runId).request(request)
                .config(normalizeConfig(source == null ? null : source.getConfig())).build();
    }

    public AgentLoopResponse runPrepared(ToolLoopRequest request) {
        if (managedExecutionEnabled()) {
            // A scanner may win the claim after prepareRun; its database event stream is authoritative.
            return runtimeStore.claimRun(request.getRunId(), recoveryProperties.getWorkerId())
                    .map(this::runClaimed).orElse(null);
        }
        return runInternal(request, ignored -> {}, true, null, null);
    }

    public AgentLoopResponse runClaimed(RecommendationExecutionLease lease) {
        return runClaimed(lease, ignored -> {});
    }

    private AgentLoopResponse runClaimed(RecommendationExecutionLease lease, Consumer<AgentRunEvent> sink) {
        try (RecommendationLeaseHeartbeatService.Handle heartbeat = heartbeatService.begin(lease)) {
            ToolLoopRequest request = normalizeRequest(lease.request());
            if (!lease.runId().equals(request.getRunId())
                    || !DEFAULT_WHITELIST.containsAll(request.getConfig().getToolWhitelist())) {
                throw new IllegalArgumentException("Invalid recoverable recommendation request");
            }
            heartbeat.assertActive();
            return runInternal(request, sink, true, lease, heartbeat);
        } catch (StaleExecutionLeaseException stale) {
            throw stale;
        } catch (RuntimeException error) {
            try {
                runtimeStore.failRun(lease.runId(), "execution_failed", lease);
            } catch (RuntimeException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private boolean managedExecutionEnabled() {
        return runtimeStore != null && recoveryProperties != null && recoveryProperties.isEnabled();
    }

    public void failPreparedRun(String runId, String reason) {
        if (runtimeStore != null) {
            if (managedExecutionEnabled()) runtimeStore.failUnclaimedRun(runId, reason);
            else runtimeStore.failRun(runId, reason);
        }
        completePersistentStream(runId);
    }

    private AgentLoopResponse runInternal(ToolLoopRequest loopRequest, Consumer<AgentRunEvent> eventSink,
                                          boolean alreadyPersisted, RecommendationExecutionLease lease,
                                          RecommendationLeaseHeartbeatService.Handle heartbeat) {
        RecommendationRequest request = loopRequest == null || loopRequest.getRequest() == null
                ? RecommendationRequest.builder().userId("anonymous").build()
                : loopRequest.getRequest();
        validateRequest(request);
        ToolLoopConfig config = normalizeConfig(loopRequest == null ? null : loopRequest.getConfig());
        String callerRunId = loopRequest == null ? null : loopRequest.getRunId();
        RecommendationPipelineState context = new RecommendationPipelineState(
                callerRunId == null || callerRunId.isBlank() ? UUID.randomUUID().toString() : callerRunId, request);
        validateRunId(context.getRunId());
        context.setDeadline(runtimeLimits.getRunTimeout());
        if (heartbeat != null) {
            context.setExecutionGuard(heartbeat::assertActive);
            heartbeat.onLost(context::close);
            context.checkActive();
        }
        if (runtimeStore != null && !alreadyPersisted) {
            runtimeStore.startRun(context.getRunId(), request);
        }
        DynamicSubAgentRuntime subAgentRuntime;
        try {
            subAgentRuntime = subAgentRuntimeRegistry == null
                    ? new DynamicSubAgentRuntime(context.getRunId(), lease == null ? 0 : lease.attempt())
                    : subAgentRuntimeRegistry.create(context.getRunId(), lease == null ? 0 : lease.attempt());
        } catch (RuntimeException setupError) {
            try {
                if (lease != null) runtimeStore.failRun(context.getRunId(), "setup_failed", lease);
                else failPreparedRun(context.getRunId(), "setup_failed");
            } catch (RuntimeException cleanupError) {
                setupError.addSuppressed(cleanupError);
            }
            throw setupError;
        }
        AtomicReference<AgentRunEvent> terminalEvent = new AtomicReference<>();
        Consumer<AgentRunEvent> safeEventSink = synchronizedEventSink(event -> {
            if (lease != null && ("run.completed".equals(event.getName()) || "run.failed".equals(event.getName()))) {
                terminalEvent.set(event);
                return;
            }
            if (persistentEventService != null) {
                if (lease != null) persistentEventService.append(event, lease);
                else persistentEventService.append(event);
            }
            if (eventSink != null) {
                eventSink.accept(event);
            }
        });
        context.setLlmBudget(new LlmCallBudget(configuredMaxLlmCalls));
        long start = System.nanoTime();
        List<String> thoughts = new ArrayList<>();
        List<ToolCallRecord> toolCalls = new ArrayList<>();
        List<ToolObservation> observations = new ArrayList<>();
        Map<String, EvidenceRecord> evidences = new LinkedHashMap<>();
        Set<String> fingerprints = ConcurrentHashMap.newKeySet();
        AtomicInteger eventSequence = new AtomicInteger();
        AtomicInteger parallelBatchCount = new AtomicInteger();
        AtomicInteger parallelSpecialistCount = new AtomicInteger();
        String metricOutcome = "failed";
        try {
            emit(safeEventSink, context, eventSequence, "run_started", "run.started", "running",
                    "Recommendation Agent Loop started", Map.of("scene", scenePathEnforcer.normalizeScene(request.getScene()), "llmMetrics", context.getLlmBudget().snapshot()), start);

        String status = "running";
        String stopReason = "completed";

        for (int step = 1; step <= config.getMaxSteps() && toolCalls.size() < config.getMaxSteps(); step++) {
            context.checkActive();
            int remaining = config.getMaxSteps() - toolCalls.size();
            List<SpecialistParallelPlanner.PlannedSpecialistAction> parallelActions =
                    parallelPlanner.plan(context, config.getToolWhitelist(), remaining);
            if (!parallelActions.isEmpty()) {
                parallelBatchCount.incrementAndGet();
                parallelSpecialistCount.addAndGet(parallelActions.size());
                MergeOutcome outcome = runParallelBatch(
                        parallelActions, step, context, config, fingerprints, safeEventSink,
                        eventSequence, start, thoughts, toolCalls, observations, evidences,
                        subAgentRuntime, lease);
                pipelineExecutor.finalizeProductsIfReady(context);
                if (!outcome.success()) {
                    status = outcome.status();
                    stopReason = outcome.stopReason();
                    break;
                }
                continue;
            }

            AgentId assigned = planAgent(context, observations, evidences, step);
            context.checkActive();
            String supervisorThought = "Supervisor delegated the next bounded task to " + assigned;
            thoughts.add(supervisorThought);

            if (assigned == AgentId.SUPERVISOR) {
                if (!config.getToolWhitelist().contains(RecommendationPipelineExecutor.FINAL_ACTION)) {
                    toolCalls.add(blockedCall(toolCalls.size() + 1,
                            RecommendationPipelineExecutor.FINAL_ACTION, "tool is not in whitelist"));
                    status = "blocked";
                    stopReason = "tool_not_whitelisted";
                    break;
                }
                AgentActionDecision finalDecision = AgentActionDecision.builder()
                        .thought("All required specialist evidence is complete.")
                        .action(RecommendationPipelineExecutor.FINAL_ACTION)
                        .finalAnswer("Recommendation plan is ready.")
                        .evidenceIds(context.evidenceIds()).build();
                ToolCallRecord finalRecord = handleFinalAnswer(
                        toolCalls.size() + 1, finalDecision, context, evidences);
                toolCalls.add(finalRecord);
                status = "success".equals(finalRecord.getStatus()) ? "completed" : "blocked";
                stopReason = "success".equals(finalRecord.getStatus())
                        ? "final_answer" : finalRecord.getErrorMessage();
                context.addMessage(AgentMessage.builder().type(AgentMessageType.COMPLETE)
                        .from(AgentId.SUPERVISOR).to(AgentId.SUPERVISOR)
                        .summary(stopReason).payload(Map.of("step", step)).build());
                break;
            }

            String goal = "Produce the missing evidence for "
                    + scenePathEnforcer.describeScene(request.getScene());
            List<String> toolScopes = scenePathEnforcer.executableTools(
                    assigned, request.getScene(), context);
            DynamicSubAgentRuntime.TaskHandle taskHandle = subAgentRuntime.spawn(
                    new DynamicSubAgentRuntime.SubAgentSpec(
                            assigned.name(), assigned, goal, toolScopes, remaining, null),
                    context);
            persistRuntime(subAgentRuntime, lease);
            String taskId = taskHandle.taskId();
            context.addMessage(AgentMessage.builder()
                    .type(AgentMessageType.DELEGATE)
                    .from(AgentId.SUPERVISOR)
                    .to(assigned)
                    .summary(supervisorThought)
                    .payload(Map.of("step", step, "taskId", taskId,
                            "goal", scenePathEnforcer.completionConditions(request.getScene()),
                            "dependencies", subAgentRuntime.dependencies(taskId),
                            "candidateVersion", taskHandle.context().candidateVersion()))
                    .build());
            emit(safeEventSink, context, eventSequence, "model_completed", "agent.assigned", "success",
                    "Delegated to " + assigned, Map.of("step", step, "agent", assigned.name(), "taskId", taskId), start);
            emit(safeEventSink, context, eventSequence, "model_completed", "planner.decision", "success",
                    supervisorThought, Map.of("step", step, "agent", assigned.name()), start);

            subAgentRuntime.markRunning(taskHandle);
            persistRuntime(subAgentRuntime, lease);
            SpecialistAgentTeam.SpecialistRun specialistRun = specialistAgentTeam.run(
                    new SpecialistAgentTeam.AgentTask(taskId, assigned,
                            goal, toolCalls.size() + 1, remaining, taskHandle.context()),
                    context, config.getToolWhitelist(), fingerprints,
                    specialistListener(safeEventSink, context, eventSequence, start));
            subAgentRuntime.finish(taskHandle, specialistRun.success(), specialistRun.stopReason(),
                    specialistRun.observations());
            persistRuntime(subAgentRuntime, lease);
            MergeOutcome outcome = mergeSpecialistRun(
                    assigned, taskId, specialistRun, step, context, safeEventSink, eventSequence,
                    start, thoughts, toolCalls, observations, evidences,
                    subAgentRuntime.artifactIds(taskId));
            pipelineExecutor.finalizeProductsIfReady(context);
            if (!outcome.success()) {
                status = outcome.status();
                stopReason = outcome.stopReason();
                break;
            }
        }

        if ("running".equals(status)) {
            status = "blocked";
            stopReason = "max_steps_exceeded";
        }

        RecommendationResponse response = "completed".equals(status) ? buildResponse(context, start) : null;
        RecommendationPlan plan = "completed".equals(status) ? buildPlan(context, toolCalls, start) : null;
        context.checkActive();
        if (plan != null) {
            emit(safeEventSink, context, eventSequence, "run_completed", "run.completed", "success",
                    "Recommendation plan completed", Map.of(
                            "final_answer", plan,
                            "response", plan,
                            "metrics", Map.of("toolCalls", plan.getMetrics().getToolCalls(),
                                    "steps", plan.getMetrics().getSteps(),
                                    "latencyMs", plan.getMetrics().getLatencyMs()),
                            "dataSources", context.getDataSources()), start);
        }
        if (plan == null) {
            emit(safeEventSink, context, eventSequence, "run_failed", "run.failed", status,
                    "Recommendation did not complete", Map.of("stopReason", stopReason), start);
        }
        Map<String, Object> llmMetrics = new LinkedHashMap<>(context.getLlmBudget().snapshot());
        llmMetrics.put("invalidAgentSelections", context.getInvalidAgentSelections());
        llmMetrics.put("collaborationMessages", context.getMessages());
        llmMetrics.put("vetoCount", context.getVetoes().size());
        llmMetrics.put("deniedBlackboardWrites", context.getDeniedWrites().size());
        llmMetrics.put("parallelBatchCount", parallelBatchCount.get());
        llmMetrics.put("parallelSpecialistCount", parallelSpecialistCount.get());
        finishRuntime(subAgentRuntime, status, stopReason, lease, terminalEvent.get());
        publishCommittedTerminal(eventSink, terminalEvent.get());
        metricOutcome = status;
        completePersistentStream(context.getRunId());
        llmMetrics.put("subAgentTaskCount", subAgentRuntime.taskViews().size());
        llmMetrics.put("subAgentTasks", subAgentRuntime.taskViews());
        llmMetrics.put("subAgentArtifacts", subAgentRuntime.artifactViews());
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
                .llmMetrics(llmMetrics)
                .build();
        } catch (RuntimeException error) {
            context.close();
            if (error instanceof StaleExecutionLeaseException) {
                // Local bookkeeping only: never persist a failure over the replacement's attempt.
                if (subAgentRuntimeRegistry != null) subAgentRuntimeRegistry.finish(subAgentRuntime, "failed", "lease_lost");
                else subAgentRuntime.finishRun("failed", "lease_lost");
                throw error;
            }
            logRuntimeFailure("root", context, error);
            metricOutcome = error instanceof RunDeadlineExceededException ? "timeout" : "failed";
            try {
                emit(safeEventSink, context, eventSequence, "run_failed", "run.failed", "failed",
                        "Recommendation Agent Loop failed", Map.of(
                                "errorType", error.getClass().getSimpleName()), start);
            } catch (RuntimeException ignored) {
                // Preserve the original failure if the event store is unavailable too.
            }
            try {
                finishRuntime(subAgentRuntime, "failed", failureReason(error), lease, terminalEvent.get());
                publishCommittedTerminal(eventSink, terminalEvent.get());
            } catch (RuntimeException persistenceFailure) {
                error.addSuppressed(persistenceFailure);
                try {
                    if (lease != null) runtimeStore.failRun(context.getRunId(), "execution_failed", lease);
                    else failPreparedRun(context.getRunId(), "execution_failed");
                } catch (RuntimeException cleanupError) {
                    error.addSuppressed(cleanupError);
                }
            } finally {
                completePersistentStream(context.getRunId());
            }
            throw error;
        } finally {
            context.close();
            if (telemetry != null) {
                telemetry.recordRun(request.getScene(), metricOutcome, System.nanoTime() - start, toolCalls.size());
            }
        }
    }

    private void finishRuntime(DynamicSubAgentRuntime runtime, String status, String stopReason,
                               RecommendationExecutionLease lease, AgentRunEvent terminalEvent) {
        if (subAgentRuntimeRegistry == null) {
            runtime.finishRun(status, stopReason);
        } else {
            subAgentRuntimeRegistry.finish(runtime, status, stopReason);
        }
        if (runtimeStore != null && lease != null) runtimeStore.persist(runtime.runView(), lease, terminalEvent);
        else persistRuntime(runtime, null);
    }

    private void publishCommittedTerminal(Consumer<AgentRunEvent> sink, AgentRunEvent event) {
        if (sink == null || event == null) return;
        try {
            sink.accept(event);
        } catch (RuntimeException ignored) {
            // Delivery failure must not turn an already committed success into a failed run.
            // SSE readers can resume from the durable event log.
        }
    }

    private void logRuntimeFailure(String stage, RecommendationPipelineState context, Throwable error) {
        String origin = java.util.Arrays.stream(error.getStackTrace())
                .filter(frame -> frame.getClassName().startsWith("com.ecommerce."))
                .findFirst().map(StackTraceElement::toString).orElse("external_dependency");
        // Keep model text, request content, credentials and raw exception messages out of logs.
        log.warn("Recommendation failed: runId={}, scene={}, stage={}, errorType={}, origin={}",
                context.getRunId(), scenePathEnforcer.normalizeScene(context.getRequest().getScene()),
                stage, error.getClass().getSimpleName(), origin);
    }

    private void completePersistentStream(String runId) {
        if (persistentEventService != null) {
            persistentEventService.complete(runId);
        }
    }

    private static String failureReason(RuntimeException error) {
        String message = error.getMessage();
        return message == null || message.isBlank()
                ? "unexpected_" + error.getClass().getSimpleName()
                : "unexpected_" + error.getClass().getSimpleName() + ":" + message;
    }

    private MergeOutcome runParallelBatch(
            List<SpecialistParallelPlanner.PlannedSpecialistAction> actions,
            int step,
            RecommendationPipelineState context,
            ToolLoopConfig config,
            Set<String> fingerprints,
            Consumer<AgentRunEvent> eventSink,
            AtomicInteger eventSequence,
            long start,
            List<String> thoughts,
            List<ToolCallRecord> toolCalls,
            List<ToolObservation> observations,
            Map<String, EvidenceRecord> evidences,
            DynamicSubAgentRuntime subAgentRuntime, RecommendationExecutionLease lease) {
        List<DynamicSubAgentRuntime.SubAgentSpec> specs = actions.stream()
                .map(action -> new DynamicSubAgentRuntime.SubAgentSpec(
                        action.agent().name(),
                        action.agent(),
                        "Produce dependency-safe evidence for "
                                + scenePathEnforcer.describeScene(context.getRequest().getScene()),
                        List.of(action.action()),
                        1,
                        action.action()))
                .toList();
        List<DynamicSubAgentRuntime.TaskHandle> taskHandles =
                subAgentRuntime.spawnBatch(specs, context);
        persistRuntime(subAgentRuntime, lease);
        List<ParallelInvocation> invocations = new ArrayList<>();
        for (int index = 0; index < actions.size(); index++) {
            SpecialistParallelPlanner.PlannedSpecialistAction action = actions.get(index);
            DynamicSubAgentRuntime.TaskHandle taskHandle = taskHandles.get(index);
            String taskId = taskHandle.taskId();
            String thought = "Supervisor scheduled " + action.agent() + "/" + action.action()
                    + " in a dependency-safe parallel batch";
            thoughts.add(thought);
            context.addMessage(AgentMessage.builder()
                    .type(AgentMessageType.DELEGATE)
                    .from(AgentId.SUPERVISOR)
                    .to(action.agent())
                    .summary(thought)
                    .payload(Map.of("step", step, "taskId", taskId, "action", action.action(),
                            "parallel", true,
                            "dependencies", subAgentRuntime.dependencies(taskId),
                            "candidateVersion", taskHandle.context().candidateVersion()))
                    .build());
            emit(eventSink, context, eventSequence, "model_completed", "agent.assigned", "success",
                    "Parallel delegation to " + action.agent(),
                    Map.of("step", step, "agent", action.agent().name(), "taskId", taskId,
                            "action", action.action(), "parallel", true), start);
            SpecialistAgentTeam.AgentTask task = new SpecialistAgentTeam.AgentTask(
                    taskId, action.agent(),
                    "Produce dependency-safe evidence for "
                            + scenePathEnforcer.describeScene(context.getRequest().getScene()),
                    toolCalls.size() + index + 1, 1, taskHandle.context());
            invocations.add(new ParallelInvocation(action, task, taskHandle));
        }

        List<CompletableFuture<SpecialistAgentTeam.SpecialistRun>> futures = invocations.stream()
                .map(invocation -> submitOrRunInCaller(() -> {
                    subAgentRuntime.markRunning(invocation.taskHandle());
                    persistRuntime(subAgentRuntime, lease);
                    return specialistAgentTeam.runPlannedAction(
                            invocation.task(), invocation.action().action(), context,
                            config.getToolWhitelist(), fingerprints,
                            specialistListener(eventSink, context, eventSequence, start));
                }))
                .toList();

        MergeOutcome firstFailure = MergeOutcome.ok();
        for (int index = 0; index < invocations.size(); index++) {
            ParallelInvocation invocation = invocations.get(index);
            SpecialistAgentTeam.SpecialistRun specialistRun;
            try {
                specialistRun = context.await(futures.get(index));
            } catch (Exception error) {
                if (error instanceof RunDeadlineExceededException deadline) {
                    futures.forEach(future -> future.cancel(true));
                    throw deadline;
                }
                if (error instanceof StaleExecutionLeaseException stale) {
                    futures.forEach(future -> future.cancel(true));
                    throw stale;
                }
                logRuntimeFailure("parallel_" + invocation.action().agent().name(), context, error);
                specialistRun = SpecialistAgentTeam.SpecialistRun.failed(
                        invocation.action().agent(), "parallel_specialist_failed",
                        List.of(), List.of(), List.of(), List.of());
            }
            subAgentRuntime.finish(invocation.taskHandle(), specialistRun.success(),
                    specialistRun.stopReason(), specialistRun.observations());
            persistRuntime(subAgentRuntime, lease);
            MergeOutcome outcome = mergeSpecialistRun(
                    invocation.action().agent(), invocation.task().taskId(), specialistRun, step,
                    context, eventSink, eventSequence, start,
                    thoughts, toolCalls, observations, evidences,
                    subAgentRuntime.artifactIds(invocation.task().taskId()));
            if (firstFailure.success() && !outcome.success()) {
                firstFailure = outcome;
            }
        }
        return firstFailure;
    }

    private CompletableFuture<SpecialistAgentTeam.SpecialistRun> submitOrRunInCaller(
            Supplier<SpecialistAgentTeam.SpecialistRun> task) {
        try {
            return CompletableFuture.supplyAsync(task, orchestrationExecutor);
        } catch (RejectedExecutionException rejected) {
            return CompletableFuture.completedFuture(task.get());
        }
    }

    private SpecialistAgentTeam.SpecialistRunListener specialistListener(
            Consumer<AgentRunEvent> eventSink,
            RecommendationPipelineState context,
            AtomicInteger eventSequence,
            long start) {
        return new SpecialistAgentTeam.SpecialistRunListener() {
            @Override
            public void onToolStarted(AgentId agent, int sequence, String tool, Map<String, Object> arguments) {
                emit(eventSink, context, eventSequence, "tool_started", "tool.started", "running",
                        "Executing " + tool, Map.of("step", sequence, "tool", tool,
                                "agent", agent.name(), "arguments", arguments), start);
            }

            @Override
            public void onToolCompleted(AgentId agent, ToolCallRecord call, ToolObservation observation) {
                if (telemetry != null) telemetry.recordTool(call);
                emit(eventSink, context, eventSequence,
                        "failed".equals(call.getStatus()) ? "error" : "tool_completed",
                        "failed".equals(call.getStatus()) ? "tool.failed" : "tool.completed",
                        call.getStatus(), call.getResultSummary() == null ? call.getToolName() : call.getResultSummary(),
                        Map.of("step", call.getSequence(), "tool", call.getToolName(),
                                "agent", agent.name(), "latencyMs", call.getLatencyMs()), start);
                if (observation != null) {
                    emit(eventSink, context, eventSequence, "retrieval_completed", "observation", "success",
                            observation.getSummary(), Map.of("step", call.getSequence(),
                                    "tool", observation.getToolName(), "agent", agent.name(),
                                    "evidenceIds", observation.getEvidenceIds()), start);
                }
            }
        };
    }

    private MergeOutcome mergeSpecialistRun(
            AgentId assigned,
            String taskId,
            SpecialistAgentTeam.SpecialistRun specialistRun,
            int step,
            RecommendationPipelineState context,
            Consumer<AgentRunEvent> eventSink,
            AtomicInteger eventSequence,
            long start,
            List<String> thoughts,
            List<ToolCallRecord> toolCalls,
            List<ToolObservation> observations,
            Map<String, EvidenceRecord> evidences) {
        return mergeSpecialistRun(assigned, taskId, specialistRun, step, context,
                eventSink, eventSequence, start, thoughts, toolCalls, observations,
                evidences, List.of());
    }

    private MergeOutcome mergeSpecialistRun(
            AgentId assigned,
            String taskId,
            SpecialistAgentTeam.SpecialistRun specialistRun,
            int step,
            RecommendationPipelineState context,
            Consumer<AgentRunEvent> eventSink,
            AtomicInteger eventSequence,
            long start,
            List<String> thoughts,
            List<ToolCallRecord> toolCalls,
            List<ToolObservation> observations,
            Map<String, EvidenceRecord> evidences,
            List<String> artifactIds) {
        thoughts.addAll(specialistRun.thoughts());
        toolCalls.addAll(specialistRun.toolCalls());
        observations.addAll(specialistRun.observations());
        for (ToolObservation observation : specialistRun.observations()) {
            Object vetoed = observation.getData() == null
                    ? List.of() : observation.getData().getOrDefault("vetoedProductIds", List.of());
            if (vetoed instanceof List<?> vetoedIds && !vetoedIds.isEmpty()) {
                context.addMessage(AgentMessage.builder()
                        .type(AgentMessageType.VETO)
                        .from(AgentId.INVENTORY)
                        .to(AgentId.SUPERVISOR)
                        .summary("Vetoed unavailable products")
                        .payload(Map.of("productIds", vetoedIds))
                        .build());
                context.addMessage(AgentMessage.builder()
                        .type(AgentMessageType.REQUEST_REVISION)
                        .from(AgentId.SUPERVISOR).to(AgentId.PRODUCT)
                        .summary("Replace inventory-vetoed products")
                        .payload(Map.of("productIds", vetoedIds)).build());
                emit(eventSink, context, eventSequence, "model_completed", "agent.vetoed", "success",
                        "Inventory vetoed " + vetoedIds,
                        Map.of("step", step, "agent", AgentId.INVENTORY.name(), "productIds", vetoedIds), start);
            }
        }
        for (EvidenceRecord evidence : specialistRun.evidences()) {
            evidences.put(evidence.getEvidenceId(), evidence);
        }
        context.addMessage(AgentMessage.builder()
                .type(specialistRun.success() ? AgentMessageType.RESULT : AgentMessageType.ERROR)
                .from(assigned).to(AgentId.SUPERVISOR)
                .summary(specialistRun.stopReason())
                .payload(Map.of("taskId", taskId, "toolCount", specialistRun.toolCalls().size(),
                        "evidenceIds", specialistRun.evidences().stream().map(EvidenceRecord::getEvidenceId).toList(),
                        "artifactIds", artifactIds == null ? List.of() : artifactIds))
                .build());
        if (!specialistRun.success()) {
            String status = switch (specialistRun.stopReason()) {
                case "tool_not_whitelisted", "duplicate_tool_call", "specialist_scope_violation" -> "blocked";
                default -> "failed";
            };
            return new MergeOutcome(false, status, specialistRun.stopReason());
        }
        if (specialistRun.toolCalls().isEmpty()) {
            return new MergeOutcome(false, "blocked", "specialist_made_no_progress");
        }
        return MergeOutcome.ok();
    }

    private Consumer<AgentRunEvent> synchronizedEventSink(Consumer<AgentRunEvent> eventSink) {
        Consumer<AgentRunEvent> target = eventSink == null ? ignored -> {} : eventSink;
        Object monitor = new Object();
        return event -> {
            synchronized (monitor) {
                target.accept(event);
            }
        };
    }

    private void persistRuntime(DynamicSubAgentRuntime runtime, RecommendationExecutionLease lease) {
        if (runtimeStore != null && runtime != null) {
            if (lease != null) runtimeStore.persist(runtime.runView(), lease, null);
            else runtimeStore.persist(runtime.runView());
        }
    }

    private record ParallelInvocation(
            SpecialistParallelPlanner.PlannedSpecialistAction action,
            SpecialistAgentTeam.AgentTask task,
            DynamicSubAgentRuntime.TaskHandle taskHandle) {}

    private record MergeOutcome(boolean success, String status, String stopReason) {
        private static MergeOutcome ok() {
            return new MergeOutcome(true, "running", "");
        }
    }

    private ToolLoopConfig normalizeConfig(ToolLoopConfig config) {
        ToolLoopConfig value = config == null ? ToolLoopConfig.builder().build() : config;
        int requested = value.getMaxSteps() <= 0 ? 8 : value.getMaxSteps();
        return ToolLoopConfig.builder().maxSteps(Math.min(requested, runtimeLimits.getMaxSteps()))
                .toolWhitelist(scenePathEnforcer.normalizeWhitelist(value.getToolWhitelist())).build();
    }

    private void validateRequest(RecommendationRequest request) {
        if (request.getNumItems() <= 0 || request.getNumItems() > runtimeLimits.getMaxItems()) {
            throw new IllegalArgumentException("numItems is outside the configured limit");
        }
    }

    private static void validateRunId(String runId) {
        if (!runId.matches("[A-Za-z0-9_-]{1,128}")) {
            throw new IllegalArgumentException("runId must contain 1-128 letters, digits, underscores or hyphens");
        }
    }

    private AgentId planAgent(
            RecommendationPipelineState context,
            List<ToolObservation> observations,
            Map<String, EvidenceRecord> evidences,
            int step) {
        AgentId fallbackAgent = scenePathEnforcer.expectedNextAgent(context.getRequest().getScene(), context);
        List<AgentId> allowed = scenePathEnforcer.allowedAgents(context.getRequest().getScene(), context);
        if (allowed.isEmpty()) return fallbackAgent;
        if (!allowed.contains(fallbackAgent)) fallbackAgent = allowed.get(0);
        if (!modeResolver.llmEnabled()) {
            return fallbackAgent;
        }
        if (context.getLlmBudget() != null
                && !context.getLlmBudget().tryAcquire("supervisor", context.getRunId() + ":supervisor:" + step)) {
            return fallbackAgent;
        }
        try {
            String response = supervisorLlmClient.complete(
                    supervisorSystemPrompt(),
                    supervisorUserPrompt(context, observations, evidences));
            AgentId proposed = parseAgentId(response);
            if (!allowed.contains(proposed)) {
                context.recordInvalidAgentSelection(proposed, fallbackAgent, "agent_prerequisites_not_met");
                return fallbackAgent;
            }
            return proposed;
        } catch (Exception ignored) {
            context.recordInvalidAgentSelection(null, fallbackAgent, "supervisor_llm_failed");
            return fallbackAgent;
        }
    }

    private String supervisorSystemPrompt() {
        return """
                You are the supervisor of a bounded recommendation team.
                Choose exactly one next specialist. Return JSON only:
                {"thought":"brief reason","agent":"PROFILE|PRODUCT|INVENTORY|COPY|SUPERVISOR"}

                Roles:
                - PROFILE: user profile and recent orders
                - PRODUCT: product search, revision after veto, and rerank
                - INVENTORY: campaign constraints, fulfillment, inventory, and veto
                - COPY: localized or retention copy
                - SUPERVISOR: finish only when required evidence is complete
                Do not invent product IDs, countries, currency, or evidence IDs.
                """;
    }

    private String supervisorUserPrompt(
            RecommendationPipelineState context,
            List<ToolObservation> observations,
            Map<String, EvidenceRecord> evidences) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("request", pipelineExecutor.crossBorderState(context.getRequest()));
        state.put("goal", "Assign the specialist who can produce the next required evidence.");
        state.put("requiredCapabilities", scenePathEnforcer.requiredCapabilities(context.getRequest().getScene()));
        state.put("optionalCapabilities", scenePathEnforcer.optionalCapabilities(context.getRequest().getScene()));
        state.put("completionConditions", scenePathEnforcer.completionConditions(context.getRequest().getScene()));
        state.put("recommendedPath", scenePathEnforcer.recommendedPath(context.getRequest().getScene()));
        state.put("allowedAgents", scenePathEnforcer.allowedAgents(context.getRequest().getScene(), context));
        state.put("expectedNextAgent", scenePathEnforcer.expectedNextAgent(context.getRequest().getScene(), context).name());
        state.put("expectedNextTool", scenePathEnforcer.expectedNextStep(context.getRequest().getScene(), context));
        state.put("hasProfile", context.getProfile() != null);
        state.put("rawProductCount", context.getRawProducts() == null ? 0 : context.getRawProducts().size());
        state.put("rankedProductCount", context.getRankedProducts() == null ? 0 : context.getRankedProducts().size());
        state.put("hasInventory", context.getAvailableIds() != null);
        state.put("finalProductCount", context.getFinalProducts() == null ? 0 : context.getFinalProducts().size());
        state.put("copyCount", context.getCopies() == null ? 0 : context.getCopies().size());
        state.put("unhandledVeto", context.hasUnhandledVeto());
        state.put("invalidAgentSelections", context.getInvalidAgentSelections());
        state.put("observations", observations);
        state.put("knownEvidenceIds", evidences.keySet());
        state.put("dataSources", context.getDataSources());
        try {
            return objectMapper.writeValueAsString(state);
        } catch (Exception e) {
            return state.toString();
        }
    }

    private AgentId parseAgentId(String raw) throws Exception {
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
            cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
        }
        Map<String, Object> data = objectMapper.readValue(cleaned, new TypeReference<>() {});
        Object agent = data.get("agent");
        if (agent == null) {
            agent = data.get("nextAgent");
        }
        if (agent == null) {
            return null;
        }
        try {
            return AgentId.valueOf(String.valueOf(agent).trim().toUpperCase());
        } catch (IllegalArgumentException ignored) {
            return null;
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
        Set<String> vetoed = context.vetoedProductIds();
        if (context.getFinalProducts() != null && !vetoed.isEmpty()) {
            context.setFinalProducts(context.getFinalProducts().stream()
                    .filter(product -> !vetoed.contains(product.getProductId()))
                    .toList());
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
        strategy.put("mode", "supervisor_multi_agent_loop");
        strategy.put("agentTeam", List.of("PROFILE", "PRODUCT", "INVENTORY", "COPY"));
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

    @FunctionalInterface
    public interface SupervisorLlmClient {
        String complete(String systemPrompt, String userPrompt);
    }
}
