package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;
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
 * 受限 Hybrid Agent Loop：Intake 分类后，进入由 Evidence Planner 驱动的取证循环。
 *
 * 取证循环：
 * - 每一步由 AfterSalesEvidencePlannerService 决定下一份证据（EvidenceType），
 *   Java 侧把证据映射到既有的只读工具（get_order_detail / get_shipment_trace / search_after_sales_policy）；
 * - 工具参数只来自 AfterSalesToolExecutor.trustedArguments，模型从不产生参数；
 * - Planner 事件：planning_started / planning_completed / planning_fallback；
 *   LLM 规划请求了已存在或非必需的证据 → 以 LLM_INVALID_PLAN 拒绝并直接规则兜底（不重复调用模型）；
 *   兜底仍无法推进（如规划结果指向已存在证据）→ 整条工单失败 PLANNER_NO_PROGRESS；
 * - 指纹去重与 maxSteps 上限保留：重复工具调用与超步数都按失败处理。
 *
 * 决策阶段：READY_FOR_DECISION 后离开取证循环，先做服务端决策前置校验 —— 当前补偿管线
 * （calculate_compensation → create_action_proposal）要求 ORDER/SHIPMENT/POLICY 三份证据齐备；
 * 取证的 requiredEvidence 子集可以不含 POLICY（规划层面合法），但决策管线无法在缺少政策证据时
 * 计算金额/上限/资格，此时以 DECISION_EVIDENCE_INCOMPLETE 干净失败，绝不带着缺失证据进入决策工具。
 * 默认 ORDER+SHIPMENT+POLICY 场景下工具 stepCount 总数保持 5（3 份取证 + 2 步决策）。
 * maxSteps 是跨越取证与决策两阶段的最终预算：每次工具执行前都校验剩余步数。
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
    private final ObjectMapper objectMapper;
    private final long stepDelayMs;

    public AfterSalesAgentLoopService(
            AfterSalesToolExecutor toolExecutor,
            AfterSalesRunEventService eventService,
            AfterSalesRunRepository runRepository,
            AfterSalesTicketRepository ticketRepository,
            AfterSalesTicketContextService ticketContextService,
            AfterSalesIntakeService intakeService,
            AfterSalesEvidencePlannerService plannerService,
            ObjectMapper objectMapper,
            @Value("${agent.aftersales.demo-step-delay-ms:0}") long stepDelayMs) {
        this.toolExecutor = toolExecutor;
        this.eventService = eventService;
        this.runRepository = runRepository;
        this.ticketRepository = ticketRepository;
        this.ticketContextService = ticketContextService;
        this.intakeService = intakeService;
        this.plannerService = plannerService;
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
                        "summary", "正在核验订单、物流和适用政策。",
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
        state.setIntake(intake);
        eventService.append(runId, "intake_completed", "Intake 分析完成", "success",
                intakeSummary(intake), intakeEventData(intake, elapsedMs(intakeStartedNanos)));

            // 取证循环：Planner 决定下一份证据；READY_FOR_DECISION 即离开循环进入决策阶段。
            int step = 0;
            while (true) {
                if (step >= run.getMaxSteps()) {
                    throw new IllegalStateException("MAX_STEPS_EXCEEDED");
                }
                AfterSalesTypes.PlanningResult plan = planNextStep(run, state, step + 1);
                if (plan.nextEvidence() == EvidenceType.READY_FOR_DECISION) {
                    validateDecisionPrerequisites(state); // 决策前置校验：缺证据 → DECISION_EVIDENCE_INCOMPLETE
                    break;
                }
                step = executeToolStep(run, state, step, fingerprints, evidenceToolName(plan.nextEvidence()));
            }
            // 决策阶段（确定性）：Planner 绝不选择这两个工具，证据齐备后由 Java 直接执行。
            // executeToolStep 内部同样校验 maxSteps 预算（跨越取证与决策两阶段的最终预算）。
            step = executeToolStep(run, state, step, fingerprints, AfterSalesToolExecutor.CALCULATE_COMPENSATION);
            step = executeToolStep(run, state, step, fingerprints, AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL);
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
     * LLM 规划请求已存在/非必需的证据时以 LLM_INVALID_PLAN 拒绝并规则兜底（不重复调用模型）；
     * 规划结果标记 invalidInput（requiredEvidence 含未知证据）→ PLANNER_INVALID_REQUIRED_EVIDENCE；
     * 兜底后仍无法推进 → PLANNER_NO_PROGRESS。
     * planning_completed / planning_fallback 携带显式字段 nextEvidence（不再是 evidence）。
     */
    private AfterSalesTypes.PlanningResult planNextStep(
            AfterSalesRunEntity run,
            AfterSalesAgentState state,
            int step) {
        Map<String, Boolean> presence = evidencePresence(state);
        eventService.append(run.getId(), "planning_started", "规划下一步取证", "running",
                "基于已核验证据规划下一个取证步骤。", Map.of(
                        "summary", "基于已核验证据规划下一个取证步骤。",
                        "step", step,
                        "evidencePresence", presence,
                        "requiredEvidence", state.getIntake().requiredEvidence()
                ));
        long plannedAt = System.nanoTime();
        PlanningInput input = new PlanningInput(state.getIntake(), presence);
        AfterSalesTypes.PlanningResult plan = plannerService.plan(input);
        String rejectReason = null;
        if ("LLM".equals(plan.source()) && !advances(plan, state.getIntake(), presence)) {
            // LLM 请求了已存在或非必需的证据：拒绝（LLM_INVALID_PLAN）并规则兜底，不重复调用模型。
            rejectReason = "LLM_INVALID_PLAN";
            plan = plannerService.deterministicPlan(input, rejectReason);
        }
        if (plan.invalidInput()) {
            // 服务端输入非法（requiredEvidence 含未知证据）：规划器已明确标记不可用，绝不执行。
            throw new IllegalStateException("PLANNER_INVALID_REQUIRED_EVIDENCE");
        }
        if (!advances(plan, state.getIntake(), presence)) {
            // 兜底仍无法推进：执行该规划不会让工单前进（重复取证 / 证据缺失就绪决策）。
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

    /** 判断规划结果能否让工单前进：证据必须「必需且缺失」；READY_FOR_DECISION 必须证据齐备。 */
    private static boolean advances(
            AfterSalesTypes.PlanningResult plan,
            AfterSalesTypes.IntakeResult intake,
            Map<String, Boolean> presence) {
        EvidenceType next = plan.nextEvidence();
        if (next == EvidenceType.READY_FOR_DECISION) {
            for (String required : intake.requiredEvidence()) {
                if (!Boolean.TRUE.equals(presence.get(required))) {
                    return false;
                }
            }
            return true;
        }
        return !Boolean.TRUE.equals(presence.get(next.name()))
                && intake.requiredEvidence().contains(next.name());
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
     * 决策前置校验：当前补偿管线要求 ORDER/SHIPMENT/POLICY 三份证据齐备。取证的 requiredEvidence
     * 子集可以不含 POLICY（规划层面合法），但决策管线无法在缺少政策证据时计算金额/上限/资格；
     * 缺失任何一份都在进入决策工具前以 DECISION_EVIDENCE_INCOMPLETE 干净失败。
     */
    private static void validateDecisionPrerequisites(AfterSalesAgentState state) {
        List<String> missing = new ArrayList<>();
        if (state.getOrder() == null) {
            missing.add(EvidenceType.ORDER.name());
        }
        if (state.getShipment() == null) {
            missing.add(EvidenceType.SHIPMENT.name());
        }
        if (state.getPolicy() == null) {
            missing.add(EvidenceType.POLICY.name());
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("DECISION_EVIDENCE_INCOMPLETE:" + String.join(",", missing));
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

    private void complete(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            AfterSalesAgentState state,
            int stepCount,
            long startedNanos) {
        long durationMs = elapsedMs(startedNanos);
        Map<String, Object> finalAnswer = new LinkedHashMap<>();
        finalAnswer.put("ticketId", ticket.getId());
        finalAnswer.put("order", state.getOrder());
        finalAnswer.put("shipment", state.getShipment());
        finalAnswer.put("policy", state.getPolicy());
        finalAnswer.put("compensation", state.getCompensation());
        finalAnswer.put("proposalId", state.getProposalId());
        finalAnswer.put("requiresApproval", true);
        finalAnswer.put("evidenceIds", state.getEvidenceIds());
        finalAnswer.put("intake", state.getIntake());
        finalAnswer.put("decisionSummary", "The shipment is inactive beyond policy threshold. A deterministic delay coupon proposal is pending operator approval.");

        run.setStatus("COMPLETED");
        run.setStepCount(stepCount);
        run.setStopReason("ACTION_PROPOSAL_CREATED");
        run.setCompletedAt(Instant.now());
        run.setDurationMs(durationMs);
        run.setFinalAnswerJson(writeJson(finalAnswer));
        runRepository.save(run);

        ticket.setStatus(AfterSalesTypes.TicketStatus.PENDING_APPROVAL);
        ticketRepository.save(ticket);

        eventService.append(run.getId(), "run_completed", "生成待审批方案", "success",
                "分析完成，Agent 未执行任何副作用操作。", Map.of(
                        "summary", "补偿方案已生成，等待人工审批。",
                        "durationMs", durationMs,
                        "finalAnswer", finalAnswer
                ));
   }

    private long elapsedMs(long startedNanos) {
        return (System.nanoTime() - startedNanos) / 1_000_000;
    }

    /** intake_completed 只携带结构化分类字段、source、latencyMs 和安全 summary。 */
    private Map<String, Object> intakeEventData(AfterSalesTypes.IntakeResult intake, long latencyMs) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("summary", intakeSummary(intake));
        data.put("issueType", intake.issueType());
        data.put("intents", intake.intents());
        data.put("urgency", intake.urgency());
        data.put("entities", intake.entities());
        data.put("missingInfo", intake.missingInfo());
        data.put("requiredEvidence", intake.requiredEvidence());
        data.put("source", intake.source());
        data.put("latencyMs", latencyMs);
        if (intake.fallbackReason() != null) {
            data.put("fallbackReason", intake.fallbackReason());
        }
        return data;
    }

    private String intakeSummary(AfterSalesTypes.IntakeResult intake) {
        String source = "LLM".equals(intake.source()) ? "模型识别" : "规则降级";
        return "识别为物流延迟，紧急度" + urgencyLabel(intake.urgency()) + "，来源：" + source + "。";
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
            case AfterSalesToolExecutor.CALCULATE_COMPENSATION -> "政策证据有效，由 Java 规则计算补偿金额。";
            case AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL -> "证据和金额均已验证，生成待人工审批方案。";
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
