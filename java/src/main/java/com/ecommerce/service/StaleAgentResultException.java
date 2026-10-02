package com.ecommerce.service;

/** Raised when an asynchronous specialist returns for an obsolete candidate set. */
public class StaleAgentResultException extends RuntimeException {
    public StaleAgentResultException(String action, long expectedVersion, long currentVersion) {
        super(action + " produced candidateVersion=" + expectedVersion
                + " but current candidateVersion=" + currentVersion);
    }
}
