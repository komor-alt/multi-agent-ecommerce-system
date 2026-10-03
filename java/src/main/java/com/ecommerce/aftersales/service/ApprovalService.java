package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.ApprovalRecordEntity;
import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.ApprovalRecordRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class ApprovalService {
    // 审批的服务端「可审批性」判定收敛在 ApprovalPolicyGate（状态机/运行终态/证据/政策/金额重算）；
    // 本服务只负责状态流转与记录落库：Gate 通过后才写 APPROVED / ApprovalRecord / ExecutionJob。
    private final ActionProposalRepository proposalRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final ExecutionJobRepository executionJobRepository;
    private final ApprovalPolicyGate approvalPolicyGate;

    public ApprovalService(
            ActionProposalRepository proposalRepository,
            ApprovalRecordRepository approvalRecordRepository,
            ExecutionJobRepository executionJobRepository,
            ApprovalPolicyGate approvalPolicyGate) {
        this.proposalRepository = proposalRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.executionJobRepository = executionJobRepository;
        this.approvalPolicyGate = approvalPolicyGate;
    }

    @Transactional
    public ApprovalOutcome approve(String proposalId, String operatorId, String comment) {
        ActionProposalEntity proposal = proposalRepository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("PROPOSAL_NOT_FOUND"));

        if (proposal.getStatus() == AfterSalesTypes.ProposalStatus.APPROVED) {
            ExecutionJobEntity existingJob = executionJobRepository.findByProposalId(proposalId)
                    .orElseThrow(() -> new IllegalStateException("APPROVED_PROPOSAL_HAS_NO_JOB"));
            return new ApprovalOutcome(existingJob, false);
        }
        if (proposal.getStatus() != AfterSalesTypes.ProposalStatus.PENDING) {
            throw new IllegalStateException("PROPOSAL_ALREADY_PROCESSED");
        }

        // 服务端可审批性复核（Gate 不重新锁 Proposal）：失败抛 ApprovalPolicyViolationException，
        // 事务整体回滚 —— 绝不创建 ApprovalRecord 或 ExecutionJob，proposal 保持 PENDING。
        ApprovalValidationResult validated = approvalPolicyGate.validate(proposal);

        proposal.setStatus(AfterSalesTypes.ProposalStatus.APPROVED);
        proposal.setReviewedBy(operatorId);
        proposal.setReviewComment(comment);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);
        ApprovalRecordEntity approval = ApprovalRecordEntity.builder()
                .id(UUID.randomUUID().toString()).proposalId(proposalId).decision("APPROVED")
                .operatorId(operatorId).comment(comment).runId(validated.runId())
                .orderId(validated.order().orderId()).userId(validated.order().userId())
                .paidAmount(validated.order().paidAmount()).actionType(proposal.getActionType())
                .amount(proposal.getAmount()).currency(proposal.getCurrency())
                .policyVersion(proposal.getPolicyVersion()).proposalVersion(proposal.getProposalVersion())
                .evidenceIdsJson(proposal.getEvidenceIdsJson()).build();
        approvalRecordRepository.save(approval);

        String key = idempotencyKey(proposal);
        ExecutionJobEntity job = executionJobRepository.findByIdempotencyKey(key)
                .orElseGet(() -> executionJobRepository.save(ExecutionJobEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .proposalId(proposal.getId())
                        .approvalId(approval.getId())
                        .ticketId(proposal.getTicketId())
                        .idempotencyKey(key)
                        .actionType(proposal.getActionType())
                        .amount(proposal.getAmount())
                        .currency(proposal.getCurrency())
                        .status(AfterSalesTypes.ExecutionStatus.PENDING)
                        .attemptCount(0)
                        .build()));
        return new ApprovalOutcome(job, true);
    }

    @Transactional
    public ActionProposalEntity reject(String proposalId, String operatorId, String comment) {
        ActionProposalEntity proposal = proposalRepository.findByIdForUpdate(proposalId)
                .orElseThrow(() -> new IllegalArgumentException("PROPOSAL_NOT_FOUND"));
        if (proposal.getStatus() == AfterSalesTypes.ProposalStatus.REJECTED) {
            return proposal;
        }
        if (proposal.getStatus() != AfterSalesTypes.ProposalStatus.PENDING) {
            throw new IllegalStateException("PROPOSAL_ALREADY_PROCESSED");
        }
        proposal.setStatus(AfterSalesTypes.ProposalStatus.REJECTED);
        proposal.setReviewedBy(operatorId);
        proposal.setReviewComment(comment);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);
        saveApprovalRecord(proposalId, "REJECTED", operatorId, comment);
        return proposal;
    }

    private void saveApprovalRecord(String proposalId, String decision, String operatorId, String comment) {
        approvalRecordRepository.save(ApprovalRecordEntity.builder()
                .id(UUID.randomUUID().toString())
                .proposalId(proposalId)
                .decision(decision)
                .operatorId(operatorId)
                .comment(comment)
                .build());
    }

    public record ApprovalOutcome(ExecutionJobEntity job, boolean newlyApproved) {
    }

    static String idempotencyKey(ActionProposalEntity proposal) {
        try {
            String source = proposal.getId() + ":" + proposal.getActionType() + ":" + proposal.getProposalVersion();
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("IDEMPOTENCY_KEY_FAILED", error);
        }
    }
}


