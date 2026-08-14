package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;
import com.ecommerce.aftersales.model.DecisionRoute;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.service.AfterSalesEvidencePlannerService.PlanningInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 受限 Hybrid Agent Loop：Intake 分类 → 决策路线解析 → 由 Evidence Planner 驱动的取证循环。
 *
 * 决策路线（DecisionRouteResolver，模型不能输出）：
 * - Intake 分类完成后立即解析路线，并用路线证据重建 IntakeResult.requiredEvidence
 *   （模型/规则的 requiredEvidence 只是不可信建议，不能降低也不能抬高服务端要求）；
 * - ANSWER_ONLY（仅 TRACK_SHIPMENT）：只取证 ORDER+SHIPMENT，READY 后直接以确定性 Java 模板
 *   答复物流状态（decision_completed / ANSWER_DELIVERED），绝不调用 POLICY、CALCULATE_COMPENSATION
 *   或 CREATE_ACTION_PROPOSAL，也不需要额外的 LLM 调用；
 * - COMPENSATION_EVALUATION（含 REQUEST_REFUND）：取证 ORDER+SHIPMENT+POLICY，READY 后执行
 *   calculate_compensation：eligible=false → decision_completed + NO_ACTION_REQUIRED（不建方案）；
 *   eligible=true → decision_completed 后 create_action_proposal → ACTION_PROPOSAL_CREATED。
 *
 * 取证循环：
 * - 每一步由 AfterSalesEvidencePlannerService 决定下一份证据（EvidenceType），
 *   Java 侧把证据映射到既有的只读工具（get_order_detail / get_shipment_trace / search_after_sales_policy）；
 * - 工具参数只来自 AfterSalesToolExecutor.trustedArguments，模型从不产生参数；
 * - EvidencePreconditionGate 对所有规划结果做业务前置校验（非必需证据 / 已存在证据 /
 *   SHIPMENT 依赖 ORDER、POLICY 依赖 ORDER+SHIPMENT / READY 证据不齐）：
 *   LLM 规划形式合法但前置不满足 → 以 LLM_INVALID_PLAN 拒绝并直接规则兜底（不重复调用模型）；
 *   兜底（或 RULES 模式）规划也必须过同一 Gate，仍不可推进 → 整条工单失败 PLANNER_NO_PROGRESS；
 * - 指纹去重与 maxSteps 上限保留：重复工具调用与超步数都按失败处理。
 *
 * 决策阶段：READY_FOR_DECISION 后离开取证循环，先按路线做服务端决策前置校验
 * （validateDecisionPrerequisites，路线感知：ANSWER_ONLY 只需 ORDER+SHIPMENT，
 * COMPENSATION_EVALUATION 必须 ORDER+SHIPMENT+POLICY）；缺证据以 DECISION_EVIDENCE_INCOMPLETE
 * 干净失败，绝不带着缺失证据进入决策工具。maxSteps 是跨越取证与决策两阶段的最终预算：
 * 每次工具执行前都校验剩余步数。系统故障（工具异常/超步/非法规划）仍整条工单失败。
 *
 * 不记录思维链、prompt、key 或模型原始输出；所有事件只携带结构化安全字段。
 */
@Service
public class AfterSalesAgentLoopService {
    private static final List<String> TOOL_WHITELIST = List.of(
            AfterSalesToolExecutor.GET_ORDER_DETAIL,
            AfterSalesToolExecutor.GET_SHIPMENT_TRACE,
            AfterSalesToolExecutor.SEARCH_POLICY,
            AfterSalesToolExecutor.CALCULATE_COMPENSATION,
            AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL
    );

    private final AfterSalesToolExecutor toolExecutor;
    private final AfterSalesRunEventService eventService;
    private final AfterSalesRunRepository runRepository;
    private final AfterSalesTicketRepository ticketRepository;
    private final AfterSalesTicketContextService ticketContextService;
    private final AfterSalesIntakeService intakeService;
    private final AfterSalesEvidencePlannerService plannerService;
    private final DecisionRouteResolver routeResolver;
    private final ObjectMapper objectMapper;
    private final long stepDelayMs;
    /** 证据业务前置校验（纯 Java 组件）：所有规划来源（LLM 与规则兜底）必须通过同一 Gate。 */
    private final EvidencePreconditionGate preconditionGate = new EvidencePreconditionGate();

