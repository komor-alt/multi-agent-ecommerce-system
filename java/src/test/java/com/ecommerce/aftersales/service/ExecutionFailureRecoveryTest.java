package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.connector.*;
import com.ecommerce.aftersales.entity.*;
import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.repository.*;
import com.ecommerce.config.AfterSalesExecutionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Uses the production approval gate, approval service, execution service and connector ledger.
 * Only repositories and external fault delivery are test doubles; no live model or commerce calls.
 */
class ExecutionFailureRecoveryTest {
    @Test
    void lostResponseIsReconciledEvenWhenOrderRefundedBeforeRetry() {
        FaultConnector connector = new FaultConnector();
        connector.loseResponse.set(true);
        Fixture f = new Fixture(connector);
        f.service.executeAsync(f.job.getId());
        assertThat(f.job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.RETRY_WAIT);
        assertThat(connector.committedEffectCount()).isEqualTo(1);
        connector.refunded = true;
        f.retry();
        assertThat(f.job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.SUCCEEDED);
        assertThat(connector.committedEffectCount()).isEqualTo(1);
        f.service.executeAsync(f.job.getId());
        assertThat(connector.committedEffectCount()).isEqualTo(1);
    }

    @Test
    void readTimeoutRetriesWithoutInventingEvidenceOrSideEffects() {
        FaultConnector connector = new FaultConnector();
        connector.readTimeout.set(true);
        Fixture f = new Fixture(connector);
        f.service.executeAsync(f.job.getId());
        assertThat(f.job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.RETRY_WAIT);
        assertThat(connector.committedEffectCount()).isZero();
        f.retry();
        assertThat(f.job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.SUCCEEDED);
        assertThat(connector.committedEffectCount()).isEqualTo(1);
    }

    @Test
    void refundedOrderCancelsBeforeEffect() {
        FaultConnector connector = new FaultConnector();
        connector.refunded = true;
        Fixture f = new Fixture(connector);
        f.service.executeAsync(f.job.getId());
        assertThat(f.job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.CANCELLED);
        assertThat(connector.committedEffectCount()).isZero();
    }

    @Test
    void modifiedApprovedJobNeverReachesConnectorWrite() {
        FaultConnector connector = new FaultConnector();
        Fixture f = new Fixture(connector);
        f.job.setAmount(java.math.BigDecimal.ONE);
        f.service.executeAsync(f.job.getId());
        assertThat(f.job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.CANCELLED);
        assertThat(f.job.getLastError()).isEqualTo("APPROVAL_AMOUNT_CHANGED");
        assertThat(connector.committedEffectCount()).isZero();
    }

    @Test
    void repeatedExecutionAndApprovalReuseTheExistingBusinessCommand() {
        FaultConnector connector = new FaultConnector();
        Fixture f = new Fixture(connector);
        for (int i = 0; i < 5; i++) f.service.executeAsync(f.job.getId());
        assertThat(f.job.getStatus()).isEqualTo(AfterSalesTypes.ExecutionStatus.SUCCEEDED);
        assertThat(connector.committedEffectCount()).isEqualTo(1);
        assertThat(f.job.getAttemptCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DELAY_COMPENSATION_COUPON", "DAMAGE_COMPENSATION_COUPON", "LOST_PARCEL_REFUND"})
    void connectorRejectsSameKeyWithDifferentPayloadAndDeduplicatesConcurrentWrites(String action) throws Exception {
        MockShopifyAfterSalesConnector connector = new MockShopifyAfterSalesConnector();
        var command = new AfterSalesConnector.ExecutionCommand("stable-key", ApprovalTestSupport.order().orderId(),
                action, java.math.BigDecimal.TEN, "VND");
        var pool = java.util.concurrent.Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<AfterSalesTypes.ExecutionResult>>();
            for (int i = 0; i < 24; i++) futures.add(pool.submit(() -> connector.execute(ApprovalTestSupport.order(), command)));
            var first = futures.get(0).get();
            for (var future : futures) assertThat(future.get()).isEqualTo(first);
        } finally { pool.shutdownNow(); }
        assertThat(connector.committedEffectCount()).isEqualTo(1);
        var changed = new AfterSalesConnector.ExecutionCommand("stable-key", command.orderId(), action,
                java.math.BigDecimal.ONE, "VND");
        assertThatThrownBy(() -> connector.execute(ApprovalTestSupport.order(), changed))
                .isInstanceOf(ConnectorException.class).hasMessage("IDEMPOTENCY_PAYLOAD_MISMATCH");
    }

    @Test
    void generatesMeasuredRecoveryReport() throws Exception {
        var rows = new java.util.ArrayList<java.util.Map<String, Object>>();
        for (String mode : java.util.List.of("READ_TIMEOUT", "TRANSIENT_FAILURE", "RESPONSE_LOST", "REFUNDED", "TAMPERED", "DUPLICATE")) {
            FaultConnector connector = new FaultConnector();
            connector.readTimeout.set("READ_TIMEOUT".equals(mode));
            connector.transientFailure.set("TRANSIENT_FAILURE".equals(mode));
            connector.loseResponse.set("RESPONSE_LOST".equals(mode));
            connector.refunded = "REFUNDED".equals(mode);
            Fixture f = new Fixture(connector);
            if ("TAMPERED".equals(mode)) f.job.setAmount(java.math.BigDecimal.ONE);
            f.service.executeAsync(f.job.getId());
            String firstStatus = f.job.getStatus().name();
            int firstEffects = connector.committedEffectCount();
            boolean recovery = java.util.Set.of("READ_TIMEOUT", "TRANSIENT_FAILURE", "RESPONSE_LOST").contains(mode);
            if (recovery) f.retry();
            if ("DUPLICATE".equals(mode)) for (int i = 0; i < 5; i++) f.service.executeAsync(f.job.getId());
            boolean cancelled = java.util.Set.of("REFUNDED", "TAMPERED").contains(mode);
            boolean passed = f.job.getStatus() == (cancelled ? AfterSalesTypes.ExecutionStatus.CANCELLED : AfterSalesTypes.ExecutionStatus.SUCCEEDED)
                    && connector.committedEffectCount() == (cancelled ? 0 : 1);
            rows.add(java.util.Map.of("scenario", mode, "firstStatus", firstStatus, "firstEffects", firstEffects,
                    "finalStatus", f.job.getStatus().name(), "sideEffects", connector.committedEffectCount(),
                    "attempts", f.job.getAttemptCount(), "recoveryCase", recovery, "passed", passed));
        }
        long recoveryCases = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("recoveryCase"))).count();
        long recovered = rows.stream().filter(r -> Boolean.TRUE.equals(r.get("recoveryCase")) && Boolean.TRUE.equals(r.get("passed"))).count();
        var report = java.util.Map.of("generatedAt", Instant.now().toString(), "caseCount", rows.size(),
                "recoverySuccessRate", recovered / (double) recoveryCases, "recoveryDenominator", recoveryCases,
                "scope", "production approval/execution services, mock repositories, process-local connector ledger; not real external writes",
                "cases", rows);
        var directory = java.nio.file.Path.of("target", "after-sales-eval");
        java.nio.file.Files.createDirectories(directory);
        java.nio.file.Files.writeString(directory.resolve("v2-recovery.json"),
                ApprovalTestSupport.MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(rows).allSatisfy(r -> assertThat(r.get("passed")).as(r.get("scenario").toString()).isEqualTo(true));
    }

