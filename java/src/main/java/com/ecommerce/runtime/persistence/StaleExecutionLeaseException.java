package com.ecommerce.runtime.persistence;

/** An expired/replaced worker must stop; it must not fail or finalize its replacement's run. */
public class StaleExecutionLeaseException extends IllegalStateException {
    public StaleExecutionLeaseException(String runId) {
        super("RECOMMENDATION_EXECUTION_LEASE_STALE:" + runId);
    }
}