    public AfterSalesAgentLoopService(
            AfterSalesToolExecutor toolExecutor,
            AfterSalesRunEventService eventService,
            AfterSalesRunRepository runRepository,
            AfterSalesTicketRepository ticketRepository,
            AfterSalesTicketContextService ticketContextService,
            AfterSalesIntakeService intakeService,
            AfterSalesEvidencePlannerService plannerService,
            DecisionRouteResolver routeResolver,
            ObjectMapper objectMapper,
            @Value("${agent.aftersales.demo-step-delay-ms:0}") long stepDelayMs) {
        this.toolExecutor = toolExecutor;
        this.eventService = eventService;
        this.runRepository = runRepository;
        this.ticketRepository = ticketRepository;
        this.ticketContextService = ticketContextService;
        this.intakeService = intakeService;
        this.plannerService = plannerService;
        this.routeResolver = routeResolver;
        this.objectMapper = objectMapper;
        this.stepDelayMs = Math.max(0, stepDelayMs);
    }

    public void run(String runId, String ticketId) {
        AfterSalesRunEntity run = runRepository.findById(runId).orElseThrow();
        AfterSalesTicketContextService.TicketContext ticketContext = ticketContextService.load(ticketId);
        AfterSalesTicketEntity ticket = ticketContext.ticket();
        AfterSalesAgentState state = new AfterSalesAgentState(runId, ticket);
        Set<String> fingerprints = new HashSet<>();
        // 单调时钟测量分析总耗时：不受系统时间跳变影响。总耗时是用户感知的完整 run 时长
        // （含演示等待），工具事件里的 latencyMs 才不含演示等待。
        long startedNanos = System.nanoTime();

        try {
            eventService.append(runId, "run_started", "售后分析开始", "running",
                "工单进入受限 Agent Loop。", Map.of(
                        "summary", "正在识别客户诉求并收集当前路线所需证据。",
                        "ticketId", ticketId,
                        "orderId", ticket.getOrderId(),
                        "maxSteps", run.getMaxSteps()
                ));

        // Intake：结构化分类先于取证循环执行。不进入工具循环，不计入工具 stepCount；
        // LLM 失败由 AfterSalesIntakeService 降级为规则分类，Intake 自身失败不影响整条工单。
        long intakeStartedNanos = System.nanoTime();
        eventService.append(runId, "intake_started", "提取工单意图", "running",
                "正在从客户消息中提取问题类型、意图与紧急度。", Map.of(
                        "summary", "正在从客户消息中提取问题类型、意图与紧急度。",
                        "ticketId", ticketId
                ));
        AfterSalesTypes.IntakeResult intake = intakeService.classify(ticketContext.customerMessage());
        // 决策路线解析（确定性、模型不可输出）：用服务端路线证据重建 requiredEvidence，
        // 模型的 requiredEvidence 建议既不能降低也不能抬高本路线的要求。
        DecisionRouteResolver.RouteDecision routeDecision = routeResolver.resolve(intake);
        intake = intake.withRequiredEvidence(routeDecision.requiredEvidence());
        state.setIntake(intake);
        state.setRoute(routeDecision.route());
        eventService.append(runId, "intake_completed", "Intake 分析完成", "success",
                intakeSummary(intake, routeDecision.route()),
                intakeEventData(intake, routeDecision.route(), elapsedMs(intakeStartedNanos)));

            // 取证循环：Planner 决定下一份证据；READY_FOR_DECISION 即离开循环进入决策阶段。
            int step = 0;
            while (true) {
                if (step >= run.getMaxSteps()) {
                    throw new IllegalStateException("MAX_STEPS_EXCEEDED");
                }
                AfterSalesTypes.PlanningResult plan = planNextStep(run, state, step + 1);
                if (plan.nextEvidence() == EvidenceType.READY_FOR_DECISION) {
                    validateDecisionPrerequisites(state); // 路线感知前置校验：缺证据 → DECISION_EVIDENCE_INCOMPLETE
                    break;
                }
                step = executeToolStep(run, state, step, fingerprints, evidenceToolName(plan.nextEvidence()));
            }
            // 决策阶段（确定性，按路线分派）：Planner 绝不选择决策工具，证据齐备后由 Java 直接执行。
            // executeToolStep 内部同样校验 maxSteps 预算（跨越取证与决策两阶段的最终预算）。
            step = decide(run, state, step, fingerprints);
            complete(run, ticket, state, step, startedNanos);
        } catch (Exception error) {
            long durationMs = elapsedMs(startedNanos);
            run.setStatus("FAILED");
            run.setStopReason(error.getMessage());
            run.setCompletedAt(Instant.now());
            run.setDurationMs(durationMs);
            runRepository.save(run);
            ticket.setStatus(AfterSalesTypes.TicketStatus.FAILED);
            ticketRepository.save(ticket);
            eventService.append(runId, "error", "售后分析失败", "failed",
                    error.getMessage(), Map.of(
                            "summary", error.getMessage(),
                            "durationMs", durationMs
                    ));
            eventService.complete(runId);
        }
    }

