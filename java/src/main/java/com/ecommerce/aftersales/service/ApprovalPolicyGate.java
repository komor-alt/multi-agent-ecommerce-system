package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审批策略 Gate：对 ApprovalService 已用 findByIdForUpdate 锁定的 ActionProposalEntity
 * 做服务端「可审批性」复核（状态机 / 运行终态 / 证据完整性 / 政策与金额重算）。
 * 本 Gate 自身绝不重新锁定 Proposal，也不写任何状态；所有复核数据都来自工单 / 运行 /
 * 最终答复 / 政策目录 / 规则引擎的服务端可信来源，模型与提案内容本身都不可信。
 *
 * 复核顺序（任一失败抛 ApprovalPolicyViolationException，approve 事务整体回滚）：
 * 1. proposal PENDING；
 * 2. ticket PENDING_APPROVAL；
 * 3. ticket.currentRunId 对应 run COMPLETED 且 stopReason=ACTION_PROPOSAL_CREATED 且 run.ticketId 匹配；
 * 4. 解析 run.finalAnswerJson 为可信 OrderSnapshot / ShipmentSnapshot / PolicyEvidence / evidenceIds；
 *    evidenceIds 必须是 JSON 字符串数组（非空元素），否则 FINAL_ANSWER_INVALID；
 * 5. 可信 Run 证据必须含 order:/shipment:/policy:/calculation: 四类证据（APPROVAL_EVIDENCE_INCOMPLETE）；
 * 6. proposal.evidenceIdsJson 解析为字符串数组后，与可信 Run 证据按集合语义（去重）
 *    完全相等（APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH）；Proposal 自身不是可信事实来源；
 * 7. proposal.policyVersion = finalAnswer policy.version；
 * 8. policy.country=order.country、policy.issueType=ticket.issueType、
 *    policy.effectiveFrom 不晚于 ticket.createdAt（政策上下文一致）；
 * 9. DemoAfterSalesPolicyCatalogService.lookup(order.country, ticket.issueType, ticket.createdAt)
 *    重新匹配历史版本，匹配版本必须与 proposal 一致；
 * 10. CompensationRuleService.calculate(order, shipment, policy) 重算：eligible=true，
 *    amount 以 compareTo 相等、currency/actionType 精确一致。
 */
@Service
public class ApprovalPolicyGate {

    /** 提案证据必须覆盖的四类前缀：订单 / 物流 / 政策 / 计算。 */
    private static final List<String> REQUIRED_EVIDENCE_PREFIXES = List.of(
            "order:", "shipment:", "policy:", "calculation:");

    private final AfterSalesTicketRepository ticketRepository;
    private final AfterSalesRunRepository runRepository;
    private final DemoAfterSalesPolicyCatalogService policyCatalog;
    private final CompensationRuleService compensationRuleService;
    private final ObjectMapper objectMapper;

    public ApprovalPolicyGate(
            AfterSalesTicketRepository ticketRepository,
            AfterSalesRunRepository runRepository,
            DemoAfterSalesPolicyCatalogService policyCatalog,
            CompensationRuleService compensationRuleService,
            ObjectMapper objectMapper) {
        this.ticketRepository = ticketRepository;
        this.runRepository = runRepository;
        this.policyCatalog = policyCatalog;
        this.compensationRuleService = compensationRuleService;
        this.objectMapper = objectMapper;
    }

    public ApprovalValidationResult validate(ActionProposalEntity proposal) {
        requirePending(proposal);
        AfterSalesTicketEntity ticket = loadTicket(proposal);
        requireTicketState(ticket);
        AfterSalesRunEntity run = loadRun(ticket);
        requireRunState(run, proposal);
        FinalAnswer finalAnswer = parseFinalAnswer(run);
        requireTrustedEvidenceComplete(finalAnswer.evidenceIds);
        requireSnapshotEquality(proposal, finalAnswer.evidenceIds);
        requirePolicyVersion(proposal, finalAnswer.policy);
        requirePolicyContext(finalAnswer.policy, finalAnswer.order, ticket);
        requireRelookupVersion(proposal, finalAnswer.order, ticket);
        AfterSalesTypes.CompensationResult recomputed = compensationRuleService.calculate(
                finalAnswer.order, finalAnswer.shipment, finalAnswer.policy);
        requireCompensation(proposal, recomputed);
        return new ApprovalValidationResult(
                proposal.getId(), finalAnswer.order, finalAnswer.shipment, finalAnswer.policy, recomputed);
    }

