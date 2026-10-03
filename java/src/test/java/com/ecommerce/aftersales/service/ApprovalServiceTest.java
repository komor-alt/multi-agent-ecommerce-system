package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.ApprovalRecordRepository;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ApprovalService 集成测试：真实 ApprovalPolicyGate（mock 仓库 + 真实政策目录/规则引擎）。
 * 覆盖：Gate 通过才创建 ExecutionJob/ApprovalRecord；篡改 amount/currency/actionType/
 * policyVersion/evidence 任一都拒绝且绝不创建 ExecutionJob/ApprovalRecord；ticket/run 状态
 * 非法与补偿不 eligible 拒绝；重复 approve 幂等返回既有 job。
 */
class ApprovalServiceTest {

    @Test
    void approveCreatesJobAndApprovalRecordWhenGatePasses() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        Harness harness = new Harness(proposal);

        ApprovalService.ApprovalOutcome outcome = harness.service.approve("proposal-1", "operator-1", "approved");

        assertThat(outcome.newlyApproved()).isTrue();
        assertThat(outcome.job().getProposalId()).isEqualTo("proposal-1");
        assertThat(outcome.job().getTicketId()).isEqualTo("ticket-1");
        assertThat(outcome.job().getActionType()).isEqualTo("DELAY_COMPENSATION_COUPON");
        assertThat(outcome.job().getAmount()).isEqualByComparingTo("150000.00");
        assertThat(outcome.job().getCurrency()).isEqualTo("VND");
        assertThat(proposal.getStatus()).isEqualTo(AfterSalesTypes.ProposalStatus.APPROVED);
        assertThat(proposal.getReviewedBy()).isEqualTo("operator-1");
        ArgumentCaptor<ExecutionJobEntity> captor = ArgumentCaptor.forClass(ExecutionJobEntity.class);
        verify(harness.executionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).hasSize(64);
        ArgumentCaptor<com.ecommerce.aftersales.entity.ApprovalRecordEntity> approvalCaptor =
                ArgumentCaptor.forClass(com.ecommerce.aftersales.entity.ApprovalRecordEntity.class);
        verify(harness.approvalRepository, times(1)).save(approvalCaptor.capture());
        assertThat(approvalCaptor.getValue().getId()).isEqualTo(outcome.job().getApprovalId());
        assertThat(approvalCaptor.getValue().getAmount()).isEqualByComparingTo("150000.00");
        assertThat(approvalCaptor.getValue().getOrderId()).isEqualTo("O-VN-5002");
        assertThat(approvalCaptor.getValue().getRunId()).isEqualTo("run-1");
    }

    @Test
    void duplicateApprovalReturnsExistingJobWithoutCreatingAnotherExecution() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        Harness harness = new Harness(proposal);

        ApprovalService.ApprovalOutcome first = harness.service.approve("proposal-1", "operator-1", "approved");
        when(harness.executionRepository.findByProposalId("proposal-1")).thenReturn(Optional.of(first.job()));
        ApprovalService.ApprovalOutcome second = harness.service.approve("proposal-1", "operator-1", "approved again");

        assertThat(second.job().getId()).isEqualTo(first.job().getId());
        assertThat(first.newlyApproved()).isTrue();
        assertThat(second.newlyApproved()).isFalse();
        ArgumentCaptor<ExecutionJobEntity> captor = ArgumentCaptor.forClass(ExecutionJobEntity.class);
        verify(harness.executionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).hasSize(64);
        verify(harness.approvalRepository, times(1)).save(any());
    }

    @Test
    void tamperedAmountIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setAmount(new BigDecimal("999999.00"));

        assertRejectedWithoutSideEffects(proposal, "APPROVAL_AMOUNT_MISMATCH");
    }

    @Test
    void tamperedCurrencyIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setCurrency("USD");

        assertRejectedWithoutSideEffects(proposal, "APPROVAL_CURRENCY_MISMATCH");
    }

    @Test
    void tamperedActionTypeIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setActionType("STORE_CREDIT");

        assertRejectedWithoutSideEffects(proposal, "APPROVAL_ACTION_MISMATCH");
    }

    @Test
    void tamperedPolicyVersionIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setPolicyVersion("v1");

        assertRejectedWithoutSideEffects(proposal, "APPROVAL_POLICY_VERSION_MISMATCH");
    }

    @Test
    void approveWithReorderedDuplicateEvidenceSnapshotCreatesJobAndRecord() {
        // 提案快照去重/乱序后与可信 Run 证据集合相等：审批通过并落库。
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"calculation:ticket-1:v1\",\"order:O-VN-5002:v1\","
                + "\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"policy:VN_SHIPMENT_DELAY:v3#section-4.2\",\"calculation:ticket-1:v1\"]");
        Harness harness = new Harness(proposal);

        ApprovalService.ApprovalOutcome outcome = harness.service.approve("proposal-1", "operator-1", "approved");

        assertThat(outcome.newlyApproved()).isTrue();
        assertThat(outcome.job().getProposalId()).isEqualTo("proposal-1");
        assertThat(proposal.getStatus()).isEqualTo(AfterSalesTypes.ProposalStatus.APPROVED);
        verify(harness.executionRepository, times(1)).save(any());
        verify(harness.approvalRepository, times(1)).save(any());
    }

    @Test
    void proposalEvidenceMissingIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"policy:VN_SHIPMENT_DELAY:v3#section-4.2\"]");

        assertRejectedWithoutSideEffects(proposal, "APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH");
    }

    @Test
    void proposalEvidenceReplacedIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"policy:VN_SHIPMENT_DELAY:v2#section-4.2\",\"calculation:ticket-1:v1\"]");

        assertRejectedWithoutSideEffects(proposal, "APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH");
    }

    @Test
    void proposalEvidenceExtraFakeEntryIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"policy:VN_SHIPMENT_DELAY:v3#section-4.2\",\"calculation:ticket-1:v1\","
                + "\"fake:approved-by-model\"]");

        assertRejectedWithoutSideEffects(proposal, "APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH");
    }

    @Test
    void trustedRunEvidenceIncompleteIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJson(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy(),
                List.of("order:O-VN-5002:v1", "shipment:O-VN-5002:2026-08-01",
                        "calculation:ticket-1:v1")));

        assertRejectedWithoutSideEffects(proposal, ApprovalTestSupport.ticket(), run,
                "APPROVAL_EVIDENCE_INCOMPLETE");
    }

    @Test
    void missingTrustedEvidenceIdsIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJsonWithoutEvidenceIds(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy()));

        assertRejectedWithoutSideEffects(proposal, ApprovalTestSupport.ticket(), run, "FINAL_ANSWER_INVALID");
    }

    @Test
    void nonArrayTrustedEvidenceIdsIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJsonRawEvidence(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy(),
                "order:O-VN-5002:v1"));

        assertRejectedWithoutSideEffects(proposal, ApprovalTestSupport.ticket(), run, "FINAL_ANSWER_INVALID");
    }

    @Test
    void nonStringTrustedEvidenceIdsIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJsonRawEvidence(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy(),
                List.of("order:O-VN-5002:v1", 42)));

        assertRejectedWithoutSideEffects(proposal, ApprovalTestSupport.ticket(), run, "FINAL_ANSWER_INVALID");
    }

    @Test
    void tamperedEvidenceIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"policy:VN_SHIPMENT_DELAY:v3#section-4.2\"]");

        assertRejectedWithoutSideEffects(proposal, "APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH");
    }

    @Test
    void ticketNotWaitingForApprovalIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        AfterSalesTicketEntity ticket = ApprovalTestSupport.ticket();
        ticket.setStatus(AfterSalesTypes.TicketStatus.RESOLVED);
        Harness harness = new Harness(proposal, ticket, ApprovalTestSupport.run());

        assertRejected(harness, "APPROVAL_TICKET_STATE_INVALID");
    }

    @Test
    void runNotCompletedIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setStatus("FAILED");
        Harness harness = new Harness(proposal, ApprovalTestSupport.ticket(), run);

        assertRejected(harness, "APPROVAL_RUN_STATE_INVALID");
    }

    @Test
    void compensationNotEligibleIsRejectedWithoutCreatingJobOrRecord() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJson(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(2), ApprovalTestSupport.policy()));
        Harness harness = new Harness(proposal, ApprovalTestSupport.ticket(), run);

        assertRejected(harness, "APPROVAL_COMPENSATION_NOT_ELIGIBLE");
    }

    private static void assertRejectedWithoutSideEffects(ActionProposalEntity proposal, String expectedCode) {
        assertRejectedWithoutSideEffects(proposal, ApprovalTestSupport.ticket(), ApprovalTestSupport.run(), expectedCode);
    }

    private static void assertRejectedWithoutSideEffects(
            ActionProposalEntity proposal,
            AfterSalesTicketEntity ticket,
            AfterSalesRunEntity run,
            String expectedCode) {
        Harness harness = new Harness(proposal, ticket, run);
        assertRejected(harness, expectedCode);
        assertThat(proposal.getStatus()).isEqualTo(AfterSalesTypes.ProposalStatus.PENDING);
    }

    private static void assertRejected(Harness harness, String expectedCode) {
        assertThatThrownBy(() -> harness.service.approve("proposal-1", "operator-1", "approved"))
                .isInstanceOfSatisfying(ApprovalPolicyViolationException.class,
                        error -> assertThat(error.getCode()).isEqualTo(expectedCode));
        verify(harness.executionRepository, never()).save(any());
        verify(harness.approvalRepository, never()).save(any());
    }

    /** 测试夹具：mock 全部仓库 + 真实 ApprovalPolicyGate（mock 工单/运行仓库 + 真实目录/规则引擎）。 */
    private static final class Harness {
        final ActionProposalRepository proposalRepository;
        final ApprovalRecordRepository approvalRepository;
        final ExecutionJobRepository executionRepository;
        final ApprovalService service;

        Harness(ActionProposalEntity proposal) {
            this(proposal, ApprovalTestSupport.ticket(), ApprovalTestSupport.run());
        }

        Harness(ActionProposalEntity proposal, AfterSalesTicketEntity ticket, AfterSalesRunEntity run) {
            this.proposalRepository = mock(ActionProposalRepository.class);
            this.approvalRepository = mock(ApprovalRecordRepository.class);
            this.executionRepository = mock(ExecutionJobRepository.class);
            when(proposalRepository.findByIdForUpdate(anyString())).thenReturn(Optional.of(proposal));
            when(executionRepository.findByIdempotencyKey(anyString())).thenReturn(Optional.empty());
            when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
            ApprovalPolicyGate gate = ApprovalTestSupport.gate(ticket, run);
            this.service = new ApprovalService(proposalRepository, approvalRepository, executionRepository, gate);
        }
    }
}
