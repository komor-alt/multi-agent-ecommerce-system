package com.ecommerce.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmCallBudgetTest {

    @Test
    void zeroBudgetNeverReservesModelCalls() {
        LlmCallBudget budget = new LlmCallBudget(0);

        assertFalse(budget.tryAcquire("planner", "run-1"));
        Map<String, Object> metrics = budget.snapshot();
        assertEquals(0, metrics.get("llmCallCount"));
        assertEquals(1, metrics.get("budgetBlockedCalls"));
        assertNull(metrics.get("promptTokens"));
        assertNull(metrics.get("completionTokens"));
        assertNull(metrics.get("estimatedCost"));
    }

    @Test
    void duplicateAndExcessCallsAreBoundedWithoutRetainingInput() {
        LlmCallBudget budget = new LlmCallBudget(1);

        assertTrue(budget.tryAcquire("planner", "run-1:step-1"));
        assertFalse(budget.tryAcquire("planner", "run-1:step-1"));
        assertFalse(budget.tryAcquire("planner", "run-1:step-2"));

        Map<String, Object> metrics = budget.snapshot();
        assertEquals(1, metrics.get("llmCallCount"));
        assertEquals(1, metrics.get("duplicateCallsBlocked"));
        assertEquals(1, metrics.get("budgetBlockedCalls"));
        assertEquals("unavailable", metrics.get("usageStatus"));
        assertNull(metrics.get("promptTokens"));
        assertNull(metrics.get("completionTokens"));
        assertNull(metrics.get("estimatedCost"));
    }

    @Test
    void sumsProviderUsageAndMarksPartialWhenSomeCallsOmitUsage() {
        LlmCallBudget budget = new LlmCallBudget(2);
        assertTrue(budget.reserve("supervisor", "call-1").acquired());
        assertTrue(budget.reserve("final_decision", "call-2").acquired());
        budget.recordTrace(new LlmCallBudget.CallTrace("supervisor", "provider", "model", 20,
                "success", null, 11, 5, 16, "available", true));
        budget.recordTrace(new LlmCallBudget.CallTrace("final_decision", "provider", "model", 30,
                "success", null, null, null, null, "unavailable", true));

        Map<String, Object> metrics = budget.snapshot();
        assertEquals(2, metrics.get("providerCallCount"));
        assertEquals(1, metrics.get("usageCallCount"));
        assertEquals(11L, metrics.get("promptTokens"));
        assertEquals(5L, metrics.get("completionTokens"));
        assertEquals(16L, metrics.get("totalTokens"));
        assertEquals("partial", metrics.get("usageStatus"));
        assertEquals("provider_usage_missing_for_some_calls", metrics.get("usageUnavailableReason"));
    }
}
