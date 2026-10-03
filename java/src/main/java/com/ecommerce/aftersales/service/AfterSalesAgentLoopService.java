package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.TicketAttachmentEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;
import com.ecommerce.aftersales.model.DecisionRoute;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.TicketAttachmentRepository;
import com.ecommerce.aftersales.service.AfterSalesEvidencePlannerService.PlanningInput;
import com.ecommerce.service.LlmCallBudget;
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
 * - 证据图按问题类型区分（三条路径互不相同）：SHIPMENT_DELAY = ORDER→SHIPMENT→POLICY，
 *   LOST_IN_TRANSIT = ORDER→SHIPMENT→CARRIER_CASE→POLICY，DAMAGED_ITEM =
 *   ORDER→DELIVERY→DAMAGE_PHOTO→PRODUCT→POLICY；
 * - ANSWER_ONLY（仅 TRACK_SHIPMENT）：SHIPMENT_DELAY / LOST_IN_TRANSIT 只取证 ORDER+SHIPMENT，
 *   DAMAGED_ITEM 只取证 ORDER+DELIVERY；READY 后直接以确定性 Java 模板答复（物流状态 /
 *   交付证明），绝不调用 POLICY、CALCULATE_COMPENSATION 或 CREATE_ACTION_PROPOSAL；
 * - COMPENSATION_EVALUATION（含 REQUEST_REFUND）：按问题类型证据图取证，READY 后执行
 *   calculate_compensation（问题类型分派，绝不跨图取证）：eligible=false →
 *   decision_completed + NO_ACTION_REQUIRED（不建方案）；eligible=true → decision_completed
 *   后 create_action_proposal → ACTION_PROPOSAL_CREATED。
 *
 * 取证循环：
 * - 每一步由 AfterSalesEvidencePlannerService 决定下一份证据（EvidenceType），
 *   Java 侧把证据映射到只读工具（get_order_detail / get_shipment_trace / get_carrier_case /
 *   get_delivery_proof / get_damage_photo / get_product / search_after_sales_policy）；
 * - 工具参数只来自 AfterSalesToolExecutor.trustedArguments，模型从不产生参数；
 * - EvidencePreconditionGate 对所有规划结果做业务前置校验（非必需证据 / 已存在证据 /
 *   证据图依赖前置 / READY 证据不齐）：LLM 规划形式合法但前置不满足 → 以 LLM_INVALID_PLAN
 *   拒绝并直接规则兜底（不重复调用模型）；兜底（或 RULES 模式）规划也必须过同一 Gate，
 *   仍不可推进 → 整条工单失败 PLANNER_NO_PROGRESS；
 * - 指纹去重与 maxSteps 上限保留：重复工具调用与超步数都按失败处理。
 *
 * 决策阶段：READY_FOR_DECISION 后离开取证循环，先按路线做服务端决策前置校验
 * （validateDecisionPrerequisites，路线 + 问题类型感知，见方法注释）；缺证据以
 * DECISION_EVIDENCE_INCOMPLETE 干净失败，绝不带着缺失证据进入决策工具。maxSteps 是跨越
 * 取证与决策两阶段的最终预算：每次工具执行前都校验剩余步数。系统故障（工具异常/超步/
 * 非法规划）仍整条工单失败。
 *
 * 升级/外部等待（Phase 3，确定性规则，绝不 LLM）：
 * - UNSUPPORTED（取消订单/退换货/账号支付滥用）→ HUMAN_ESCALATION 路线：Intake 后立即
 *   以 run ESCALATED / ticket ESCALATED / stopReason UNSUPPORTED_ISSUE_TYPE 完成，
 *   零工具、零方案；
 * - 每次新证据收集后、任何不安全动作之前评估 AfterSalesEscalationPolicyService：
 *   证据矛盾（EVIDENCE_CONFLICT）、承运商调查停滞超过 7 天（CARRIER_INVESTIGATION_STALE）、
 *   高价值订单补偿评估（HIGH_VALUE_ORDER）→ human_escalation 正常终态；承运商调查进行中
 *   且 ≤ 7 天 → waiting_external 正常终态（WAITING_EXTERNAL，不失败、不升级、不查政策）；
 * - 政策查找无适用版本（PolicyNotFoundException）→ POLICY_NOT_COVERED 升级而非 FAILED
 *   （只捕获这一种已知政策未覆盖错误，未知异常保持 FAILED）；
 * - 连续 2 次真实 LLM 规划降级 → PLANNER_CONSECUTIVE_FALLBACK 升级；只有 RULES_MODE
 *   不计入连续降级（其余 RULE_FALLBACK，含 LLM_INVALID_* 与业务规则拒绝，都计入），
 *   非降级被接受后计数清零。
 * 升级/等待终态都以 eventService.complete 正常收尾（human_escalation / waiting_external +
 * run_completed SSE 事件），绝不走 error 分支；结构化原因持久化到工单 escalationReasonJson
 * 并在 finalAnswer 输出，绝不创建任何方案。
 *
 * 不记录思维链、prompt、key 或模型原始输出；所有事件只携带结构化安全字段。
 */
@Service
public class AfterSalesAgentLoopService {
    private static final List<String> TOOL_WHITELIST = List.of(
            AfterSalesToolExecutor.GET_ORDER_DETAIL,
            AfterSalesToolExecutor.GET_SHIPMENT_TRACE,
            AfterSalesToolExecutor.GET_CARRIER_CASE,
            AfterSalesToolExecutor.GET_DELIVERY_PROOF,
            AfterSalesToolExecutor.GET_DAMAGE_PHOTO,
            AfterSalesToolExecutor.GET_PRODUCT,
            AfterSalesToolExecutor.SEARCH_POLICY,
            AfterSalesToolExecutor.CALCULATE_COMPENSATION,
            AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL
    );

