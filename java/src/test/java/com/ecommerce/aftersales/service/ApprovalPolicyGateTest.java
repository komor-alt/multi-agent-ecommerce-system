package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.AfterSalesRunEntity;
import com.ecommerce.aftersales.entity.AfterSalesTicketEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.AfterSalesRunRepository;
import com.ecommerce.aftersales.repository.AfterSalesTicketRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_ACTION_MISMATCH;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_AMOUNT_MISMATCH;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_COMPENSATION_NOT_ELIGIBLE;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_CURRENCY_MISMATCH;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_EVIDENCE_INCOMPLETE;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_POLICY_VERSION_MISMATCH;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_PROPOSAL_STATE_INVALID;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_RUN_STATE_INVALID;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.APPROVAL_TICKET_STATE_INVALID;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.FINAL_ANSWER_INVALID;
import static com.ecommerce.aftersales.service.ApprovalPolicyViolationException.POLICY_CONTEXT_MISMATCH;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * ApprovalPolicyGate 单元测试：Gate 接收已锁定（findByIdForUpdate）的 Proposal 做复核，
 * 自身不重新锁 Proposal；篡改 amount/currency/actionType/policyVersion/evidence 任一、
 * ticket/run 状态非法、补偿不 eligible、最终答复非法、政策上下文不匹配 → 均以安全错误码拒绝。
 */
class ApprovalPolicyGateTest {

    @Test
    void validProposalPassesAndReturnsTrustedRecomputedCompensation() {
        ApprovalValidationResult result = ApprovalTestSupport.passingGate()
                .validate(ApprovalTestSupport.proposal());

        assertThat(result.proposalId()).isEqualTo("proposal-1");
        assertThat(result.order().orderId()).isEqualTo("O-VN-5002");
        assertThat(result.shipment().inactiveDays()).isEqualTo(10);
        assertThat(result.policy().version()).isEqualTo("v3");
        assertThat(result.recomputedCompensation().eligible()).isTrue();
        assertThat(result.recomputedCompensation().amount()).isEqualByComparingTo("150000.00");
        assertThat(result.recomputedCompensation().currency()).isEqualTo("VND");
        assertThat(result.recomputedCompensation().actionType()).isEqualTo("DELAY_COMPENSATION_COUPON");
    }

    @Test
    void tamperedAmountIsRejected() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setAmount(new BigDecimal("999999.00"));

