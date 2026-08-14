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
    // TODO: move server-side approval policy checks into ApprovalPolicyGate.
    // 当前审批的服务端策略检查（状态机/幂等/执行任务派生）内联在本服务中；后续应抽到独立的
    // ApprovalPolicyGate 统一收敛「可审批性」判定，本服务只负责状态流转与记录落库。本轮不重构。
    private final ActionProposalRepository proposalRepository;
    private final ApprovalRecordRepository approvalRecordRepository;
    private final ExecutionJobRepository executionJobRepository;

    public ApprovalService(
            ActionProposalRepository proposalRepository,
            ApprovalRecordRepository approvalRecordRepository,
            ExecutionJobRepository executionJobRepository) {
        this.proposalRepository = proposalRepository;
        this.approvalRecordRepository = approvalRecordRepository;
        this.executionJobRepository = executionJobRepository;
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

        proposal.setStatus(AfterSalesTypes.ProposalStatus.APPROVED);
        proposal.setReviewedBy(operatorId);
        proposal.setReviewComment(comment);
        proposal.setReviewedAt(Instant.now());
        proposalRepository.save(proposal);
        saveApprovalRecord(proposalId, "APPROVED", operatorId, comment);

        String key = idempotencyKey(proposal);
        ExecutionJobEntity job = executionJobRepository.findByIdempotencyKey(key)
                .orElseGet(() -> executionJobRepository.save(ExecutionJobEntity.builder()
                        .id(UUID.randomUUID().toString())
                        .proposalId(proposal.getId())
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

    private String idempotencyKey(ActionProposalEntity proposal) {
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