    /**
     * 单次规划周期：planning_started →（planning_fallback 可选）→ planning_completed。
     * LLM 规划经 EvidencePreconditionGate 校验：形式合法但业务前置不满足（非必需/已存在/
     * 依赖缺失/READY 证据不齐）→ 以 LLM_INVALID_PLAN 拒绝并规则兜底（不重复调用模型）；
     * 规划结果标记 invalidInput（requiredEvidence 含未知证据）→ PLANNER_INVALID_REQUIRED_EVIDENCE；
     * 兜底（或 RULES 模式）规划也必须过同一 Gate，仍不可推进 → PLANNER_NO_PROGRESS。
     * planning_completed / planning_fallback 携带显式字段 nextEvidence（不再是 evidence）。
     */
    private AfterSalesTypes.PlanningResult planNextStep(
            AfterSalesRunEntity run,
            AfterSalesAgentState state,
            int step) {
        Map<String, Boolean> presence = evidencePresence(state);
        eventService.append(run.getId(), "planning_started", "规划下一步取证", "running",
                "基于已获取证据规划下一个取证步骤。", Map.of(
                        "summary", "基于已获取证据规划下一个取证步骤。",
                        "step", step,
                        "evidencePresence", presence,
                        "requiredEvidence", state.getIntake().requiredEvidence()
                ));
        long plannedAt = System.nanoTime();
        PlanningInput input = new PlanningInput(state.getIntake(), presence);
        AfterSalesTypes.PlanningResult plan = plannerService.plan(input);
        String rejectReason = null;
        if ("LLM".equals(plan.source()) && !passesGate(plan, state, presence)) {
            // LLM 规划形式合法但业务前置不满足（非必需/已存在/依赖缺失/READY 证据不齐）：
            // 拒绝（LLM_INVALID_PLAN）并规则兜底，不重复调用模型。
            rejectReason = "LLM_INVALID_PLAN";
            plan = plannerService.deterministicPlan(input, rejectReason);
        }
        if (plan.invalidInput()) {
            // 服务端输入非法（requiredEvidence 含未知证据）：规划器已明确标记不可用，绝不执行。
            throw new IllegalStateException("PLANNER_INVALID_REQUIRED_EVIDENCE");
        }
        if (!passesGate(plan, state, presence)) {
            // 兜底（或 RULES 模式）规划仍不过同一 Gate：执行该规划不会让工单前进。
            throw new IllegalStateException("PLANNER_NO_PROGRESS");
        }
        if (rejectReason != null || isDegradedPlan(plan)) {
            appendFallbackEvent(run.getId(), step, plan, rejectReason != null ? rejectReason : plan.fallbackReason());
        }
        long latencyMs = elapsedMs(plannedAt);
        eventService.append(run.getId(), "planning_completed", "取证规划完成", "success",
                planningSummary(plan), Map.of(
                        "summary", planningSummary(plan),
                        "nextEvidence", plan.nextEvidence().name(),
                        "reasonCode", plan.reasonCode(),
                        "source", plan.source(),
                        "step", step,
                        "latencyMs", latencyMs
                ));
        return plan;
    }

    /** 业务前置校验（EvidencePreconditionGate）：所有规划来源必须能通过才能推进。 */
    private boolean passesGate(
            AfterSalesTypes.PlanningResult plan,
            AfterSalesAgentState state,
            Map<String, Boolean> presence) {
        return preconditionGate.validate(
                plan.nextEvidence(), state.getIntake().requiredEvidence(), presence).passed();
    }

    /** 降级判定：RULES 模式是主动配置，不算降级；其余 fallbackReason 都代表 LLM 路径降级。 */
    private static boolean isDegradedPlan(AfterSalesTypes.PlanningResult plan) {
        return "RULE_FALLBACK".equals(plan.source())
                && plan.fallbackReason() != null
                && !"RULES_MODE".equals(plan.fallbackReason());
    }