    private final AfterSalesToolExecutor toolExecutor;
    private final AfterSalesRunEventService eventService;
    private final AfterSalesRunRepository runRepository;
    private final AfterSalesTicketRepository ticketRepository;
    private final AfterSalesTicketContextService ticketContextService;
    private final TicketAttachmentRepository attachmentRepository;
    private final AfterSalesIntakeService intakeService;
    private final AfterSalesEvidencePlannerService plannerService;
    private final DecisionRouteResolver routeResolver;
    private final AfterSalesEscalationPolicyService escalationPolicy;
    private final ObjectMapper objectMapper;
    private final long stepDelayMs;
    @Value("$" + "{agent.aftersales.max-llm-calls:0}")
    private int configuredMaxLlmCalls = 0;
    /** 证据业务前置校验（纯 Java 组件）：所有规划来源（LLM 与规则兜底）必须通过同一 Gate。 */
    private final EvidencePreconditionGate preconditionGate = new EvidencePreconditionGate();

    @org.springframework.beans.factory.annotation.Autowired
    public AfterSalesAgentLoopService(
            AfterSalesToolExecutor toolExecutor,
            AfterSalesRunEventService eventService,
            AfterSalesRunRepository runRepository,
            AfterSalesTicketRepository ticketRepository,
            AfterSalesTicketContextService ticketContextService,
            TicketAttachmentRepository attachmentRepository,
            AfterSalesIntakeService intakeService,
            AfterSalesEvidencePlannerService plannerService,
            DecisionRouteResolver routeResolver,
            AfterSalesEscalationPolicyService escalationPolicy,
            ObjectMapper objectMapper,
            @Value("$" + "{agent.aftersales.demo-step-delay-ms:0}") long stepDelayMs) {
        this(toolExecutor, eventService, runRepository, ticketRepository, ticketContextService,
                attachmentRepository, intakeService, plannerService, routeResolver,
                escalationPolicy, objectMapper, stepDelayMs, 0);
    }

    /** Explicit budget constructor for offline tests with injected mock model responses. */
    public AfterSalesAgentLoopService(
            AfterSalesToolExecutor toolExecutor,
            AfterSalesRunEventService eventService,
            AfterSalesRunRepository runRepository,
            AfterSalesTicketRepository ticketRepository,
            AfterSalesTicketContextService ticketContextService,
            TicketAttachmentRepository attachmentRepository,
            AfterSalesIntakeService intakeService,
            AfterSalesEvidencePlannerService plannerService,
            DecisionRouteResolver routeResolver,
            AfterSalesEscalationPolicyService escalationPolicy,
            ObjectMapper objectMapper,
            long stepDelayMs,
            int maxLlmCalls) {
        this.toolExecutor = toolExecutor;
        this.eventService = eventService;
        this.runRepository = runRepository;
        this.ticketRepository = ticketRepository;
        this.ticketContextService = ticketContextService;
        this.attachmentRepository = attachmentRepository;
        this.intakeService = intakeService;
        this.plannerService = plannerService;
        this.routeResolver = routeResolver;
        this.escalationPolicy = escalationPolicy;
        this.objectMapper = objectMapper;
        this.stepDelayMs = Math.max(0, stepDelayMs);
        this.configuredMaxLlmCalls = Math.max(0, maxLlmCalls);
    }
    public void run(String runId, String ticketId) {
        AfterSalesRunEntity run = runRepository.findById(runId).orElseThrow();
        AfterSalesTicketContextService.TicketContext ticketContext = ticketContextService.load(ticketId);
        AfterSalesTicketEntity ticket = ticketContext.ticket();
        AfterSalesAgentState state = new AfterSalesAgentState(runId, ticket);
        state.setLlmBudget(new LlmCallBudget(configuredMaxLlmCalls));
        Set<String> fingerprints = new HashSet<>();
        // 单调时钟测量分析总耗时：不受系统时间跳变影响。总耗时是用户感知的完整 run 时长
        // （含演示等待），工具事件里的 latencyMs 才不含演示等待。
        long startedNanos = System.nanoTime();
        // 跨越取证与决策两阶段的工具步数（EscalationSignal 捕获分支也需要它）。
        int step = 0;

        try (LlmCallBudget.Scope ignored = LlmCallBudget.bind(state.getLlmBudget())) {
            eventService.append(runId, "run_started", "售后分析开始", "running",
                "工单进入受限 Agent Loop。", Map.of(
                        "summary", "正在识别客户诉求并收集当前路线所需证据。",
                        "ticketId", ticketId,
                        "orderId", ticket.getOrderId(),
                        "maxSteps", run.getMaxSteps(),
                        "llmMetrics", state.getLlmBudget().snapshot()
                ));

        // 恢复会话（run.parentRunId 非空 = 客户补充信息后的新 run）：还原父 run 的可恢复
        // 快照（ORDER/DELIVERY/intake/路线/证据 ID），跳过 Intake 分类，绝不重复已收集工具。
        boolean resumed = restoreFromParent(run, state, fingerprints);
        if (!resumed) {
            // Intake：结构化分类先于取证循环执行。不进入工具循环，不计入工具 stepCount；
            // LLM 失败由 AfterSalesIntakeService 降级为规则分类，Intake 自身失败不影响整条工单。
            classifyIntake(run, ticket, state, ticketContext.customerMessage());
        }

            // 超范围问题（UNSUPPORTED → HUMAN_ESCALATION 路线）：分类后立即转人工升级终态，
            // 零工具、零方案（stepCount 保持 0），结构化原因码 UNSUPPORTED_ISSUE_TYPE。
            if (state.getRoute() == DecisionRoute.HUMAN_ESCALATION) {
                completeEscalated(run, ticket, state, step, startedNanos,
                        AfterSalesEscalationPolicyService.unsupportedIssue(List.of(), Instant.now()));
                return;
            }

            // 取证循环：Planner 决定下一份证据；READY_FOR_DECISION 即离开循环进入决策阶段。
            int consecutiveDegradedPlans = 0;
            while (true) {
                if (step >= run.getMaxSteps()) {
                    throw new IllegalStateException("MAX_STEPS_EXCEEDED");
                }
                AfterSalesTypes.PlanningResult plan = planNextStep(run, state, step + 1);
                // 连续真实降级计数（RULES_MODE 与业务规则拒绝不计入，见策略服务）：每次规划
                // 兜底计数变化后、任何不安全动作之前评估 —— 连续 2 次真实降级即转人工升级。
                if (AfterSalesEscalationPolicyService.isTrueDegradedPlan(plan)) {
                    consecutiveDegradedPlans++;
                    if (consecutiveDegradedPlans >= 2) {
                        completeEscalated(run, ticket, state, step, startedNanos,
                                AfterSalesEscalationPolicyService.plannerConsecutiveFallback(
                                        List.copyOf(state.getEvidenceIds()), Instant.now()));
                        return;
                    }
                } else {
                    consecutiveDegradedPlans = 0;
                }
                if (plan.nextEvidence() == EvidenceType.READY_FOR_DECISION) {
                    validateDecisionPrerequisites(state); // 路线感知前置校验：缺证据 → DECISION_EVIDENCE_INCOMPLETE
                    break;
                }
                // DAMAGE_PHOTO 已规划但工单没有任何有效图片附件：不抛通用失败，以 REQUEST_MORE_INFO
                // 语义干净结束（run/ticket WAITING_CUSTOMER、CUSTOMER_INFO_REQUIRED、SSE 正常收尾）。
                if (plan.nextEvidence() == EvidenceType.DAMAGE_PHOTO && !hasValidImageAttachment(ticket)) {
                    completeRequestMoreInfo(run, ticket, state, step, startedNanos);
                    return;
                }
                step = executeToolStep(run, state, step, fingerprints, evidenceToolName(plan.nextEvidence()));
                // 升级政策评估：每次新证据收集后、任何不安全动作之前（确定性规则，绝不 LLM）。
                // 命中升级 → 正常终态转人工（绝不继续到政策/补偿/方案）；承运商调查进行中且
                // 仍在 SLA 内 → 正常终态外部等待（不失败、不升级、不查政策）。
                AfterSalesEscalationPolicyService.Assessment assessment = escalationPolicy.assess(state);
                if (assessment.escalation() != null) {
                    completeEscalated(run, ticket, state, step, startedNanos, assessment.escalation());
                    return;
                }
                if (assessment.waiting() != null) {
                    completeWaitingExternal(run, ticket, state, step, startedNanos, assessment.waiting());
                    return;
                }
            }
            // 决策阶段（确定性，按路线分派）：Planner 绝不选择决策工具，证据齐备后由 Java 直接执行。
            // executeToolStep 内部同样校验 maxSteps 预算（跨越取证与决策两阶段的最终预算）。
            step = decide(run, state, step, fingerprints);
            complete(run, ticket, state, step, startedNanos);
        } catch (EscalationSignal signal) {
            // 已知可升级错误（政策未覆盖 POLICY_NOT_FOUND）：以正常终态转人工升级，
            // 绝不走 error 失败分支（见 executeToolStep 的捕获点）。
            completeEscalated(run, ticket, state, step, startedNanos, signal.reason);
        } catch (Exception error) {
            long durationMs = elapsedMs(startedNanos);
            run.setStatus("FAILED");
            run.setStopReason(error.getMessage());
            run.setCompletedAt(Instant.now());
            run.setDurationMs(durationMs);
            run.setFinalAnswerJson(writeJson(Map.of("status", "FAILED", "llmMetrics", state.getLlmBudget().snapshot())));
            runRepository.save(run);
            ticket.setStatus(AfterSalesTypes.TicketStatus.FAILED);
            ticketRepository.save(ticket);
            eventService.append(runId, "error", "售后分析失败", "failed",
                    error.getMessage(), Map.of(
                            "summary", error.getMessage(),
                            "durationMs", durationMs,
                            "llmMetrics", state.getLlmBudget().snapshot()
                    ));
            eventService.complete(runId);
        }
    }

