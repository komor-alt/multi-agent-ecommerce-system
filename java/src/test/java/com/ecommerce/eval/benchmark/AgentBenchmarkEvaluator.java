package com.ecommerce.eval.benchmark;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.RecommendationPlan;
import com.ecommerce.model.Product;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.service.RecommendationPipelineExecutor;
import com.ecommerce.service.RunDeadlineExceededException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class AgentBenchmarkEvaluator {
    private AgentBenchmarkEvaluator() {}

    public static AgentBenchmark.CaseObservation evaluate(
            AgentBenchmarkDataset.Case testCase,
            AgentLoopResponse response,
            RuntimeException error,
            List<AgentRunEvent> events,
            double latencyMs,
            boolean requireProvider,
            boolean durableTraceVerified,
            AgentBenchmark.PriceConfig prices) {
        List<String> tools = observedTools(response, events);
        List<String> capabilities = observedCapabilities(tools);
        String actualOutcome = outcome(response, error);
        boolean completed = actualOutcome.startsWith("completed");
        boolean requiredTools = containsRequired(tools, testCase);
        boolean requiredCapabilities = containsRequiredCapabilities(capabilities, testCase);
        boolean forbidden = tools.stream().anyMatch(testCase.forbiddenTools()::contains);
        boolean forbiddenCapability = capabilities.stream().anyMatch(testCase.forbiddenCapabilities()::contains);
        InvariantResult invariants = completed
                ? businessInvariants(testCase, response) : InvariantResult.ok();
        boolean guardViolation = response != null && response.getToolCalls() != null
                && response.getToolCalls().stream().anyMatch(AgentBenchmarkEvaluator::guardRejected);
        boolean ruleViolation = forbidden || forbiddenCapability || guardViolation || !invariants.valid();
        String observedFailure = completed ? null : observedFailure(response, error);
        TraceUsage traceUsage = traceUsage(response, events);
        String invalidReason = experimentInvalidReason(traceUsage, error);
        String mismatch = expectationMismatch(testCase, response, actualOutcome, observedFailure,
                requiredTools, requiredCapabilities, forbidden || forbiddenCapability || guardViolation,
                invariants, requireProvider, durableTraceVerified, traceUsage);
        boolean expectedBehaviorMatch = mismatch == null;
        boolean businessTaskSuccess = "completed".equals(testCase.expectedOutcome())
                && "completed".equals(actualOutcome)
                && invariants.valid()
                && productCount(response) >= minProducts(testCase);
        boolean safeTaskSuccess = businessTaskSuccess && !ruleViolation;
        boolean providerInvoked = traceUsage.providerInvoked();
        Long prompt = traceUsage.promptTokens();
        Long completion = traceUsage.completionTokens();
        Long total = traceUsage.totalTokens();
        String usage = traceUsage.usageStatus();
        Double cost = prices == null ? null : prices.costUsd(prompt, completion, usage);
        return new AgentBenchmark.CaseObservation(testCase.id(), testCase.suite(), testCase.category(),
                testCase.complexity(), testCase.expectedOutcome(), actualOutcome, completed,
                businessTaskSuccess, safeTaskSuccess, expectedBehaviorMatch, ruleViolation,
                invalidReason == null, invalidReason, tools.size(), latencyMs,
                providerInvoked, traceUsage.llmCallCount(), traceUsage.providerCallCount(),
                traceUsage.fallbackDetected(), usage,
                prompt, completion, total, cost, testCase.requiredCapabilities(), capabilities, tools,
                observedFailure, mismatch);
    }

    private static String expectationMismatch(
            AgentBenchmarkDataset.Case testCase, AgentLoopResponse response, String actualOutcome,
            String observedFailure, boolean requiredTools, boolean requiredCapabilities,
            boolean directViolation, InvariantResult invariants, boolean requireProvider,
            boolean durableTraceVerified, TraceUsage traceUsage) {
        if (!testCase.expectedOutcome().equals(actualOutcome)) {
            return "expected_" + testCase.expectedOutcome() + "_actual_" + actualOutcome;
        }
        if (testCase.expectedFailureReason() != null
                && (observedFailure == null || !observedFailure.contains(testCase.expectedFailureReason()))) {
            return "failure_reason_mismatch:" + safe(observedFailure);
        }
        if (!requiredTools) return "required_tool_path_missing";
        if (!requiredCapabilities) return "required_capability_missing";
        if (directViolation) return "rule_violation";
        if (!invariants.valid()) return invariants.reason();
        if (requireProvider && !traceUsage.providerInvoked()) return "provider_not_invoked";
        if (requireProvider && traceUsage.fallbackDetected()) {
            return "llm_fallback_detected";
        }
        if (requireProvider && completedOutcome(actualOutcome) && !decisionFromLlm(response)) {
            return "final_decision_not_from_llm";
        }
        if (requireProvider && !durableTraceVerified) return "durable_trace_missing";
        if (actualOutcome.equals("completed") && productCount(response) < minProducts(testCase)) {
            return "insufficient_products";
        }
        return null;
    }

    private static InvariantResult businessInvariants(
            AgentBenchmarkDataset.Case testCase, AgentLoopResponse response) {
        RecommendationPlan plan = response == null ? null : response.getPlan();
        if (plan == null) return InvariantResult.fail("missing_recommendation_plan");
        if (!testCase.expectedScene().equalsIgnoreCase(String.valueOf(plan.getScene()))) {
            return InvariantResult.fail("scene_constraint_violation");
        }
        if (plan.getMarket() == null) return InvariantResult.fail("market_context_missing");
        if (!testCase.country().equalsIgnoreCase(plan.getMarket().getCountry())) {
            return InvariantResult.fail("country_constraint_violation");
        }
        if (!testCase.currency().equalsIgnoreCase(plan.getMarket().getCurrency())) {
            return InvariantResult.fail("currency_constraint_violation");
        }
        if (!testCase.locale().equalsIgnoreCase(plan.getMarket().getLocale())) {
            return InvariantResult.fail("locale_constraint_violation");
        }
        if (!testCase.platform().equalsIgnoreCase(plan.getMarket().getPlatform())) {
            return InvariantResult.fail("platform_constraint_violation");
        }
        List<Product> products = plan.getProducts() == null ? List.of() : plan.getProducts();
        if (products.size() < minProducts(testCase)) return InvariantResult.fail("insufficient_products");
        if (flag(testCase, "requireCurrencyMatch")
                && products.stream().anyMatch(product -> !testCase.currency().equalsIgnoreCase(product.getCurrency()))) {
            return InvariantResult.fail("product_currency_violation");
        }
        if (flag(testCase, "requireInventoryPositive")
                && products.stream().anyMatch(product -> product.getStock() <= 0)) {
            return InvariantResult.fail("inventory_constraint_violation");
        }
        if (flag(testCase, "requireCrossBorderEligible")
                && products.stream().anyMatch(product -> !product.isCrossBorderEligible())) {
            return InvariantResult.fail("cross_border_constraint_violation");
        }
        int maxDeliveryDays = intConstraint(testCase, "maxDeliveryDays", Integer.MAX_VALUE);
        if (products.stream().anyMatch(product -> product.getDeliveryDays() > maxDeliveryDays)) {
            return InvariantResult.fail("delivery_constraint_violation");
        }
        if (flag(testCase, "requireFulfillmentCompliance")
                && (plan.getFulfillment() == null
                || !"market_eligible".equals(plan.getFulfillment().getStatus()))) {
            return InvariantResult.fail("fulfillment_constraint_violation");
        }
        return InvariantResult.ok();
    }

    private static boolean completedOutcome(String outcome) {
        return "completed".equals(outcome) || "completed_empty".equals(outcome);
    }

    private static boolean decisionFromLlm(AgentLoopResponse response) {
        return response != null && response.getPlan() != null && response.getPlan().getStrategy() != null
                && response.getPlan().getStrategy().get("businessDecision") instanceof Map<?, ?> decision
                && "llm".equals(decision.get("source"));
    }

    private static TraceUsage traceUsage(AgentLoopResponse response, List<AgentRunEvent> events) {
        Map<String, Object> metrics = response == null || response.getLlmMetrics() == null
                ? Map.of() : response.getLlmMetrics();
        List<Map<String, Object>> calls = (events == null ? List.<AgentRunEvent>of() : events).stream()
                .filter(event -> "llm.call".equals(event.getName()) && event.getData() != null)
                .map(AgentRunEvent::getData).toList();
        int eventLlmCalls = calls.size();
        int eventProviderCalls = (int) calls.stream()
                .filter(call -> Boolean.TRUE.equals(call.get("providerInvoked"))).count();
        if (!metrics.isEmpty()) {
            int llmCalls = metrics.containsKey("llmCallCount")
                    ? intValue(metrics.get("llmCallCount")) : eventLlmCalls;
            int providerCalls = metrics.containsKey("providerCallCount")
                    ? intValue(metrics.get("providerCallCount")) : eventProviderCalls;
            boolean invoked = providerCalls > 0;
            boolean fallback = number(metrics.get("fallbackCount")) > 0
                    || llmCalls != providerCalls;
            return new TraceUsage(invoked, llmCalls, providerCalls, fallback,
                    longValue(metrics.get("promptTokens")),
                    longValue(metrics.get("completionTokens")), longValue(metrics.get("totalTokens")),
                    String.valueOf(metrics.getOrDefault("usageStatus",
                            invoked ? "unavailable" : "unavailable_provider_not_invoked")),
                    budgetInvalidReason(calls, metrics));
        }
        boolean invoked = eventProviderCalls > 0;
        boolean fallback = eventLlmCalls != eventProviderCalls || calls.stream().anyMatch(call ->
                call.containsKey("status") && !"success".equals(call.get("status")));
        boolean complete = !calls.isEmpty() && calls.stream().allMatch(call ->
                call.get("promptTokens") instanceof Number && call.get("completionTokens") instanceof Number
                        && call.get("totalTokens") instanceof Number);
        Long prompt = complete ? calls.stream().mapToLong(call -> ((Number) call.get("promptTokens")).longValue()).sum() : null;
        Long completion = complete ? calls.stream().mapToLong(call -> ((Number) call.get("completionTokens")).longValue()).sum() : null;
        Long total = complete ? calls.stream().mapToLong(call -> ((Number) call.get("totalTokens")).longValue()).sum() : null;
        return new TraceUsage(invoked, eventLlmCalls, eventProviderCalls, fallback, prompt, completion, total,
                complete ? "available" : invoked ? "partial_or_unavailable" : "unavailable_provider_not_invoked",
                budgetInvalidReason(calls, Map.of()));
    }

    private static String budgetInvalidReason(List<Map<String, Object>> calls, Map<String, Object> metrics) {
        for (Map<String, Object> call : calls) {
            String reason = String.valueOf(call.getOrDefault("fallbackReason", ""));
            if ("llm_budget_exhausted".equals(reason) || "llm_budget_unavailable".equals(reason)) {
                return reason;
            }
        }
        if (number(metrics.get("budgetBlockedCalls")) > 0) return "llm_budget_exhausted";
        return null;
    }

    private static String experimentInvalidReason(TraceUsage traceUsage, RuntimeException error) {
        if (traceUsage.invalidReason() != null) return traceUsage.invalidReason();
        String message = error == null ? null : error.getMessage();
        if ("llm_budget_exhausted".equals(message) || "llm_budget_unavailable".equals(message)) {
            return message;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static boolean containsRequired(List<String> tools, AgentBenchmarkDataset.Case testCase) {
        if (!tools.containsAll(testCase.expectedRequiredTools())) return false;
        Object raw = testCase.constraints().get("minimumToolCounts");
        if (!(raw instanceof Map<?, ?> counts)) return true;
        Map<String, Long> actual = tools.stream().collect(Collectors.groupingBy(value -> value,
                LinkedHashMap::new, Collectors.counting()));
        for (Map.Entry<?, ?> entry : counts.entrySet()) {
            int expected = entry.getValue() instanceof Number number ? number.intValue() : 0;
            if (actual.getOrDefault(String.valueOf(entry.getKey()), 0L) < expected) return false;
        }
        return true;
    }

    @SuppressWarnings("unchecked")
    private static boolean containsRequiredCapabilities(
            List<String> capabilities, AgentBenchmarkDataset.Case testCase) {
        if (!capabilities.containsAll(testCase.requiredCapabilities())) return false;
        Object raw = testCase.constraints().get("minimumCapabilityCounts");
        if (!(raw instanceof Map<?, ?> counts)) return true;
        Map<String, Long> actual = capabilities.stream().collect(Collectors.groupingBy(value -> value,
                LinkedHashMap::new, Collectors.counting()));
        for (Map.Entry<?, ?> entry : counts.entrySet()) {
            int expected = entry.getValue() instanceof Number number ? number.intValue() : 0;
            if (actual.getOrDefault(String.valueOf(entry.getKey()), 0L) < expected) return false;
        }
        return true;
    }

    private static List<String> observedCapabilities(List<String> tools) {
        List<String> capabilities = new ArrayList<>();
        for (String tool : tools) {
            String capability = capability(tool);
            if (capability != null) capabilities.add(capability);
        }
        return List.copyOf(capabilities);
    }

    private static String capability(String tool) {
        return switch (tool) {
            case "get_user_profile" -> "profile";
            case "get_recent_orders" -> "order_context";
            case "load_campaign_constraints" -> "campaign_constraints";
            case "search_products" -> "product_recall";
            case "check_fulfillment" -> "market_fulfillment";
            case "check_inventory" -> "inventory";
            case "rerank", "filter_products" -> "ranking";
            case "generate_localized_copy" -> "localized_copy";
            case "generate_retention_copy" -> "retention_copy";
            case "final_answer" -> "final_decision";
            default -> null;
        };
    }

    private static List<String> observedTools(AgentLoopResponse response, List<AgentRunEvent> events) {
        if (response != null && response.getToolCalls() != null) {
            return response.getToolCalls().stream().map(ToolCallRecord::getToolName).toList();
        }
        List<String> tools = new ArrayList<>();
        for (AgentRunEvent event : events == null ? List.<AgentRunEvent>of() : events) {
            if (!"tool.started".equals(event.getName()) || event.getData() == null) continue;
            Object tool = event.getData().get("tool");
            if (tool != null) tools.add(String.valueOf(tool));
        }
        return List.copyOf(tools);
    }

    private static String outcome(AgentLoopResponse response, RuntimeException error) {
        if (error instanceof RunDeadlineExceededException) return "timeout";
        if (error != null) return "failed";
        if (response == null) return "failed";
        if ("completed".equals(response.getStatus())) return productCount(response) == 0
                ? "completed_empty" : "completed";
        if ("blocked".equals(response.getStatus())) return "blocked";
        return "failed";
    }

    private static String observedFailure(AgentLoopResponse response, RuntimeException error) {
        if (error instanceof RunDeadlineExceededException) return "run_deadline_exceeded";
        if (error != null) return safe(error.getMessage());
        return response == null ? "missing_response" : safe(response.getStopReason());
    }

    private static int productCount(AgentLoopResponse response) {
        RecommendationPlan plan = response == null ? null : response.getPlan();
        return plan == null || plan.getProducts() == null ? 0 : plan.getProducts().size();
    }

    private static int minProducts(AgentBenchmarkDataset.Case testCase) {
        Object value = testCase.constraints().get("minProducts");
        return value instanceof Number number ? number.intValue() : 0;
    }

    private static boolean flag(AgentBenchmarkDataset.Case testCase, String name) {
        return Boolean.TRUE.equals(testCase.constraints().get(name));
    }

    private static int intConstraint(AgentBenchmarkDataset.Case testCase, String name, int fallback) {
        Object value = testCase.constraints().get(name);
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private static boolean guardRejected(ToolCallRecord call) {
        return "blocked".equals(call.getStatus()) || "stale_rejected".equals(call.getStatus())
                || (call.getErrorMessage() != null && (call.getErrorMessage().contains("not_whitelisted")
                || call.getErrorMessage().contains("scope_violation")));
    }

    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
    private static int intValue(Object value) { return value instanceof Number number ? number.intValue() : 0; }
    private static Long longValue(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static String safe(String value) { return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9:_-]", "_"); }

    private record TraceUsage(boolean providerInvoked, int llmCallCount, int providerCallCount,
                              boolean fallbackDetected,
                              Long promptTokens, Long completionTokens, Long totalTokens,
                              String usageStatus, String invalidReason) {}

    private record InvariantResult(boolean valid, String reason) {
        static InvariantResult ok() { return new InvariantResult(true, null); }
        static InvariantResult fail(String reason) { return new InvariantResult(false, reason); }
    }
}
