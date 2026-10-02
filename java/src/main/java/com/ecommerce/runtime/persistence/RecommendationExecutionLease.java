package com.ecommerce.runtime.persistence;

import com.ecommerce.model.ToolLoopRequest;

import java.time.Instant;
import java.util.Objects;

/** Internal execution credential. Never accept a token or attempt from a client request. */
public record RecommendationExecutionLease(String runId, String token, int attempt,
                                            String ownerId, ToolLoopRequest request) {
    boolean isCurrent(RecommendationRunEntity run, Instant now) {
        return run.isRecoverable() && Objects.equals(run.getId(), runId)
                && token != null && Objects.equals(run.getExecutionToken(), token)
                && Objects.equals(run.getExecutionOwner(), ownerId)
                && run.getExecutionAttempt() == attempt && run.getExecutionLeaseUntil() != null
                && run.getExecutionLeaseUntil().isAfter(now);
    }

    void assertCurrent(RecommendationRunEntity run, Instant now) {
        if (!isCurrent(run, now)) throw new StaleExecutionLeaseException(runId);
    }
}
