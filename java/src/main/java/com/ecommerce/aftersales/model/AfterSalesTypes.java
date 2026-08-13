package com.ecommerce.aftersales.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public final class AfterSalesTypes {

    private AfterSalesTypes() {
    }

    public enum TicketStatus {
        OPEN,
        ANALYZING,
        PENDING_APPROVAL,
        RESOLVED,
        FAILED
    }

    public enum ProposalStatus {
        PENDING,
        APPROVED,
        REJECTED
    }

    public enum ExecutionStatus {
        PENDING,
        RUNNING,
        RETRY_WAIT,
        SUCCEEDED,
        DEAD_LETTER
    }

    public record OrderSnapshot(
            String orderId,
            String userId,
            String platform,
            String country,
            String currency,
            String warehouseRegion,
            BigDecimal paidAmount,
            boolean paid,
            String fulfillmentStatus,
            int promisedDeliveryDays,
            String trackingNumber
    ) {
    }

    public record ShipmentCheckpoint(Instant occurredAt, String status, String location, String description) {
    }

    public record ShipmentSnapshot(
            String trackingNumber,
            String status,
            Instant lastUpdatedAt,
            int inactiveDays,
            int delayDays,
            List<ShipmentCheckpoint> timeline
    ) {
    }

    public record PolicyEvidence(
            String evidenceId,
            String policyId,
            String version,
            String country,
            String issueType,
            Instant effectiveFrom,
            int minimumInactiveDays,
            BigDecimal compensationRate,
            BigDecimal maximumCompensation,
            String actionType,
            String section,
            String summary
    ) {
    }

    public record CompensationResult(
            boolean eligible,
            String actionType,
            BigDecimal amount,
            String currency,
            String reason
    ) {
    }

    public record ToolResult(String summary, Object data, List<String> evidenceIds) {
    }

    public record ExecutionResult(String externalReference, String status, Instant executedAt) {
    }
}