    private static class FaultConnector extends MockShopifyAfterSalesConnector {
        final AtomicBoolean loseResponse = new AtomicBoolean();
        final AtomicBoolean readTimeout = new AtomicBoolean();
        final AtomicBoolean transientFailure = new AtomicBoolean();
        boolean refunded;
        @Override public AfterSalesTypes.OrderSnapshot getOrder(String id) {
            if (readTimeout.getAndSet(false)) throw new ConnectorException(ConnectorException.Category.RETRYABLE, "READ_TIMEOUT");
            var o = ApprovalTestSupport.order();
            return new AfterSalesTypes.OrderSnapshot(o.orderId(), o.userId(), o.platform(), o.country(), o.currency(),
                    o.warehouseRegion(), o.paidAmount(), o.paid(), o.fulfillmentStatus(), o.promisedDeliveryDays(),
                    o.trackingNumber(), refunded ? "REFUNDED" : "NONE");
        }
        @Override public AfterSalesTypes.ExecutionResult execute(AfterSalesTypes.OrderSnapshot order, ExecutionCommand command) {
            if (transientFailure.getAndSet(false)) throw new ConnectorException(ConnectorException.Category.RETRYABLE, "TRANSIENT_FAILURE");
            var result = super.execute(order, command);
            if (loseResponse.getAndSet(false)) throw new ConnectorException(ConnectorException.Category.RETRYABLE, "RESPONSE_LOST");
            return result;
        }
    }

    private static final class Fixture {
        final ExecutionJobEntity job;
        final ExecutionService service;
        @SuppressWarnings({"rawtypes", "unchecked"})
        Fixture(FaultConnector connector) {
            var ticket = ApprovalTestSupport.ticket();
            var proposal = ApprovalTestSupport.proposal();
            var jobs = mock(ExecutionJobRepository.class);
            var tickets = mock(AfterSalesTicketRepository.class);
            var proposals = mock(ActionProposalRepository.class);
            var approvals = mock(ApprovalRecordRepository.class);
            var runs = mock(AfterSalesRunRepository.class);
            when(tickets.findById(ticket.getId())).thenReturn(Optional.of(ticket));
            when(runs.findById("run-1")).thenReturn(Optional.of(ApprovalTestSupport.run()));
            when(proposals.findByIdForUpdate(proposal.getId())).thenReturn(Optional.of(proposal));
            when(proposals.findById(proposal.getId())).thenReturn(Optional.of(proposal));
            when(approvals.save(any())).thenAnswer(inv -> {
                ApprovalRecordEntity a = inv.getArgument(0);
                when(approvals.findById(a.getId())).thenReturn(Optional.of(a));
                return a;
            });
            when(jobs.save(any())).thenAnswer(inv -> inv.getArgument(0));
            var policies = new DemoAfterSalesPolicyCatalogService();
            var gate = new ApprovalPolicyGate(tickets, runs, policies, new CompensationRuleService(), ApprovalTestSupport.MAPPER);
            job = new ApprovalService(proposals, approvals, jobs, gate).approve(proposal.getId(), "operator", "verified").job();
            when(jobs.findByIdForUpdate(job.getId())).thenReturn(Optional.of(job));
            TransactionTemplate tx = mock(TransactionTemplate.class);
            when(tx.execute(any(TransactionCallback.class))).thenAnswer(inv ->
                    ((TransactionCallback) inv.getArgument(0)).doInTransaction(mock(TransactionStatus.class)));
            AfterSalesExecutionProperties properties = new AfterSalesExecutionProperties();
            properties.setWorkerId("fault-test");
            service = new ExecutionService(jobs, tickets, connector, new ExecutionPreconditionGate(),
                    mock(AfterSalesRunEventService.class), ApprovalTestSupport.MAPPER, tx, properties, Runnable::run,
                    new ExecutionApprovalValidator(proposals, approvals, policies));
        }
        void retry() {
            job.setNextRetryAt(Instant.now().minusSeconds(1));
            service.executeAsync(job.getId());
        }
    }
}