    private void appendFallbackEvent(String runId, int step, AfterSalesTypes.PlanningResult plan, String reason) {
        eventService.append(runId, "planning_fallback", "降级为规则规划", "success",
                "模型规划不可用，已按规则降级规划。", Map.of(
                        "summary", "模型规划不可用，已按规则降级规划。",
                        "nextEvidence", plan.nextEvidence().name(),
                        "reasonCode", plan.reasonCode(),
                        "source", plan.source(),
                        "step", step,
                        "fallbackReason", reason
                ));
    }

    /**
     * 决策前置校验（路线感知）：ANSWER_ONLY 只需 ORDER+SHIPMENT（纯物流答复不需要政策/金额）；
     * COMPENSATION_EVALUATION 必须 ORDER+SHIPMENT+POLICY 三份齐备，否则无法计算金额/上限/资格。
     * 正常流程中 Planner 的 READY 已隐含路线证据齐备（在场快照与状态同源），本校验是
     * 进入决策阶段前的纵深防御：任何不一致都以 DECISION_EVIDENCE_INCOMPLETE 干净失败。
     * package-private：同包测试直接覆盖路线感知逻辑。
     */
    static void validateDecisionPrerequisites(AfterSalesAgentState state) {
        List<String> missing = new ArrayList<>();
        if (state.getOrder() == null) {
            missing.add(EvidenceType.ORDER.name());
        }
        if (state.getShipment() == null) {
            missing.add(EvidenceType.SHIPMENT.name());
        }
        if (state.getRoute() == DecisionRoute.COMPENSATION_EVALUATION && state.getPolicy() == null) {
            missing.add(EvidenceType.POLICY.name());
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("DECISION_EVIDENCE_INCOMPLETE:" + String.join(",", missing));
        }
    }

    /**
     * 决策阶段（确定性，按路线分派；无额外 LLM 调用）：
     * - ANSWER_ONLY：只发 decision_completed（物流状态答复），立即完成 —— 绝不调用 POLICY、
     *   CALCULATE_COMPENSATION 或 CREATE_ACTION_PROPOSAL；
     * - COMPENSATION_EVALUATION：执行 calculate_compensation；eligible=true → decision_completed
     *   后 create_action_proposal；eligible=false → decision_completed（NO_ACTION）后完成，
     *   不调用 create_action_proposal。
     */
    private int decide(
            AfterSalesRunEntity run,
            AfterSalesAgentState state,
            int step,
            Set<String> fingerprints) {
        switch (state.getRoute()) {
            case ANSWER_ONLY -> {
                eventService.append(run.getId(), "decision_completed", "决策完成", "success",
                        "直接答复物流状态。", Map.of(
                                "summary", "仅查询物流，已按可信物流状态直接答复，无需人工审批。",
                                "route", state.getRoute().name(),
                                "requiresApproval", false,
                                "answerType", "SHIPMENT_STATUS",
                                "answer", shipmentAnswer(state.getShipment()),
                                "evidenceIds", List.copyOf(state.getEvidenceIds()),
                                "stepCount", step
                        ));
                return step;
            }
            case COMPENSATION_EVALUATION -> {
                step = executeToolStep(run, state, step, fingerprints, AfterSalesToolExecutor.CALCULATE_COMPENSATION);
                AfterSalesTypes.CompensationResult compensation = state.getCompensation();
                eventService.append(run.getId(), "decision_completed", "决策完成", "success",
                        compensation.eligible() ? "生成待审批补偿方案。" : "未达到政策补偿阈值，无需动作。", Map.of(
                                "summary", compensation.eligible()
                                        ? "规则计算可补偿，生成待审批方案。"
                                        : "未达到政策补偿阈值，无需任何动作。",
                                "route", state.getRoute().name(),
                                "eligible", compensation.eligible(),
                                "requiresApproval", compensation.eligible(),
                                "action", compensation.actionType(),
                                "amount", compensation.amount(),
                                "currency", compensation.currency(),
                                "reason", compensation.reason(),
                                "evidenceIds", List.copyOf(state.getEvidenceIds()),
                                "stepCount", step
                        ));
                if (!compensation.eligible()) {
                    return step; // 不可补偿：绝不调用 create_action_proposal。
                }
                return executeToolStep(run, state, step, fingerprints, AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL);
            }
            default -> throw new IllegalStateException("ROUTE_NOT_IMPLEMENTED");
        }
    }