        assertRejected(proposal, APPROVAL_AMOUNT_MISMATCH);
    }

    @Test
    void tamperedCurrencyIsRejected() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setCurrency("USD");

        assertRejected(proposal, APPROVAL_CURRENCY_MISMATCH);
    }

    @Test
    void tamperedActionTypeIsRejected() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setActionType("STORE_CREDIT");

        assertRejected(proposal, APPROVAL_ACTION_MISMATCH);
    }

    @Test
    void tamperedPolicyVersionIsRejected() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setPolicyVersion("v1");

        assertRejected(proposal, APPROVAL_POLICY_VERSION_MISMATCH);
    }

    @Test
    void reorderedDuplicateEvidenceSnapshotPasses() {
        // 提案快照去重/乱序后与可信 Run 证据完全一致（顺序本身不是安全属性）。
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"calculation:ticket-1:v1\",\"order:O-VN-5002:v1\","
                + "\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"policy:VN_SHIPMENT_DELAY:v3#section-4.2\",\"calculation:ticket-1:v1\"]");

        ApprovalValidationResult result = ApprovalTestSupport.passingGate().validate(proposal);

        assertThat(result.proposalId()).isEqualTo("proposal-1");
        assertThat(result.recomputedCompensation().eligible()).isTrue();
    }

    @Test
    void reorderedDuplicateTrustedEvidencePasses() {
        // 可信 Run 证据去重/乱序，提案保持默认快照：集合语义相等 → 通过。
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJson(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy(),
                List.of("calculation:ticket-1:v1", "order:O-VN-5002:v1", "order:O-VN-5002:v1",
                        "shipment:O-VN-5002:2026-08-01", "policy:VN_SHIPMENT_DELAY:v3#section-4.2")));

        ApprovalValidationResult result = ApprovalTestSupport.gate(ApprovalTestSupport.ticket(), run)
                .validate(ApprovalTestSupport.proposal());

        assertThat(result.proposalId()).isEqualTo("proposal-1");
        assertThat(result.recomputedCompensation().eligible()).isTrue();
    }

    @Test
    void proposalEvidenceMissingCalculationIsRejectedAsSnapshotMismatch() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"policy:VN_SHIPMENT_DELAY:v3#section-4.2\"]");

        assertRejected(proposal, APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH);
    }

    @Test
    void proposalEvidenceMissingPolicyIsRejectedAsSnapshotMismatch() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"calculation:ticket-1:v1\"]");

        assertRejected(proposal, APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH);
    }

    @Test
    void proposalEvidenceReplacedIsRejectedAsSnapshotMismatch() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"policy:VN_SHIPMENT_DELAY:v2#section-4.2\",\"calculation:ticket-1:v1\"]");

        assertRejected(proposal, APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH);
    }

    @Test
    void proposalEvidenceExtraFakeEntryIsRejectedAsSnapshotMismatch() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[\"order:O-VN-5002:v1\",\"shipment:O-VN-5002:2026-08-01\","
                + "\"policy:VN_SHIPMENT_DELAY:v3#section-4.2\",\"calculation:ticket-1:v1\","
                + "\"fake:approved-by-model\"]");

        assertRejected(proposal, APPROVAL_EVIDENCE_SNAPSHOT_MISMATCH);
    }

    @Test
    void trustedRunEvidenceIncompleteIsRejected() {
        // 可信 Run 证据缺 policy：即使提案自己声称完整也拒绝。
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJson(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy(),
                List.of("order:O-VN-5002:v1", "shipment:O-VN-5002:2026-08-01", "calculation:ticket-1:v1")));

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run,
                APPROVAL_EVIDENCE_INCOMPLETE);
    }

    @Test
    void missingTrustedEvidenceIdsIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJsonWithoutEvidenceIds(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy()));

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, FINAL_ANSWER_INVALID);
    }

    @Test
    void nonArrayTrustedEvidenceIdsIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJsonRawEvidence(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy(),
                "order:O-VN-5002:v1"));

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, FINAL_ANSWER_INVALID);
    }

    @Test
    void nonStringTrustedEvidenceIdsIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJsonRawEvidence(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy(),
                List.of("order:O-VN-5002:v1", 42)));

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, FINAL_ANSWER_INVALID);
    }

    @Test
    void nullTrustedEvidenceElementIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJsonRawEvidence(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy(),
                Arrays.asList("order:O-VN-5002:v1", null)));

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, FINAL_ANSWER_INVALID);
    }

    @Test
    void blankTrustedEvidenceIdIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJsonRawEvidence(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), ApprovalTestSupport.policy(),
                List.of("order:O-VN-5002:v1", "  ")));

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, FINAL_ANSWER_INVALID);
    }

    @Test
    void nonStringProposalEvidenceIsRejectedAsIncomplete() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("[1,2,3]");

        assertRejected(proposal, APPROVAL_EVIDENCE_INCOMPLETE);
    }

    @Test
    void unparseableEvidenceJsonIsTreatedAsIncomplete() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setEvidenceIdsJson("not-json");

        assertRejected(proposal, APPROVAL_EVIDENCE_INCOMPLETE);
    }

    @Test
    void nonPendingProposalIsRejected() {
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setStatus(AfterSalesTypes.ProposalStatus.REJECTED);

        assertRejected(proposal, APPROVAL_PROPOSAL_STATE_INVALID);
    }

    @Test
    void ticketNotWaitingForApprovalIsRejected() {
        AfterSalesTicketEntity ticket = ApprovalTestSupport.ticket();
        ticket.setStatus(AfterSalesTypes.TicketStatus.RESOLVED);

        assertRejected(ApprovalTestSupport.proposal(), ticket, ApprovalTestSupport.run(), APPROVAL_TICKET_STATE_INVALID);
    }

    @Test
    void missingTicketIsRejected() {
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        when(ticketRepository.findById(anyString())).thenReturn(Optional.empty());
        ApprovalPolicyGate gate = new ApprovalPolicyGate(
                ticketRepository, mock(AfterSalesRunRepository.class),
                new DemoAfterSalesPolicyCatalogService(), new CompensationRuleService(),
                ApprovalTestSupport.MAPPER);

        assertThatThrownBy(() -> gate.validate(ApprovalTestSupport.proposal()))
                .isInstanceOfSatisfying(ApprovalPolicyViolationException.class,
                        error -> assertThat(error.getCode()).isEqualTo(APPROVAL_TICKET_STATE_INVALID));
    }

    @Test
    void runNotCompletedIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setStatus("FAILED");

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, APPROVAL_RUN_STATE_INVALID);
    }

    @Test
    void runWithoutProposalStopReasonIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setStopReason("NO_ACTION_REQUIRED");

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, APPROVAL_RUN_STATE_INVALID);
    }

    @Test
    void runOfAnotherTicketIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setTicketId("ticket-2");

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, APPROVAL_RUN_STATE_INVALID);
    }

    @Test
    void ticketWithoutCurrentRunIsRejected() {
        AfterSalesTicketEntity ticket = ApprovalTestSupport.ticket();
        ticket.setCurrentRunId(null);

        assertRejected(ApprovalTestSupport.proposal(), ticket, ApprovalTestSupport.run(), APPROVAL_RUN_STATE_INVALID);
    }

    @Test
    void missingRunIsRejected() {
        AfterSalesTicketRepository ticketRepository = mock(AfterSalesTicketRepository.class);
        when(ticketRepository.findById(anyString())).thenReturn(Optional.of(ApprovalTestSupport.ticket()));
        AfterSalesRunRepository runRepository = mock(AfterSalesRunRepository.class);
        when(runRepository.findById(anyString())).thenReturn(Optional.empty());
        ApprovalPolicyGate gate = new ApprovalPolicyGate(
                ticketRepository, runRepository,
                new DemoAfterSalesPolicyCatalogService(), new CompensationRuleService(),
                ApprovalTestSupport.MAPPER);

        assertThatThrownBy(() -> gate.validate(ApprovalTestSupport.proposal()))
                .isInstanceOfSatisfying(ApprovalPolicyViolationException.class,
                        error -> assertThat(error.getCode()).isEqualTo(APPROVAL_RUN_STATE_INVALID));
    }

    @Test
    void blankFinalAnswerIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(null);

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, FINAL_ANSWER_INVALID);
    }

    @Test
    void unreadableFinalAnswerIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson("{not-json");

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, FINAL_ANSWER_INVALID);
    }

    @Test
    void finalAnswerWithoutPolicyIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson("{\"order\":null,\"shipment\":null}");

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, FINAL_ANSWER_INVALID);
    }

    @Test
    void policyCountryMismatchIsRejected() {
        AfterSalesTypes.PolicyEvidence otherCountryPolicy = new AfterSalesTypes.PolicyEvidence(
                "policy:MY_SHIPMENT_DELAY:v3#section-4.2", "MY_SHIPMENT_DELAY", "v3", "MY",
                "SHIPMENT_DELAY", Instant.parse("2026-01-01T00:00:00Z"), 7, new BigDecimal("0.10"),
                new BigDecimal("25.00"), "DELAY_COMPENSATION_COUPON", "4.2", "delay coupon");
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJson(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), otherCountryPolicy));

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, POLICY_CONTEXT_MISMATCH);
    }

    @Test
    void policyEffectiveAfterTicketCreationIsRejected() {
        AfterSalesTypes.PolicyEvidence futurePolicy = new AfterSalesTypes.PolicyEvidence(
                "policy:VN_SHIPMENT_DELAY:v3#section-4.2", "VN_SHIPMENT_DELAY", "v3", "VN",
                "SHIPMENT_DELAY", Instant.parse("2027-01-01T00:00:00Z"), 7, new BigDecimal("0.10"),
                new BigDecimal("150000.00"), "DELAY_COMPENSATION_COUPON", "4.2", "delay coupon");
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJson(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(), futurePolicy));

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run, POLICY_CONTEXT_MISMATCH);
    }

    @Test
    void catalogRelookupFindsNewerVersionAndRejectsOldProposalVersion() {
        // SG 订单 + 政策 v1（2025-06-01 生效）+ 2026-08-01 工单：目录重匹配到 v2 → 版本不一致。
        AfterSalesTypes.OrderSnapshot sgOrder = new AfterSalesTypes.OrderSnapshot(
                "O-SG-6001", "sea_sg_001", "shopify", "SG", "SGD", "SG",
                new BigDecimal("100.00"), true, "delivered_late", 10, "SF-SG-6001");
        AfterSalesTypes.PolicyEvidence sgV1Policy = new AfterSalesTypes.PolicyEvidence(
                "policy:SG_SHIPMENT_DELAY:v1#section-4.2", "SG_SHIPMENT_DELAY", "v1", "SG",
                "SHIPMENT_DELAY", Instant.parse("2025-06-01T00:00:00Z"), 7, new BigDecimal("0.10"),
                new BigDecimal("25.00"), "DELAY_COMPENSATION_COUPON", "4.2", "delay coupon");
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJson(
                sgOrder, ApprovalTestSupport.shipment(), sgV1Policy));
        ActionProposalEntity proposal = ApprovalTestSupport.proposal();
        proposal.setPolicyVersion("v1");

        AfterSalesTicketEntity ticket = ApprovalTestSupport.ticket();
        ticket.setOrderId(sgOrder.orderId());
        assertRejected(proposal, ticket, run, APPROVAL_POLICY_VERSION_MISMATCH);
    }

    @Test
    void compensationNotEligibleIsRejected() {
        AfterSalesRunEntity run = ApprovalTestSupport.run();
        run.setFinalAnswerJson(ApprovalTestSupport.finalAnswerJson(
                ApprovalTestSupport.order(), ApprovalTestSupport.shipment(2), ApprovalTestSupport.policy()));

        assertRejected(ApprovalTestSupport.proposal(), ApprovalTestSupport.ticket(), run,
                APPROVAL_COMPENSATION_NOT_ELIGIBLE);
    }

    private static void assertRejected(ActionProposalEntity proposal, String expectedCode) {
        assertRejected(proposal, ApprovalTestSupport.ticket(), ApprovalTestSupport.run(), expectedCode);
    }

    private static void assertRejected(
            ActionProposalEntity proposal,
            AfterSalesTicketEntity ticket,
            AfterSalesRunEntity run,
            String expectedCode) {
        assertThatThrownBy(() -> ApprovalTestSupport.gate(ticket, run).validate(proposal))
                .isInstanceOfSatisfying(ApprovalPolicyViolationException.class,
                        error -> assertThat(error.getCode()).isEqualTo(expectedCode));
    }
}