    /**
     * Intake 分类（仅新 run；恢复的 run 跳过）：结构化分类 → 服务端路线解析 → 事件。
     * 用可信分类结果同步工单问题类型（创建时的 issueType 只是种子）：政策检索与补偿计算
     * 都以分类后的问题类型为准（如工单被分类为 LOST_IN_TRANSIT 就检索丢失政策）。
     * 决策路线确定性解析（模型不可输出）：用服务端路线证据重建 requiredEvidence，
     * 模型的 requiredEvidence 建议既不能降低也不能抬高本路线的要求。
     */
    private void classifyIntake(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            AfterSalesAgentState state,
            String customerMessage) {
        long intakeStartedNanos = System.nanoTime();
        eventService.append(run.getId(), "intake_started", "提取工单意图", "running",
                "正在从客户消息中提取问题类型、意图与紧急度。", Map.of(
                        "summary", "正在从客户消息中提取问题类型、意图与紧急度。",
                        "ticketId", ticket.getId()
                ));
        AfterSalesTypes.IntakeResult intake = intakeService.classify(customerMessage);
        ticket.setIssueType(intake.issueType());
        DecisionRouteResolver.RouteDecision routeDecision = routeResolver.resolve(intake);
        intake = intake.withRequiredEvidence(routeDecision.requiredEvidence());
        state.setIntake(intake);
        state.setRoute(routeDecision.route());
        eventService.append(run.getId(), "intake_completed", "Intake 分析完成", "success",
                intakeSummary(intake, routeDecision.route()),
                intakeEventData(intake, routeDecision.route(), elapsedMs(intakeStartedNanos)));
    }