    private static void requirePending(ActionProposalEntity proposal) {
        if (proposal.getStatus() != AfterSalesTypes.ProposalStatus.PENDING) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_PROPOSAL_STATE_INVALID,
                    "Proposal is not in PENDING state and cannot be approved.",
                    "方案不在待审批状态，无法批准。");
        }
    }

    private AfterSalesTicketEntity loadTicket(ActionProposalEntity proposal) {
        return ticketRepository.findById(proposal.getTicketId())
                .orElseThrow(() -> new ApprovalPolicyViolationException(
                        ApprovalPolicyViolationException.APPROVAL_TICKET_STATE_INVALID,
                        "Ticket for the proposal was not found.",
                        "方案对应的工单不存在。"));
    }

    private static void requireTicketState(AfterSalesTicketEntity ticket) {
        if (ticket.getStatus() != AfterSalesTypes.TicketStatus.PENDING_APPROVAL) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_TICKET_STATE_INVALID,
                    "Ticket is not waiting for approval (PENDING_APPROVAL).",
                    "工单不在待审批状态（PENDING_APPROVAL）。");
        }
    }

    private AfterSalesRunEntity loadRun(AfterSalesTicketEntity ticket) {
        if (ticket.getCurrentRunId() == null) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_RUN_STATE_INVALID,
                    "Ticket has no current run to verify.",
                    "工单没有可核验的运行记录。");
        }
        return runRepository.findById(ticket.getCurrentRunId())
                .orElseThrow(() -> new ApprovalPolicyViolationException(
                        ApprovalPolicyViolationException.APPROVAL_RUN_STATE_INVALID,
                        "Run referenced by the ticket was not found.",
                        "工单引用的运行记录不存在。"));
    }

    private static void requireRunState(AfterSalesRunEntity run, ActionProposalEntity proposal) {
        if (!"COMPLETED".equals(run.getStatus())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_RUN_STATE_INVALID,
                    "Run is not COMPLETED and cannot back an approval.",
                    "运行记录未完成，不能作为审批依据。");
        }
        if (!"ACTION_PROPOSAL_CREATED".equals(run.getStopReason())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_RUN_STATE_INVALID,
                    "Run did not end with an action proposal (ACTION_PROPOSAL_CREATED).",
                    "运行终态不是生成待审批方案（ACTION_PROPOSAL_CREATED）。");
        }
        if (!run.getTicketId().equals(proposal.getTicketId())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_RUN_STATE_INVALID,
                    "Run ticket does not match the proposal ticket.",
                    "运行记录与方案不属于同一工单。");
        }
    }

    /** 可信 Run 最终答复中的证据必须覆盖四类前缀；缺失 → APPROVAL_EVIDENCE_INCOMPLETE。 */
    private static void requireTrustedEvidenceComplete(List<String> trustedEvidenceIds) {
        for (String prefix : REQUIRED_EVIDENCE_PREFIXES) {
            if (trustedEvidenceIds.stream().noneMatch(id -> id.startsWith(prefix))) {
                throw new ApprovalPolicyViolationException(
                        ApprovalPolicyViolationException.APPROVAL_EVIDENCE_INCOMPLETE,
                        "Trusted run evidence is incomplete (missing " + prefix + " evidence).",
                        "可信运行证据不完整（缺少 " + prefix + " 证据）。");
            }
        }
    }

    /**
     * Proposal 证据快照必须与可信 Run 证据完全一致：两者均按集合语义归一化
     * （去重 / 忽略顺序）后比较；Proposal 缺证据、多出伪造证据或替换证据 ID 均拒绝。
     */
    private void requireSnapshotEquality(ActionProposalEntity proposal, List<String> trustedEvidenceIds) {
        Set<String> trustedEvidence = new HashSet<>(trustedEvidenceIds);
        Set<String> proposalEvidence = readProposalEvidenceIds(proposal);
        if (!trustedEvidence.equals(proposalEvidence)) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH,
                    "Proposal evidence snapshot does not match the trusted run evidence.",
                    "方案证据快照与可信运行证据不一致。");
        }
    }

    /**
     * 解析提案证据快照为字符串数组；不可解析（空/非法 JSON）、非数组或含非字符串元素，
     * 一律按证据不完整处理 —— 无法与可信 Run 证据比较的提案快照不得参与审批。
     */
    private Set<String> readProposalEvidenceIds(ActionProposalEntity proposal) {
        try {
            List<?> elements = objectMapper.readValue(
                    proposal.getEvidenceIdsJson(), new TypeReference<List<Object>>() {
                    });
            if (elements == null) {
                return Set.of();
            }
            Set<String> evidenceIds = new HashSet<>();
            for (Object element : elements) {
                if (!(element instanceof String id)) {
                    throw new ApprovalPolicyViolationException(
                            ApprovalPolicyViolationException.APPROVAL_EVIDENCE_INCOMPLETE,
                            "Proposal evidence snapshot is not a string array.",
                            "方案证据快照不是字符串数组。");
                }
                evidenceIds.add(id);
            }
            return evidenceIds;
        } catch (ApprovalPolicyViolationException error) {
            throw error;
        } catch (Exception error) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_EVIDENCE_INCOMPLETE,
                    "Proposal evidence snapshot is missing or unreadable.",
                    "方案证据快照缺失或无法解析。");
        }
    }

    /** 解析最终答复中的可信快照；缺失或不可解析 → FINAL_ANSWER_INVALID。 */
    private FinalAnswer parseFinalAnswer(AfterSalesRunEntity run) {
        if (run.getFinalAnswerJson() == null || run.getFinalAnswerJson().isBlank()) {
            throw invalidFinalAnswer();
        }
        try {
            Map<String, Object> finalAnswer = objectMapper.readValue(
                    run.getFinalAnswerJson(), new TypeReference<>() {
                    });
            if (finalAnswer == null) {
                throw invalidFinalAnswer();
            }
            return new FinalAnswer(
                    parseSnapshot(finalAnswer, "order", AfterSalesTypes.OrderSnapshot.class),
                    parseSnapshot(finalAnswer, "shipment", AfterSalesTypes.ShipmentSnapshot.class),
                    parseSnapshot(finalAnswer, "policy", AfterSalesTypes.PolicyEvidence.class),
                    parseEvidenceIds(finalAnswer));
        } catch (ApprovalPolicyViolationException error) {
            throw error;
        } catch (Exception error) {
            throw invalidFinalAnswer();
        }
    }

    /**
     * 解析可信 evidenceIds：必须是非 null 的 JSON 字符串数组，且每个元素都是非空白字符串；
     * 缺失 / 非数组 / 含非字符串 / 含 null / 空白字符串 → FINAL_ANSWER_INVALID。
     */
    private static List<String> parseEvidenceIds(Map<String, Object> finalAnswer) {
        Object raw = finalAnswer.get("evidenceIds");
        if (!(raw instanceof List<?> list)) {
            throw invalidFinalAnswer();
        }
        List<String> evidenceIds = new ArrayList<>(list.size());
        for (Object element : list) {
            if (!(element instanceof String id) || id.isBlank()) {
                throw invalidFinalAnswer();
            }
            evidenceIds.add(id);
        }
        return List.copyOf(evidenceIds);
    }

    private <T> T parseSnapshot(Map<String, Object> finalAnswer, String key, Class<T> type) {
        Object value = finalAnswer.get(key);
        if (value == null) {
            throw invalidFinalAnswer();
        }
        try {
            return objectMapper.convertValue(value, type);
        } catch (Exception error) {
            throw invalidFinalAnswer();
        }
    }

    private static ApprovalPolicyViolationException invalidFinalAnswer() {
        return new ApprovalPolicyViolationException(
                ApprovalPolicyViolationException.FINAL_ANSWER_INVALID,
                "Trusted final answer from the run is missing or unreadable.",
                "运行最终答复缺失或无法解析。");
    }

    private static void requirePolicyVersion(ActionProposalEntity proposal, AfterSalesTypes.PolicyEvidence policy) {
        if (!proposal.getPolicyVersion().equals(policy.version())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_POLICY_VERSION_MISMATCH,
                    "Proposal policy version does not match the trusted policy evidence version.",
                    "方案政策版本与可信政策证据版本不一致。");
        }
    }

    private static void requirePolicyContext(
            AfterSalesTypes.PolicyEvidence policy,
            AfterSalesTypes.OrderSnapshot order,
            AfterSalesTicketEntity ticket) {
        if (!policy.country().equals(order.country())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.POLICY_CONTEXT_MISMATCH,
                    "Policy country does not match the order country.",
                    "政策国家与订单国家不一致。");
        }
        if (!policy.issueType().equals(ticket.getIssueType())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.POLICY_CONTEXT_MISMATCH,
                    "Policy issue type does not match the ticket issue type.",
                    "政策问题类型与工单问题类型不一致。");
        }
        if (policy.effectiveFrom().isAfter(ticket.getCreatedAt())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.POLICY_CONTEXT_MISMATCH,
                    "Policy effective date is after the ticket creation time.",
                    "政策生效时间晚于工单创建时间。");
        }
    }

    /** 用可信键在政策目录中重新匹配 ticket.createdAt 时刻的历史版本，必须与提案版本一致。 */
    private void requireRelookupVersion(
            ActionProposalEntity proposal,
            AfterSalesTypes.OrderSnapshot order,
            AfterSalesTicketEntity ticket) {
        AfterSalesTypes.PolicyEvidence matched;
        try {
            matched = policyCatalog.lookup(order.country(), ticket.getIssueType(), ticket.getCreatedAt());
        } catch (PolicyNotFoundException error) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.POLICY_CONTEXT_MISMATCH,
                    "No policy version could be re-matched from the policy catalog for the ticket context.",
                    "政策目录无法按工单上下文重新匹配政策版本。");
        }
        if (!matched.version().equals(proposal.getPolicyVersion())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_POLICY_VERSION_MISMATCH,
                    "Re-matched policy version does not match the proposal policy version.",
                    "重新匹配的历史政策版本与方案政策版本不一致。");
        }
    }

    private static void requireCompensation(
            ActionProposalEntity proposal,
            AfterSalesTypes.CompensationResult recomputed) {
        if (!recomputed.eligible()) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_COMPENSATION_NOT_ELIGIBLE,
                    "Recomputed compensation is not eligible; proposal cannot be approved.",
                    "规则重算补偿不可用，方案不能批准。");
        }
        if (recomputed.amount().compareTo(proposal.getAmount()) != 0) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_AMOUNT_MISMATCH,
                    "Proposal amount does not match the recomputed compensation amount.",
                    "方案金额与规则重算金额不一致。");
        }
        if (!recomputed.currency().equals(proposal.getCurrency())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_CURRENCY_MISMATCH,
                    "Proposal currency does not match the recomputed compensation currency.",
                    "方案币种与规则重算币种不一致。");
        }
        if (!recomputed.actionType().equals(proposal.getActionType())) {
            throw new ApprovalPolicyViolationException(
                    ApprovalPolicyViolationException.APPROVAL_ACTION_MISMATCH,
                    "Proposal action type does not match the recomputed action type.",
                    "方案动作类型与规则重算动作类型不一致。");
        }
    }

    /** finalAnswer 中必须同时存在的三份可信快照 + 可信证据清单（evidenceIds）。 */
    private record FinalAnswer(
            AfterSalesTypes.OrderSnapshot order,
            AfterSalesTypes.ShipmentSnapshot shipment,
            AfterSalesTypes.PolicyEvidence policy,
            List<String> evidenceIds) {
    }
}
