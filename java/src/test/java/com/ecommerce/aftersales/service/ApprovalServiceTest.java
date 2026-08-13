package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.entity.ActionProposalEntity;
import com.ecommerce.aftersales.entity.ExecutionJobEntity;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.ActionProposalRepository;
import com.ecommerce.aftersales.repository.ApprovalRecordRepository;
import com.ecommerce.aftersales.repository.ExecutionJobRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ApprovalServiceTest {

    @Test
    void duplicateApprovalReturnsExistingJobWithoutCreatingAnotherExecution() {
        ActionProposalRepository proposalRepository = mock(ActionProposalRepository.class);
        ApprovalRecordRepository approvalRepository = mock(ApprovalRecordRepository.class);
        ExecutionJobRepository executionRepository = mock(ExecutionJobRepository.class);
        ApprovalService service = new ApprovalService(proposalRepository, approvalRepository, executionRepository);

        ActionProposalEntity proposal = ActionProposalEntity.builder()
                .id("proposal-1")
                .ticketId("ticket-1")
                .actionType("DELAY_COMPENSATION_COUPON")
                .amount(new BigDecimal("150000.00"))
                .currency("VND")
                .proposalVersion("v1")
                .status(AfterSalesTypes.ProposalStatus.PENDING)
                .build();
        when(proposalRepository.findByIdForUpdate("proposal-1")).thenReturn(Optional.of(proposal));
        when(executionRepository.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(executionRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        ApprovalService.ApprovalOutcome first = service.approve("proposal-1", "operator-1", "approved");
        when(executionRepository.findByProposalId("proposal-1")).thenReturn(Optional.of(first.job()));
        ApprovalService.ApprovalOutcome second = service.approve("proposal-1", "operator-1", "approved again");

        assertThat(second.job().getId()).isEqualTo(first.job().getId());
        assertThat(first.newlyApproved()).isTrue();
        assertThat(second.newlyApproved()).isFalse();
        ArgumentCaptor<ExecutionJobEntity> captor = ArgumentCaptor.forClass(ExecutionJobEntity.class);
        verify(executionRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).hasSize(64);
        verify(approvalRepository, times(1)).save(any());
    }
}