    /**
     * 恢复会话：新 run 存在 parentRunId 时，从父 run 的可恢复快照还原 ORDER/DELIVERY/intake/
     * 路线/证据 ID，种子指纹（纵深防御：任何重复取证立即失败），并发出结构化 run_resumed 事件。
     * 持久化 JSON 只是数据：路线与必需证据由服务端重新确定性解析，与快照路线不符 → 失败关闭；
     * 快照缺失/不可读同样失败关闭（绝不无快照裸跑：那会重复取证、失去信任边界）。
     * 返回 true 表示已恢复（跳过 Intake 分类）。
     */
    private boolean restoreFromParent(
            AfterSalesRunEntity run,
            AfterSalesAgentState state,
            Set<String> fingerprints) {
        if (run.getParentRunId() == null) {
            return false;
        }
        AfterSalesRunEntity parent = runRepository.findById(run.getParentRunId())
                .orElseThrow(() -> new IllegalStateException("PARENT_RUN_NOT_FOUND"));
        if (parent.getResumeStateJson() == null || parent.getResumeStateJson().isBlank()) {
            throw new IllegalStateException("PARENT_RUN_NOT_RESUMABLE");
        }
        AfterSalesTypes.ResumeState resume = parseResumeState(parent.getResumeStateJson());
        if (resume.intake() == null || resume.order() == null || resume.delivery() == null
                || resume.evidenceIds() == null) {
            // 快照缺关键字段（intake/ORDER/DELIVERY/证据 ID 不可缺）：失败关闭，绝不无快照裸跑
            // （那会重复取证、失去信任边界）。
            throw new IllegalStateException("PARENT_RUN_STATE_INVALID");
        }
        DecisionRouteResolver.RouteDecision routeDecision = routeResolver.resolve(resume.intake());
        if (routeDecision.route() != resume.route()) {
            throw new IllegalStateException("PARENT_RUN_ROUTE_MISMATCH");
        }
        AfterSalesTypes.IntakeResult intake = resume.intake().withRequiredEvidence(routeDecision.requiredEvidence());
        state.setOrder(resume.order());
        state.setDelivery(resume.delivery());
        state.setIntake(intake);
        state.setRoute(routeDecision.route());
        state.getTicket().setIssueType(intake.issueType());
        state.getEvidenceIds().addAll(resume.evidenceIds());
        seedFingerprints(state, fingerprints);
        eventService.append(run.getId(), "run_resumed", "恢复上次会话", "success",
                "从等待客户补充信息的会话恢复，已还原可信证据，不重复取证。", Map.of(
                        "summary", "从等待客户补充信息的会话恢复，已还原可信证据，不重复取证。",
                        "parentRunId", parent.getId(),
                        "restoredEvidenceIds", List.copyOf(state.getEvidenceIds()),
                        "route", routeDecision.route().name()
                ));
        return true;
    }

    private AfterSalesTypes.ResumeState parseResumeState(String json) {
        try {
            return objectMapper.readValue(json, AfterSalesTypes.ResumeState.class);
        } catch (Exception error) {
            throw new IllegalStateException("PARENT_RUN_STATE_UNREADABLE");
        }
    }

    /** 恢复的已收集证据 → 对应取证工具指纹预先种入：重复执行同一工具立即失败（纵深防御）。 */
    private void seedFingerprints(AfterSalesAgentState state, Set<String> fingerprints) {
        List<String> presentTools = new ArrayList<>();
        if (state.getOrder() != null) {
            presentTools.add(AfterSalesToolExecutor.GET_ORDER_DETAIL);
        }
        if (state.getShipment() != null) {
            presentTools.add(AfterSalesToolExecutor.GET_SHIPMENT_TRACE);
        }
        if (state.getCarrierCase() != null) {
            presentTools.add(AfterSalesToolExecutor.GET_CARRIER_CASE);
        }
        if (state.getDelivery() != null) {
            presentTools.add(AfterSalesToolExecutor.GET_DELIVERY_PROOF);
        }
        if (state.getDamagePhoto() != null) {
            presentTools.add(AfterSalesToolExecutor.GET_DAMAGE_PHOTO);
        }
        if (state.getProduct() != null) {
            presentTools.add(AfterSalesToolExecutor.GET_PRODUCT);
        }
        if (state.getPolicy() != null) {
            presentTools.add(AfterSalesToolExecutor.SEARCH_POLICY);
        }
        for (String tool : presentTools) {
            Map<String, Object> arguments = toolExecutor.trustedArguments(tool, state);
            fingerprints.add(tool + ":" + arguments);
        }
    }

    /** 工单是否存在可作破损照片证据的有效图片附件（服务端持久化附件元数据，客户消息不可信）。 */
    private boolean hasValidImageAttachment(AfterSalesTicketEntity ticket) {
        return attachmentRepository.findByTicketIdOrderByCreatedAtDesc(ticket.getId()).stream()
                .anyMatch(TicketAttachmentEntity::isImage);
    }

    /** 请客户补充破损照片的安全请求文案（确定性模板，绝不拼接客户消息原文）。 */
    private static final String REQUEST_DAMAGE_PHOTO_MESSAGE =
            "To verify the damage claim, please upload a clear photo of the damaged item and its package.";

