package com.ecommerce.eval.benchmark;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Builds runtime input without exposing evaluator expectations to the agent. */
public final class AgentBenchmarkExecutionInput {
    private static final Set<String> EVALUATOR_ONLY_CONSTRAINTS = Set.of(
            "minProducts", "requireCurrencyMatch", "requireInventoryPositive",
            "requireCrossBorderEligible", "requireFulfillmentCompliance",
            "minimumToolCounts", "minimumCapabilityCounts", "hardConstraintWinner");

    private AgentBenchmarkExecutionInput() {}

    public static Map<String, Object> context(AgentBenchmarkDataset.Case testCase) {
        Map<String, Object> context = new LinkedHashMap<>();
        testCase.constraints().forEach((key, value) -> {
            if (!EVALUATOR_ONLY_CONSTRAINTS.contains(key)) context.put(key, value);
        });
        context.put("intentText", testCase.intentForExecution());
        context.put("conversationTurns", testCase.conversationTurns());
        return context;
    }

    /** RULES-mode adapter; derives scene only from executable task text, never expectedScene. */
    public static String offlineScene(AgentBenchmarkDataset.Case testCase) {
        String intent = testCase.intentForExecution().toLowerCase(Locale.ROOT);
        if (intent.contains("homepage") || intent.contains("storefront")) return "homepage";
        if (intent.contains("approved campaign") || intent.contains("campaign scene")) return "campaign";
        if (intent.contains("retention") || intent.contains("win-back") || intent.contains("win back")) {
            return "retention";
        }
        if (intent.contains("campaign")) return "campaign";
        return "homepage";
    }
}
