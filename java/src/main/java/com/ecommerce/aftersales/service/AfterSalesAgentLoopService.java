package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesAgentState;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final ObjectMapper objectMapper;
    private final long stepDelayMs;

    public AfterSalesAgentLoopService(
            AfterSalesToolExecutor toolExecutor,
            AfterSalesRunEventService eventService,
            AfterSalesRunRepository runRepository,
            AfterSalesTicketRepository ticketRepository,
            AfterSalesTicketContextService ticketContextService,
            AfterSalesIntakeService intakeService,
            ObjectMapper objectMapper,
            @Value("${agent.aftersales.demo-step-delay-ms:0}") long stepDelayMs) {
        this.toolExecutor = toolExecutor;
        this.eventService = eventService;
        this.runRepository = runRepository;
        this.ticketRepository = ticketRepository;
        this.ticketContextService = ticketContextService;
        this.intakeService = intakeService;
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

        // Intake：结构化分类先于工具循环执行。不进入工具循环，不计入工具 stepCount；
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

            for (int step = 1; step <= run.getMaxSteps(); step++) {
                String action = nextAction(state);
                if (action == null) {
                    complete(run, ticket, state, step - 1, startedNanos);
                    return;
                }
                if (!TOOL_WHITELIST.contains(action)) {
                    throw new IllegalStateException("TOOL_NOT_WHITELISTED");
                }

                Map<String, Object> arguments = toolExecutor.trustedArguments(action, state);
                String fingerprint = action + ":" + arguments;
                if (!fingerprints.add(fingerprint)) {
                    throw new IllegalStateException("DUPLICATE_TOOL_CALL");
                }

                eventService.append(runId, "tool_started", toolLabel(action), "running",
                        decisionSummary(action), Map.of(
                                "summary", decisionSummary(action),
                                "action", action,
                                "arguments", arguments,
                                "step", step
                        ));

                pauseBetweenSteps();
                long startedAt = System.nanoTime();
                AfterSalesTypes.ToolResult result = toolExecutor.execute(action, state);
                long latencyMs = (System.nanoTime() - startedAt) / 1_000_000;

                eventService.append(runId, eventType(action), toolLabel(action), "success",
                        result.summary(), Map.of(
                                "summary", result.summary(),
                                "action", action,
                                "observation", result.data(),
                                "evidenceIds", result.evidenceIds(),
                                "latencyMs", latencyMs,
                                "step", step
                        ));
                run.setStepCount(step);
                runRepository.save(run);
            }
            throw new IllegalStateException("MAX_STEPS_EXCEEDED");
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

    private String nextAction(AfterSalesAgentState state) {
        if (state.getOrder() == null) {
            return AfterSalesToolExecutor.GET_ORDER_DETAIL;
        }
        if (state.getShipment() == null) {
            return AfterSalesToolExecutor.GET_SHIPMENT_TRACE;
        }
        if (state.getPolicy() == null) {
            return AfterSalesToolExecutor.SEARCH_POLICY;
        }
        if (state.getCompensation() == null) {
            return AfterSalesToolExecutor.CALCULATE_COMPENSATION;
        }
        if (state.getProposalId() == null) {
            return AfterSalesToolExecutor.CREATE_ACTION_PROPOSAL;
        }
        return null;
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