    /**
     * REQUEST_MORE_INFO 终态（缺破损照片，不是失败）：run WAITING_CUSTOMER / ticket
     * WAITING_CUSTOMER / stopReason CUSTOMER_INFO_REQUIRED；finalAnswer 含 missingInfo
     * [DAMAGE_PHOTO] 与安全客户请求（decisionRoute = REQUEST_MORE_INFO）。可恢复会话快照
     * （ORDER/DELIVERY/intake/路线/证据 ID）持久化到 resumeStateJson，供客户补充照片后的
     * 新 run 还原。发出 request_more_info + run_completed 事件，SSE 正常收尾（complete 关闭流）。
     * 不创建任何方案（没有补偿资格计算，也没有 proposal）。
     */
    private void completeRequestMoreInfo(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            AfterSalesAgentState state,
            int stepCount,
            long startedNanos) {
        long durationMs = elapsedMs(startedNanos);
        Map<String, Object> finalAnswer = baseFinalAnswer(state, ticket);
        finalAnswer.put("decisionRoute", DecisionRoute.REQUEST_MORE_INFO.name());
        finalAnswer.put("requiresApproval", false);
        finalAnswer.put("missingInfo", List.of(EvidenceType.DAMAGE_PHOTO.name()));
        finalAnswer.put("customerRequest", REQUEST_DAMAGE_PHOTO_MESSAGE);
        finalAnswer.put("decisionSummary",
                "The damage claim cannot be verified without a clear photo of the damaged item; "
                        + "the customer was asked to upload one.");
        AfterSalesTypes.ResumeState resumeState = new AfterSalesTypes.ResumeState(
                run.getId(),
                state.getOrder(),
                state.getDelivery(),
                state.getIntake(),
                state.getRoute(),
                state.getIntake().requiredEvidence(),
                List.copyOf(state.getEvidenceIds()));
        run.setStatus("WAITING_CUSTOMER");
        run.setStepCount(stepCount);
        run.setStopReason("CUSTOMER_INFO_REQUIRED");
        run.setCompletedAt(Instant.now());
        run.setDurationMs(durationMs);
        run.setFinalAnswerJson(writeJson(finalAnswer));
        run.setResumeStateJson(writeJson(resumeState));
        runRepository.save(run);

        ticket.setStatus(AfterSalesTypes.TicketStatus.WAITING_CUSTOMER);
        ticketRepository.save(ticket);

        eventService.append(run.getId(), "request_more_info", "请客户补充破损照片", "warning",
                "破损照片缺失，已请客户补充照片。", Map.of(
                        "summary", "破损照片缺失，已请客户补充照片。",
                        "missingInfo", List.of(EvidenceType.DAMAGE_PHOTO.name()),
                        "customerRequest", REQUEST_DAMAGE_PHOTO_MESSAGE,
                        "decisionRoute", DecisionRoute.REQUEST_MORE_INFO.name(),
                        "evidenceIds", List.copyOf(state.getEvidenceIds())
                ));
        eventService.append(run.getId(), "run_completed", "等待客户补充信息", "success",
                "分析完成，Agent 未执行任何副作用操作。", Map.of(
                        "summary", "破损照片缺失，已请客户补充照片，工单进入等待客户状态。",
                        "durationMs", durationMs,
                        "finalAnswer", finalAnswer
                ));
        eventService.complete(run.getId());
    }

    /**
     * 转人工升级终态（run ESCALATED / ticket ESCALATED / stopReason = 原因码）：发出
     * human_escalation + run_completed 事件并以 eventService.complete 正常收尾（绝不 error）；
     * 结构化升级原因持久化到工单 escalationReasonJson 并在 finalAnswer 输出；
     * 绝不创建任何方案（没有补偿资格计算，也没有 proposal）。
     */
    private void completeEscalated(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            AfterSalesAgentState state,
            int stepCount,
            long startedNanos,
            AfterSalesTypes.EscalationReason reason) {
        long durationMs = elapsedMs(startedNanos);
        Map<String, Object> finalAnswer = baseFinalAnswer(state, ticket);
        finalAnswer.put("decisionRoute", DecisionRoute.HUMAN_ESCALATION.name());
        finalAnswer.put("requiresApproval", false);
        finalAnswer.put("escalationReason", reason);
        finalAnswer.put("decisionSummary", reason.summary());
        run.setStatus("ESCALATED");
        run.setStepCount(stepCount);
        run.setStopReason(reason.code());
        run.setCompletedAt(Instant.now());
        run.setDurationMs(durationMs);
        run.setFinalAnswerJson(writeJson(finalAnswer));
        runRepository.save(run);

        ticket.setStatus(AfterSalesTypes.TicketStatus.ESCALATED);
        ticket.setEscalationReasonJson(writeJson(reason));
        ticketRepository.save(ticket);

        eventService.append(run.getId(), "human_escalation", "转人工处理", "warning",
                reason.summary(), Map.of(
                        "summary", reason.summary(),
                        "escalationReason", reason,
                        "code", reason.code(),
                        "category", reason.category(),
                        "evidenceIds", reason.evidenceIds(),
                        "decisionRoute", DecisionRoute.HUMAN_ESCALATION.name()
                ));
        eventService.append(run.getId(), "run_completed", "转人工处理", "success",
                "分析完成，Agent 未执行任何副作用操作。", Map.of(
                        "summary", "无法安全决策，已转人工处理，未创建任何方案。",
                        "durationMs", durationMs,
                        "finalAnswer", finalAnswer
                ));
        eventService.complete(run.getId());
    }

    /**
     * 外部等待终态（run WAITING_EXTERNAL / ticket WAITING_EXTERNAL / stopReason
     * CARRIER_INVESTIGATION_ACTIVE）：承运商调查进行中且仍在 SLA 内 —— 不是失败、不是人工
     * 升级。发出 waiting_external + run_completed 事件并以 eventService.complete 正常收尾
     * （绝不 error）；结构化外部等待原因持久化到工单 escalationReasonJson 并在 finalAnswer
     * 输出；不查政策、不计算补偿、不创建任何方案。
     */
    private void completeWaitingExternal(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            AfterSalesAgentState state,
            int stepCount,
            long startedNanos,
            AfterSalesTypes.ExternalWaitReason reason) {
        long durationMs = elapsedMs(startedNanos);
        Map<String, Object> finalAnswer = baseFinalAnswer(state, ticket);
        finalAnswer.put("decisionRoute", state.getRoute().name());
        finalAnswer.put("llmMetrics", state.getLlmBudget().snapshot());
        finalAnswer.put("requiresApproval", false);
        finalAnswer.put("externalWait", reason);
        finalAnswer.put("decisionSummary", reason.summary());
        run.setStatus("WAITING_EXTERNAL");
        run.setStepCount(stepCount);
        run.setStopReason(AfterSalesEscalationPolicyService.CODE_CARRIER_INVESTIGATION_ACTIVE);
        run.setCompletedAt(Instant.now());
        run.setDurationMs(durationMs);
        run.setFinalAnswerJson(writeJson(finalAnswer));
        runRepository.save(run);

        ticket.setStatus(AfterSalesTypes.TicketStatus.WAITING_EXTERNAL);
        ticket.setEscalationReasonJson(writeJson(reason));
        ticketRepository.save(ticket);

        eventService.append(run.getId(), "waiting_external", "等待外部调查", "warning",
                reason.summary(), Map.of(
                        "summary", reason.summary(),
                        "externalWait", reason,
                        "code", reason.code(),
                        "carrierCaseId", reason.carrierCaseId(),
                        "nextReviewAt", reason.nextReviewAt(),
                        "evidenceIds", reason.evidenceIds()
                ));
        eventService.append(run.getId(), "run_completed", "等待外部调查", "success",
                "分析完成，Agent 未执行任何副作用操作。", Map.of(
                        "summary", "承运商调查进行中且仍在 SLA 内，工单等待外部调查结果。",
                        "durationMs", durationMs,
                        "finalAnswer", finalAnswer
                ));
        eventService.complete(run.getId());
    }