    /** 服务端重建的证据在场快照：只反映状态里真正已收集的证据，模型不可修改。 */
    private static Map<String, Boolean> evidencePresence(AfterSalesAgentState state) {
        Map<String, Boolean> presence = new LinkedHashMap<>();
        presence.put(EvidenceType.ORDER.name(), state.getOrder() != null);
        presence.put(EvidenceType.SHIPMENT.name(), state.getShipment() != null);
        presence.put(EvidenceType.POLICY.name(), state.getPolicy() != null);
        return presence;
    }

    /** Java 侧证据 → 只读取证工具映射；READY_FOR_DECISION 与决策工具不在取证工具集内。 */
    private static String evidenceToolName(EvidenceType evidence) {
        return switch (evidence) {
            case ORDER -> AfterSalesToolExecutor.GET_ORDER_DETAIL;
            case SHIPMENT -> AfterSalesToolExecutor.GET_SHIPMENT_TRACE;
            case POLICY -> AfterSalesToolExecutor.SEARCH_POLICY;
            case READY_FOR_DECISION -> throw new IllegalStateException("READY_FOR_DECISION_IS_NOT_A_TOOL");
        };
    }

    /** 执行一个工具步骤：预算校验 → 指纹去重 → 事件 → 执行 → stepCount 持久化。 */
    private int executeToolStep(
            AfterSalesRunEntity run,
            AfterSalesAgentState state,
            int step,
            Set<String> fingerprints,
            String action) {
        if (!TOOL_WHITELIST.contains(action)) {
            throw new IllegalStateException("TOOL_NOT_WHITELISTED");
        }
        int nextStep = step + 1;
        // maxSteps 是跨越取证与决策两阶段的最终预算：每次工具执行前校验，决策工具同样受约束。
        if (nextStep > run.getMaxSteps()) {
            throw new IllegalStateException("MAX_STEPS_EXCEEDED");
        }
        Map<String, Object> arguments = toolExecutor.trustedArguments(action, state);
        String fingerprint = action + ":" + arguments;
        if (!fingerprints.add(fingerprint)) {
            throw new IllegalStateException("DUPLICATE_TOOL_CALL");
        }

        eventService.append(run.getId(), "tool_started", toolLabel(action), "running",
                decisionSummary(action), Map.of(
                        "summary", decisionSummary(action),
                        "action", action,
                        "arguments", arguments,
                        "step", nextStep
                ));

        pauseBetweenSteps();
        long startedAt = System.nanoTime();
        AfterSalesTypes.ToolResult result = toolExecutor.execute(action, state);
        long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

        eventService.append(run.getId(), eventType(action), toolLabel(action), "success",
                result.summary(), Map.of(
                        "summary", result.summary(),
                        "action", action,
                        "observation", result.data(),
                        "evidenceIds", result.evidenceIds(),
                        "latencyMs", latencyMs,
                        "step", nextStep
                ));
        run.setStepCount(nextStep);
        runRepository.save(run);
        return nextStep;
    }

    /** 完成入口：按决策路线分发到各自的终态（run/ticket/finalAnswer/stopReason 均按路线确定）。 */
    private void complete(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            AfterSalesAgentState state,
            int stepCount,
            long startedNanos) {
        switch (state.getRoute()) {
            case ANSWER_ONLY -> completeAnswerOnly(run, ticket, state, stepCount, startedNanos);
            case COMPENSATION_EVALUATION -> completeCompensation(run, ticket, state, stepCount, startedNanos);
            default -> throw new IllegalStateException("ROUTE_NOT_IMPLEMENTED");
        }
    }

    /**
     * ANSWER_ONLY 终态：直接以可信物流状态答复客户。run COMPLETED / ticket RESOLVED /
     * stopReason ANSWER_DELIVERED / requiresApproval false / answerType SHIPMENT_STATUS，
     * 只带结构化物流答复与证据 ID，无金额/方案字段。
     */
    private void completeAnswerOnly(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            AfterSalesAgentState state,
            int stepCount,
            long startedNanos) {
        long durationMs = elapsedMs(startedNanos);
        Map<String, Object> finalAnswer = baseFinalAnswer(state, ticket);
        finalAnswer.put("requiresApproval", false);
        finalAnswer.put("answerType", "SHIPMENT_STATUS");
        finalAnswer.put("answer", shipmentAnswer(state.getShipment()));
        finalAnswer.put("decisionSummary",
                "No refund request was classified; the ticket is answered with the trusted shipment status "
                        + "(inactive for " + state.getShipment().inactiveDays() + " days).");
        finish(run, ticket, stepCount, durationMs, finalAnswer, "ANSWER_DELIVERED",
                AfterSalesTypes.TicketStatus.RESOLVED, "直接答复物流状态",
                "分析完成，已按可信物流状态直接答复，无需人工审批。");
    }

