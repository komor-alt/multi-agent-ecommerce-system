package com.ecommerce.service;

/** Creates the per-run LLM budget. Production uses the local budget; benchmark infrastructure may add a durable limiter. */
@FunctionalInterface
public interface LlmCallBudgetFactory {
    LlmCallBudget create(String runId, int maxCalls);
}
