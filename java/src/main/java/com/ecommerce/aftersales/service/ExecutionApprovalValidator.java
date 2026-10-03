package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.*;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.*;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Objects;

/** Fail closed for legacy jobs without an approval snapshot; they require human re-review. */
@Service
public class ExecutionApprovalValidator {
    private final ActionProposalRepository proposals;
    private final ApprovalRecordRepository approvals;
    private final DemoAfterSalesPolicyCatalogService policies;

    public ExecutionApprovalValidator(ActionProposalRepository proposals, ApprovalRecordRepository approvals,
                                      DemoAfterSalesPolicyCatalogService policies) {
        this.proposals = proposals;
        this.approvals = approvals;
        this.policies = policies;
    }

    /** Returns a stable, non-sensitive guard code, or null when execution is authorized. */
    public String rejection(ExecutionJobEntity job, AfterSalesTicketEntity ticket, AfterSalesTypes.OrderSnapshot order) {
        if (job.getApprovalId() == null) return "APPROVAL_SNAPSHOT_MISSING";
        ApprovalRecordEntity approval = approvals.findById(job.getApprovalId()).orElse(null);
        ActionProposalEntity proposal = proposals.findById(job.getProposalId()).orElse(null);
        if (approval == null || proposal == null || !"APPROVED".equals(approval.getDecision())
                || proposal.getStatus() != AfterSalesTypes.ProposalStatus.APPROVED) return "APPROVAL_INVALID";
        if (!Objects.equals(job.getIdempotencyKey(), ApprovalService.idempotencyKey(proposal)))
            return "EXECUTION_IDEMPOTENCY_KEY_CHANGED";
        if (!Objects.equals(approval.getProposalId(), proposal.getId())
                || !Objects.equals(proposal.getTicketId(), ticket.getId())
                || !Objects.equals(job.getTicketId(), ticket.getId())
                || !Objects.equals(approval.getRunId(), ticket.getCurrentRunId())) return "APPROVAL_BINDING_CHANGED";
        if (!Objects.equals(approval.getOrderId(), ticket.getOrderId())
                || !Objects.equals(approval.getOrderId(), order.orderId())
                || !Objects.equals(approval.getUserId(), order.userId())
                || (ticket.getUserId() != null && !Objects.equals(ticket.getUserId(), order.userId())))
            return "EXECUTION_ENTITY_MISMATCH";
        if (!equalAmount(approval.getAmount(), proposal.getAmount())
                || !equalAmount(approval.getAmount(), job.getAmount())
                || !Objects.equals(approval.getCurrency(), proposal.getCurrency())
                || !Objects.equals(approval.getCurrency(), job.getCurrency())
                || !Objects.equals(approval.getCurrency(), order.currency())
                || !equalAmount(approval.getPaidAmount(), order.paidAmount())) return "APPROVAL_AMOUNT_CHANGED";
        if (!Objects.equals(approval.getActionType(), proposal.getActionType())
                || !Objects.equals(approval.getActionType(), job.getActionType())
                || !Objects.equals(approval.getProposalVersion(), proposal.getProposalVersion())
                || !Objects.equals(approval.getEvidenceIdsJson(), proposal.getEvidenceIdsJson()))
            return "APPROVAL_PROPOSAL_CHANGED";
        try {
            AfterSalesTypes.PolicyEvidence policy = policies.lookup(order.country(), ticket.getIssueType(), ticket.getCreatedAt());
            if (!Objects.equals(approval.getPolicyVersion(), proposal.getPolicyVersion())
                    || !Objects.equals(approval.getPolicyVersion(), policy.version())
                    || !Objects.equals(approval.getActionType(), policy.actionType())) return "APPROVAL_POLICY_CHANGED";
        } catch (PolicyNotFoundException error) {
            return "APPROVAL_POLICY_CHANGED";
        }
        return null;
    }

    private static boolean equalAmount(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }
}
