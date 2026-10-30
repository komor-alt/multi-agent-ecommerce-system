package com.ecommerce.eval.benchmark;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.RecommendationPlan;
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
        String actualOutcome = outcome(response, error);
        boolean completed = actualOutcome.startsWith("completed");
        boolean requiredTools = containsRequired(tools, testCase);
        boolean forbidden = tools.stream().anyMatch(testCase.forbiddenTools()::contains);
        boolean marketViolation = response != null && response.getPlan() != null
                && (response.getPlan().getMarket() == null
                || !testCase.country().equalsIgnoreCase(response.getPlan().getMarket().getCountry()));
        boolean guardViolation = response != null && response.getToolCalls() != null
                && response.getToolCalls().stream().anyMatch(AgentBenchmarkEvaluator::guardRejected);
        boolean ruleViolation = forbidden || marketViolation || guardViolation;
        String observedFailure = completed ? null : observedFailure(response, error);
        TraceUsage traceUsage = traceUsage(response, events);
        String mismatch = expectationMismatch(testCase, response, actualOutcome, observedFailure, requiredTools,
                ruleViolation, requireProvider, durableTraceVerified, traceUsage);
        boolean providerInvoked = traceUsage.providerInvoked();
        Long prompt = traceUsage.promptTokens();
        Long completion = traceUsage.completionTokens();
        Long total = traceUsage.totalTokens();
        String usage = traceUsage.usageStatus();
        Double cost = prices == null ? null : prices.costUsd(prompt, completion, usage);
        return new AgentBenchmark.CaseObservation(testCase.id(), testCase.category(),
                testCase.expectedOutcome(), actualOutcome, completed, mismatch == null,
                ruleViolation, tools.size(), latencyMs, providerInvoked, usage,
                prompt, completion, total, cost, testCase.expectedRequiredTools(), tools,
                observedFailure, mismatch);
    }

    private static String expectationMismatch(
            AgentBenchmarkDataset.Case testCase, AgentLoopResponse response, String actualOutcome,
            String observedFailure, boolean requiredTools, boolean violation, boolean requireProvider,
            boolean durableTraceVerified, TraceUsage traceUsage) {
        if (!testCase.expectedOutcome().equals(actualOutcome)) {
            return "expected_" + testCase.expectedOutcome() + "_actual_" + actualOutcome;
        }
        if (testCase.expectedFailureReason() != null
                && (observedFailure == null || !observedFailure.contains(testCase.expectedFailureReason()))) {
            return "failure_reason_mismatch:" + safe(observedFailure);
        }
        if (!requiredTools) return "required_tool_path_missing";
        if (violation) return "rule_violation";
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
        if (!metrics.isEmpty()) {
            boolean invoked = number(metrics.get("providerCallCount")) > 0;
            boolean fallback = number(metrics.get("fallbackCount")) > 0
                    || number(metrics.get("llmCallCount")) != number(metrics.get("providerCallCount"));
            return new TraceUsage(invoked, fallback, longValue(metrics.get("promptTokens")),
                    longValue(metrics.get("completionTokens")), longValue(metrics.get("totalTokens")),
                    String.valueOf(metrics.getOrDefault("usageStatus",
                            invoked ? "unavailable" : "unavailable_provider_not_invoked")));
        }
        List<Map<String, Object>> calls = (events == null ? List.<AgentRunEvent>of() : events).stream()
                .filter(event -> "llm.call".equals(event.getName()) && event.getData() != null)
                .map(AgentRunEvent::getData).toList();
        boolean invoked = calls.stream().anyMatch(call -> Boolean.TRUE.equals(call.get("providerInvoked")));
        boolean fallback = calls.stream().anyMatch(call -> !"success".equals(call.get("status")));
        boolean complete = !calls.isEmpty() && calls.stream().allMatch(call ->
                call.get("promptTokens") instanceof Number && call.get("completionTokens") instanceof Number
                        && call.get("totalTokens") instanceof Number);
        Long prompt = complete ? calls.stream().mapToLong(call -> ((Number) call.get("promptTokens")).longValue()).sum() : null;
        Long completion = complete ? calls.stream().mapToLong(call -> ((Number) call.get("completionTokens")).longValue()).sum() : null;
        Long total = complete ? calls.stream().mapToLong(call -> ((Number) call.get("totalTokens")).longValue()).sum() : null;
        return new TraceUsage(invoked, fallback, prompt, completion, total,
                complete ? "available" : invoked ? "partial_or_unavailable" : "unavailable_provider_not_invoked");
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

    private static boolean guardRejected(ToolCallRecord call) {
        return "blocked".equals(call.getStatus()) || "stale_rejected".equals(call.getStatus())
                || (call.getErrorMessage() != null && (call.getErrorMessage().contains("not_whitelisted")
                || call.getErrorMessage().contains("scope_violation")));
    }

    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0L; }
    private static Long longValue(Object value) { return value instanceof Number number ? number.longValue() : null; }
    private static String safe(String value) { return value == null ? "unknown" : value.replaceAll("[^A-Za-z0-9:_-]", "_"); }

    private record TraceUsage(boolean providerInvoked, boolean fallbackDetected,
                              Long promptTokens, Long completionTokens, Long totalTokens,
                              String usageStatus) {}
}