    /**
     * COMPENSATION_EVALUATION 终态：eligible=true → 方案待审批（PENDING_APPROVAL /
     * ACTION_PROPOSAL_CREATED / requiresApproval true）；eligible=false → 无动作完成
     * （RESOLVED / NO_ACTION_REQUIRED / requiresApproval false / action NO_ACTION /
     * reason POLICY_THRESHOLD_NOT_REACHED），不生成任何方案。
     */
    private void completeCompensation(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            AfterSalesAgentState state,
            int stepCount,
            long startedNanos) {
        long durationMs = elapsedMs(startedNanos);
        AfterSalesTypes.CompensationResult compensation = state.getCompensation();
        Map<String, Object> finalAnswer = baseFinalAnswer(state, ticket);
        boolean eligible = compensation != null && compensation.eligible();
        finalAnswer.put("eligible", eligible);
        finalAnswer.put("requiresApproval", eligible);
        if (eligible) {
            finalAnswer.put("action", compensation.actionType());
            finalAnswer.put("reason", compensation.reason());
            finalAnswer.put("decisionSummary",
                    "The shipment is inactive beyond policy threshold. A deterministic delay coupon proposal is pending operator approval.");
            finish(run, ticket, stepCount, durationMs, finalAnswer, "ACTION_PROPOSAL_CREATED",
                    AfterSalesTypes.TicketStatus.PENDING_APPROVAL, "生成待审批方案",
                    "补偿方案已生成，等待人工审批。");
        } else {
            finalAnswer.put("action", "NO_ACTION");
            finalAnswer.put("reason", compensation == null ? "COMPENSATION_NOT_CALCULATED" : compensation.reason());
            finalAnswer.put("decisionSummary",
                    "The shipment does not reach the policy compensation threshold; no action is required.");
            finish(run, ticket, stepCount, durationMs, finalAnswer, "NO_ACTION_REQUIRED",
                    AfterSalesTypes.TicketStatus.RESOLVED, "无需动作",
                    "分析完成，未达到政策补偿阈值，无需任何动作。");
        }
    }

    /** 各路线 finalAnswer 的公共字段：route 与证据始终输出；缺失的证据/方案字段不输出 null。 */
    private Map<String, Object> baseFinalAnswer(AfterSalesAgentState state, AfterSalesTicketEntity ticket) {
        Map<String, Object> finalAnswer = new LinkedHashMap<>();
        finalAnswer.put("ticketId", ticket.getId());
        finalAnswer.put("order", state.getOrder());
        finalAnswer.put("shipment", state.getShipment());
        finalAnswer.put("evidenceIds", state.getEvidenceIds());
        finalAnswer.put("intake", state.getIntake());
        finalAnswer.put("decisionRoute", state.getRoute().name());
        if (state.getPolicy() != null) {
            finalAnswer.put("policy", state.getPolicy());
        }
        if (state.getCompensation() != null) {
            finalAnswer.put("compensation", state.getCompensation());
        }
        if (state.getProposalId() != null) {
            finalAnswer.put("proposalId", state.getProposalId());
        }
        return finalAnswer;
    }