    /**
     * 内部升级信号：工具执行中捕获已知可升级错误（政策未覆盖）时抛出，由 run() 的专门
     * catch 分支以正常终态转人工升级（human_escalation + run_completed + complete），
     * 绝不落入通用 error 失败分支；未知异常仍保持 FAILED。
     */
    private static final class EscalationSignal extends RuntimeException {
        private final AfterSalesTypes.EscalationReason reason;

        private EscalationSignal(AfterSalesTypes.EscalationReason reason) {
            super(reason.code());
            this.reason = reason;
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
        Map<String, Object> observations = new LinkedHashMap<>();
        if (state.getOrder() != null) { observations.put("order", state.getOrder()); }
        if (state.getShipment() != null) { observations.put("shipment", state.getShipment()); }
        if (state.getCarrierCase() != null) { observations.put("carrierCase", state.getCarrierCase()); }
        if (state.getDelivery() != null) { observations.put("delivery", state.getDelivery()); }
        if (state.getDamagePhoto() != null) { observations.put("damagePhoto", state.getDamagePhoto()); }
        if (state.getProduct() != null) { observations.put("product", state.getProduct()); }
        if (state.getPolicy() != null) { observations.put("policy", state.getPolicy()); }
        PlanningInput input = new PlanningInput(state.getIntake(), presence, observations);
        eventService.append(run.getId(), "planning_started", "规划下一步取证", "running",
                "基于已获取证据规划下一个取证步骤。", Map.of(
                        "summary", "基于已获取证据规划下一个取证步骤。",
                        "step", step,
                        "evidencePresence", presence,
                        "requiredEvidence", state.getIntake().requiredEvidence(),
                        "allowedActions", input.availableEvidence()
                ));
        long plannedAt = System.nanoTime();
        AfterSalesTypes.PlanningResult plan = plannerService.plan(input);
        String rejectReason = null;
        if ("LLM".equals(plan.source()) && !passesGate(plan, state, presence)) {
            // LLM 规划形式合法但业务前置不满足（非必需/已存在/依赖缺失/READY 证据不齐）：
            // 拒绝（LLM_INVALID_PLAN）并规则兜底，不重复调用模型。
            rejectReason = "LLM_INVALID_PLAN";
            var rejection = preconditionGate.validate(plan.nextEvidence(), state.getIntake().requiredEvidence(), presence);
            eventService.append(run.getId(), "planning_rejected", "规划被阻止", "warning",
                    "模型动作不满足业务前置条件。", Map.of(
                            "selectedAction", plan.nextEvidence().name(), "allowedActions", input.availableEvidence(),
                            "plannerSource", plan.source(), "rejectionReason", rejection.rejectionCode().name(),
                            "guardCode", rejection.rejectionCode().name(), "step", step));
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
                        "latencyMs", latencyMs,
                        "allowedActions", input.availableEvidence(),
                        "selectedAction", plan.nextEvidence().name(),
                        "plannerSource", plan.source()
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
     * 决策前置校验（路线 + 问题类型感知）：所需证据集与问题类型证据图一致，绝不跨图要求：
     * - ANSWER_ONLY：ORDER 必需；SHIPMENT_DELAY / LOST_IN_TRANSIT 另需 SHIPMENT（物流答复），
     *   DAMAGED_ITEM 另需 DELIVERY（交付证明答复）；
     * - COMPENSATION_EVALUATION：ORDER + 问题类型图全量（SHIPMENT_DELAY 图 = ORDER+SHIPMENT+
     *   POLICY；LOST_IN_TRANSIT 图再加 CARRIER_CASE；DAMAGED_ITEM 图为 ORDER+DELIVERY+
     *   DAMAGE_PHOTO+PRODUCT+POLICY）。
     * 正常流程中 Planner 的 READY 已隐含路线证据齐备（在场快照与状态同源），本校验是
     * 进入决策阶段前的纵深防御：任何不一致都以 DECISION_EVIDENCE_INCOMPLETE 干净失败。
     * package-private：同包测试直接覆盖路线/问题类型感知逻辑。
     */
    static void validateDecisionPrerequisites(AfterSalesAgentState state) {
        List<String> missing = new ArrayList<>();
        if (state.getOrder() == null) {
            missing.add(EvidenceType.ORDER.name());
        }
        String issueType = state.getTicket().getIssueType();
        boolean damaged = AfterSalesTypes.IntakeResult.DAMAGED_ITEM.equals(issueType);
        if (state.getRoute() == DecisionRoute.ANSWER_ONLY) {
            if (damaged) {
                if (state.getDelivery() == null) {
                    missing.add(EvidenceType.DELIVERY.name());
                }
            } else if (state.getShipment() == null) {
                missing.add(EvidenceType.SHIPMENT.name());
            }
        } else if (state.getRoute() == DecisionRoute.COMPENSATION_EVALUATION) {
            if (AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT.equals(issueType)) {
                if (state.getShipment() == null) {
                    missing.add(EvidenceType.SHIPMENT.name());
                }
                if (state.getCarrierCase() == null) {
                    missing.add(EvidenceType.CARRIER_CASE.name());
                }
            } else if (damaged) {
                if (state.getDelivery() == null) {
                    missing.add(EvidenceType.DELIVERY.name());
                }
                if (state.getDamagePhoto() == null) {
                    missing.add(EvidenceType.DAMAGE_PHOTO.name());
                }
                if (state.getProduct() == null) {
                    missing.add(EvidenceType.PRODUCT.name());
                }
            } else if (state.getShipment() == null) {
                missing.add(EvidenceType.SHIPMENT.name());
            }
            if (state.getPolicy() == null) {
                missing.add(EvidenceType.POLICY.name());
            }
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
                // 破损问题以交付证明答复（DAMAGED_ITEM 图无 SHIPMENT 节点），其余以物流状态答复。
                boolean deliveryAnswer = state.getDelivery() != null;
                eventService.append(run.getId(), "decision_completed", "决策完成", "success",
                        "直接答复物流状态。", Map.of(
                                "summary", deliveryAnswer
                                        ? "仅查询交付证明，已按可信交付状态直接答复，无需人工审批。"
                                        : "仅查询物流，已按可信物流状态直接答复，无需人工审批。",
                                "route", state.getRoute().name(),
                                "requiresApproval", false,
                                "answerType", deliveryAnswer ? "DELIVERY_STATUS" : "SHIPMENT_STATUS",
                                "answer", deliveryAnswer
                                        ? deliveryAnswer(state.getDelivery())
                                        : shipmentAnswer(state.getShipment()),
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
        presence.put(EvidenceType.CARRIER_CASE.name(), state.getCarrierCase() != null);
        presence.put(EvidenceType.DELIVERY.name(), state.getDelivery() != null);
        presence.put(EvidenceType.DAMAGE_PHOTO.name(), state.getDamagePhoto() != null);
        presence.put(EvidenceType.PRODUCT.name(), state.getProduct() != null);
        presence.put(EvidenceType.POLICY.name(), state.getPolicy() != null);
        return presence;
    }

    /** Java 侧证据 → 只读取证工具映射；READY_FOR_DECISION 与决策工具不在取证工具集内。 */
    private static String evidenceToolName(EvidenceType evidence) {
        return switch (evidence) {
            case ORDER -> AfterSalesToolExecutor.GET_ORDER_DETAIL;
            case SHIPMENT -> AfterSalesToolExecutor.GET_SHIPMENT_TRACE;
            case CARRIER_CASE -> AfterSalesToolExecutor.GET_CARRIER_CASE;
            case DELIVERY -> AfterSalesToolExecutor.GET_DELIVERY_PROOF;
            case DAMAGE_PHOTO -> AfterSalesToolExecutor.GET_DAMAGE_PHOTO;
            case PRODUCT -> AfterSalesToolExecutor.GET_PRODUCT;
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
        AfterSalesTypes.ToolResult result;
        try {
            result = toolExecutor.execute(action, state);
        } catch (PolicyNotFoundException error) {
            // 政策查找无适用版本（国家/问题类型/发生时间未覆盖）：不是系统失败 —— 转人工升级
            // （POLICY_NOT_COVERED），绝不回退到通用政策，也绝不以 error 收尾。
            // 只捕获这一种已知「政策未覆盖」错误；其他异常保持 FAILED。
            throw new EscalationSignal(AfterSalesEscalationPolicyService.policyNotCovered(
                    List.copyOf(state.getEvidenceIds()), Instant.now()));
        }
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
     * ANSWER_ONLY 终态：直接以可信物流状态（SHIPMENT_DELAY / LOST_IN_TRANSIT）或交付证明
     * （DAMAGED_ITEM，图无 SHIPMENT 节点）答复客户。run COMPLETED / ticket RESOLVED /
     * stopReason ANSWER_DELIVERED / requiresApproval false / answerType SHIPMENT_STATUS 或
     * DELIVERY_STATUS，只带结构化答复与证据 ID，无金额/方案字段。
     */
    private void completeAnswerOnly(
            AfterSalesRunEntity run,
            AfterSalesTicketEntity ticket,
            AfterSalesAgentState state,
            int stepCount,
            long startedNanos) {
        long durationMs = elapsedMs(startedNanos);
        Map<String, Object> finalAnswer = baseFinalAnswer(state, ticket);
        boolean deliveryAnswer = state.getDelivery() != null;
        finalAnswer.put("requiresApproval", false);
        finalAnswer.put("answerType", deliveryAnswer ? "DELIVERY_STATUS" : "SHIPMENT_STATUS");
        finalAnswer.put("answer", deliveryAnswer
                ? deliveryAnswer(state.getDelivery())
                : shipmentAnswer(state.getShipment()));
        finalAnswer.put("decisionSummary", deliveryAnswer
                ? "No refund request was classified; the ticket is answered with the trusted delivery proof "
                        + "(delivered on " + state.getDelivery().deliveredAt().toString().substring(0, 10) + ")."
                : "No refund request was classified; the ticket is answered with the trusted shipment status "
                        + "(inactive for " + state.getShipment().inactiveDays() + " days).");
        finish(run, ticket, stepCount, durationMs, finalAnswer, "ANSWER_DELIVERED",
                AfterSalesTypes.TicketStatus.RESOLVED, "直接答复物流状态",
                deliveryAnswer
                        ? "分析完成，已按可信交付证明直接答复，无需人工审批。"
                        : "分析完成，已按可信物流状态直接答复，无需人工审批。");
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
        String issueType = state.getTicket().getIssueType();
        if (eligible) {
            finalAnswer.put("action", compensation.actionType());
            finalAnswer.put("reason", compensation.reason());
            finalAnswer.put("decisionSummary", compensationSummary(issueType, true));
            finish(run, ticket, stepCount, durationMs, finalAnswer, "ACTION_PROPOSAL_CREATED",
                    AfterSalesTypes.TicketStatus.PENDING_APPROVAL, "生成待审批方案",
                    "补偿方案已生成，等待人工审批。");
        } else {
            finalAnswer.put("action", "NO_ACTION");
            finalAnswer.put("reason", compensation == null ? "COMPENSATION_NOT_CALCULATED" : compensation.reason());
            finalAnswer.put("decisionSummary", compensationSummary(issueType, false));
            finish(run, ticket, stepCount, durationMs, finalAnswer, "NO_ACTION_REQUIRED",
                    AfterSalesTypes.TicketStatus.RESOLVED, "无需动作",
                    "分析完成，未达到政策补偿条件，无需任何动作。");
        }
    }

    /** 补偿终态摘要按问题类型分派（确定性 Java 模板，无额外 LLM）。 */
    private static String compensationSummary(String issueType, boolean eligible) {
        return switch (issueType) {
            case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT -> eligible
                    ? "The carrier confirmed the parcel lost. A deterministic lost-parcel refund proposal is pending operator approval."
                    : "The carrier case is still open or below the policy threshold; no action is required.";
            case AfterSalesTypes.IntakeResult.DAMAGED_ITEM -> eligible
                    ? "The delivered item has verified damage under policy. A deterministic damage compensation proposal is pending operator approval."
                    : "The damage claim was not verified under policy; no action is required.";
            default -> eligible
                    ? "The shipment is inactive beyond policy threshold. A deterministic delay coupon proposal is pending operator approval."
                    : "The shipment does not reach the policy compensation threshold; no action is required.";
        };
    }

    /**
     * 各路线 finalAnswer 的公共字段：route 与证据始终输出；缺失的证据/方案字段不输出 null。
     * 七个证据快照按在场与否逐个输出（DAMAGED_ITEM 图没有 SHIPMENT 节点就不输出 shipment）。
     */
    private Map<String, Object> baseFinalAnswer(AfterSalesAgentState state, AfterSalesTicketEntity ticket) {
        Map<String, Object> finalAnswer = new LinkedHashMap<>();
        finalAnswer.put("ticketId", ticket.getId());
        if (state.getOrder() != null) {
            finalAnswer.put("order", state.getOrder());
        }
        if (state.getShipment() != null) {
            finalAnswer.put("shipment", state.getShipment());
        }
        if (state.getCarrierCase() != null) {
            finalAnswer.put("carrierCase", state.getCarrierCase());
        }
        if (state.getDelivery() != null) {
            finalAnswer.put("delivery", state.getDelivery());
        }
        if (state.getDamagePhoto() != null) {
            finalAnswer.put("damagePhoto", state.getDamagePhoto());
        }
        if (state.getProduct() != null) {
            finalAnswer.put("product", state.getProduct());
        }
        finalAnswer.put("evidenceIds", state.getEvidenceIds());
        finalAnswer.put("intake", state.getIntake());
        finalAnswer.put("decisionRoute", state.getRoute().name());
        finalAnswer.put("llmMetrics", state.getLlmBudget().snapshot());
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

    /** 结构化交付答复（确定性 Java 模板，无额外 LLM）：状态 + 交付时间/地点 + 签收方式。 */
    private static Map<String, Object> deliveryAnswer(AfterSalesTypes.DeliverySnapshot delivery) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("trackingNumber", delivery.trackingNumber());
        answer.put("status", delivery.status());
        answer.put("deliveredAt", delivery.deliveredAt().toString());
        answer.put("deliveredLocation", delivery.deliveredLocation());
        answer.put("recipient", delivery.recipient());
        answer.put("proofType", delivery.proofType());
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
        return "识别为" + issueTypeLabel(intake.issueType()) + "，紧急度" + urgencyLabel(intake.urgency())
                + "，决策路线：" + routeLabel(route) + "，来源：" + source + "。";
    }

    private String issueTypeLabel(String issueType) {
        return switch (issueType) {
            case AfterSalesTypes.IntakeResult.LOST_IN_TRANSIT -> "运输丢失";
            case AfterSalesTypes.IntakeResult.DAMAGED_ITEM -> "商品破损";
            case AfterSalesTypes.IntakeResult.UNSUPPORTED -> "超范围诉求";
            default -> "物流延迟";
        };
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
            case AfterSalesToolExecutor.GET_CARRIER_CASE -> "物流已长期未更新，查询承运商理赔案件与调查结论。";
            case AfterSalesToolExecutor.GET_DELIVERY_PROOF -> "订单已验证，查询可信派送证明和签收状态。";
            case AfterSalesToolExecutor.GET_DAMAGE_PHOTO -> "交付已确认，核验破损照片与人工核验结论。";
            case AfterSalesToolExecutor.GET_PRODUCT -> "破损已核验，查询受损商品资料与保障范围。";
            case AfterSalesToolExecutor.SEARCH_POLICY -> "已获取问题类型证据，检索订单国家和发生时间对应的政策版本。";
            case AfterSalesToolExecutor.CALCULATE_COMPENSATION -> "已获取适用政策，由 Java 规则按问题类型计算补偿金额。";
            case AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL -> "所需证据已获取且金额计算完成，生成待人工审批方案。";
            default -> "执行受限售后工具。";
        };
    }

    private String toolLabel(String action) {
        return switch (action) {
            case AfterSalesToolExecutor.GET_ORDER_DETAIL -> "核验订单";
            case AfterSalesToolExecutor.GET_SHIPMENT_TRACE -> "查询物流";
            case AfterSalesToolExecutor.GET_CARRIER_CASE -> "查询承运商案件";
            case AfterSalesToolExecutor.GET_DELIVERY_PROOF -> "查询派送证明";
            case AfterSalesToolExecutor.GET_DAMAGE_PHOTO -> "核验破损照片";
            case AfterSalesToolExecutor.GET_PRODUCT -> "查询受损商品";
            case AfterSalesToolExecutor.SEARCH_POLICY -> "检索售后政策";
            case AfterSalesToolExecutor.CALCULATE_COMPENSATION -> "计算补偿";
            case AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL -> "创建处理方案";
            default -> action;
        };
    }

    private String eventType(String action) {
        // 只读取证检索（政策/承运商案件/派送证明/破损照片/商品）统一为 retrieval_completed。
        return switch (action) {
            case AfterSalesToolExecutor.SEARCH_POLICY,
                    AfterSalesToolExecutor.GET_CARRIER_CASE,
                    AfterSalesToolExecutor.GET_DELIVERY_PROOF,
                    AfterSalesToolExecutor.GET_DAMAGE_PHOTO,
                    AfterSalesToolExecutor.GET_PRODUCT -> "retrieval_completed";
            default -> "tool_completed";
        };
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
