package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.*;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.math.BigDecimal;
import java.util.Optional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class ExecutionApprovalValidatorTest {
    @Test
    void unchangedApprovedCommandPasses() {
        Fixture f = new Fixture();
        assertThat(f.validator.rejection(f.job, f.ticket, ApprovalTestSupport.order())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"legacy", "amount", "currency", "action", "version", "evidence", "order", "run", "revoked", "policy", "owner"})
    void changesAfterApprovalFailClosed(String change) {
        Fixture f = new Fixture();
        switch (change) {
            case "legacy" -> f.job.setApprovalId(null);
            case "amount" -> f.job.setAmount(BigDecimal.ONE);
            case "currency" -> f.job.setCurrency("USD");
            case "action" -> f.job.setActionType("REFUND");
            case "version" -> f.proposal.setProposalVersion("v2");
            case "evidence" -> f.proposal.setEvidenceIdsJson("[]");
            case "order" -> f.ticket.setOrderId("O-SG-1001");
            case "run" -> f.ticket.setCurrentRunId("run-other");
            case "revoked" -> f.proposal.setStatus(AfterSalesTypes.ProposalStatus.REJECTED);
            case "policy" -> f.proposal.setPolicyVersion("v0");
            case "owner" -> f.ticket.setUserId("other-user");
            default -> throw new AssertionError(change);
        }
        assertThat(f.validator.rejection(f.job, f.ticket, ApprovalTestSupport.order())).isNotBlank();
    }

    private static final class Fixture {
        final ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        final AfterSalesTicketEntity ticket = ApprovalTestSupport.ticket();
        final ExecutionJobEntity job;
        final ExecutionApprovalValidator validator;
        Fixture() {
            proposal.setStatus(AfterSalesTypes.ProposalStatus.APPROVED);
            ApprovalRecordEntity approval = ApprovalRecordEntity.builder()
                    .id("approval").proposalId(proposal.getId()).decision("APPROVED")
                    .runId(ticket.getCurrentRunId()).orderId(ticket.getOrderId())
                    .userId(ApprovalTestSupport.order().userId())
                    .amount(proposal.getAmount()).paidAmount(ApprovalTestSupport.order().paidAmount())
                    .currency(proposal.getCurrency()).actionType(proposal.getActionType())
                    .policyVersion(proposal.getPolicyVersion()).proposalVersion(proposal.getProposalVersion())
                    .evidenceIdsJson(proposal.getEvidenceIdsJson()).build();
            job = ExecutionJobEntity.builder().id("job").approvalId("approval")
                    .proposalId(proposal.getId()).ticketId(ticket.getId()).idempotencyKey(ApprovalService.idempotencyKey(proposal))
                    .actionType(proposal.getActionType()).amount(proposal.getAmount()).currency(proposal.getCurrency()).build();
            ActionProposalRepository proposals = mock(ActionProposalRepository.class);
            ApprovalRecordRepository approvals = mock(ApprovalRecordRepository.class);
            when(proposals.findById(proposal.getId())).thenReturn(Optional.of(proposal));
            when(approvals.findById("approval")).thenReturn(Optional.of(approval));
            validator = new ExecutionApprovalValidator(proposals, approvals, new DemoAfterSalesPolicyCatalogService());
        }
    }
}