    /** 结构化物流答复（确定性 Java 模板，无额外 LLM）：状态 + 未更新/延迟天数 + 最后更新时间。 */
    private static Map<String, Object> shipmentAnswer(AfterSalesTypes.ShipmentSnapshot shipment) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("trackingNumber", shipment.trackingNumber());
        answer.put("status", shipment.status());
        answer.put("lastUpdatedAt", shipment.lastUpdatedAt().toString());
        answer.put("inactiveDays", shipment.inactiveDays());
        answer.put("delayDays", shipment.delayDays());
        return answer;
    }

    /** 各路线共用的落库与 run_completed 事件收尾。 */
    private void finish(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            int stepCount,
            long durationMs,
            Map<String, Object> finalAnswer,
            String stopReason,
            AfterSalesTypes.TicketStatus ticketStatus,
            String eventName,
            String eventSummary) {
        run.setStatus("COMPLETED");
        run.setStepCount(stepCount);
        run.setStopReason(stopReason);
        run.setCompletedAt(Instant.now());
        run.setDurationMs(durationMs);
        run.setFinalAnswerJson(writeJson(finalAnswer));
        runRepository.save(run);

        ticket.setStatus(ticketStatus);
        ticketRepository.save(ticket);

        eventService.append(run.getId(), "run_completed", eventName, "success",
                "分析完成，Agent 未执行任何副作用操作。", Map.of(
                        "summary", eventSummary,
                        "durationMs", durationMs,
                        "finalAnswer", finalAnswer
                ));
    }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** intake_completed 只携带结构化分类字段、服务端决策路线、source、latencyMs 和安全 summary。 */
    private Map<String, Object> intakeEventData(
            AfterSalesTypes.IntakeResult intake,
            DecisionRoute route,
            long latencyMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", intakeSummary(intake, route));
        data.put("issueType", intake.issueType());
        data.put("intents", intake.intents());
        data.put("urgency", intake.urgency());
        data.put("entities", intake.entities());
        data.put("missingInfo", intake.missingInfo());
        data.put("requiredEvidence", intake.requiredEvidence()); // 路线解析后的服务端可信清单。
        data.put("route", route.name());
        data.put("source", intake.source());
        data.put("latencyMs", latencyMs);
        if (intake.fallbackReason() != null) {
            data.put("fallbackReason", intake.fallbackReason());
        }
        return data;
    }

    private String intakeSummary(AfterSalesTypes.IntakeResult intake, DecisionRoute route) {
        String source = "LLM".equals(intake.source()) ? "模型识别" : "规则降级";
        return "识别为物流延迟，紧急度" + urgencyLabel(intake.urgency())
                + "，决策路线：" + routeLabel(route) + "，来源：" + source + "。";
    }

    private String routeLabel(DecisionRoute route) {
        return switch (route) {
            case ANSWER_ONLY -> "直接答复";
            case COMPENSATION_EVALUATION -> "补偿评估";
            case REQUEST_MORE_INFO -> "补充信息";
            case HUMAN_ESCALATION -> "人工升级";
        };
    }

    private String urgencyLabel(String urgency) {
        return switch (urgency) {
            case "HIGH" -> "高";
            case "MEDIUM" -> "中";
            default -> "低";
        };
    }

    private String planningSummary(AfterSalesTypes.PlanningResult plan) {
        String source = "LLM".equals(plan.source()) ? "模型规划" : "规则规划";
        return "下一步取证：" + plan.nextEvidence().name() + "，来源：" + source + "。";
    }

    private String decisionSummary(String action) {
        return switch (action) {
            case AfterSalesToolExecutor.GET_ORDER_DETAIL -> "先核验工单绑定订单、付款状态、金额和币种。";
            case AfterSalesToolExecutor.GET_SHIPMENT_TRACE -> "订单已验证，查询可信物流轨迹和最后更新时间。";
            case AfterSalesToolExecutor.SEARCH_POLICY -> "物流已长期未更新，检索订单国家和发生时间对应的政策版本。";
            case AfterSalesToolExecutor.CALCULATE_COMPENSATION -> "已获取适用政策，由 Java 规则计算补偿金额。";
            case AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL -> "所需证据已获取且金额计算完成，生成待人工审批方案。";
            default -> "执行受限售后工具。";
        };
    }

    private String toolLabel(String action) {
        return switch (action) {
            case AfterSalesToolExecutor.GET_ORDER_DETAIL -> "核验订单";
            case AfterSalesToolExecutor.GET_SHIPMENT_TRACE -> "查询物流";
            case AfterSalesToolExecutor.SEARCH_POLICY -> "检索售后政策";
            case AfterSalesToolExecutor.CALCULATE_COMPENSATION -> "计算补偿";
            case AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL -> "创建处理方案";
            default -> action;
        };
    }

    private String eventType(String action) {
        return AfterSalesToolExecutor.SEARCH_POLICY.equals(action) ? "retrieval_completed" : "tool_completed";
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception error) {
            throw new IllegalStateException("FINAL_ANSWER_SERIALIZATION_FAILED", error);
        }
    }

    private void pauseBetweenSteps() {
        if (stepDelayMs == 0) {
            return;
        }
        try {
            Thread.sleep(stepDelayMs);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("AGENT_RUN_INTERRUPTED", error);
        }
    }
}
