package com.ecommerce.service;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.RecommendationResponse;
import com.ecommerce.model.ToolCallRecord;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Service
public class MetricsCollector {

    private final Map<String, AgentMetric> agentMetrics = new ConcurrentHashMap<>();
    private final Map<String, ToolMetric> toolMetrics = new ConcurrentHashMap<>();
    private final Map<String, AtomicInteger> crossBorderRequests = new ConcurrentHashMap<>();
    private final AtomicInteger recommendationCount = new AtomicInteger();
    private final AtomicLong totalRecommendationLatency = new AtomicLong();
    private final AtomicLong estimatedTokenCost = new AtomicLong();
    private final AtomicInteger rejectedRuns = new AtomicInteger();
    private final AtomicInteger timedOutAgents = new AtomicInteger();
    private final AtomicInteger blockedToolCalls = new AtomicInteger();

    public void recordRecommendation(RecommendationResponse response) {
        if (response == null) {
            return;
        }
        recommendationCount.incrementAndGet();
        totalRecommendationLatency.addAndGet(Math.round(response.getTotalLatencyMs()));
        crossBorderRequests.computeIfAbsent(response.getCountry() + ":" + response.getCurrency(), ignored -> new AtomicInteger()).incrementAndGet();
        if (response.getAgentResults() == null) {
            return;
        }
        response.getAgentResults().forEach((name, result) -> {
            agentMetrics.computeIfAbsent(name, ignored -> new AgentMetric()).record(result);
            if (!result.isSuccess() && containsIgnoreCase(result.getError(), "timeout")) {
                timedOutAgents.incrementAndGet();
            }
            estimatedTokenCost.addAndGet(estimateTokens(result));
        });
    }

    public void recordToolCalls(List<ToolCallRecord> toolCalls) {
        if (toolCalls == null) {
            return;
        }
        toolCalls.forEach(record -> {
            toolMetrics.computeIfAbsent(record.getToolName(), ignored -> new ToolMetric()).record(record);
            if ("blocked".equals(record.getStatus())) {
                blockedToolCalls.incrementAndGet();
            }
            if ("failed".equals(record.getStatus()) && containsIgnoreCase(record.getErrorMessage(), "timeout")) {
                timedOutAgents.incrementAndGet();
            }
        });
    }

    public void recordRejectedRun(String requestType) {
        rejectedRuns.incrementAndGet();
    }

    public void recordTimedOutAgent(String agentName) {
        timedOutAgents.incrementAndGet();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> agents = new LinkedHashMap<>();
        agentMetrics.forEach((name, metric) -> agents.put(name, metric.snapshot()));
        Map<String, Object> tools = new LinkedHashMap<>();
        toolMetrics.forEach((name, metric) -> tools.put(name, metric.snapshot()));
        Map<String, Object> crossBorder = new LinkedHashMap<>();
        crossBorderRequests.forEach((key, count) -> crossBorder.put(key, count.get()));
        int total = recommendationCount.get();
        return Map.of(
                "recommendations", Map.of(
                        "call_count", total,
                        "avg_latency_ms", total == 0 ? 0.0 : Math.round((double) totalRecommendationLatency.get() / total * 10.0) / 10.0,
                        "estimated_token_cost", estimatedTokenCost.get()
                ),
                "protection", Map.of(
                        "rejected_runs", rejectedRuns.get(),
                        "timed_out_agents", timedOutAgents.get(),
                        "blocked_tool_calls", blockedToolCalls.get()
                ),
                "cross_border", crossBorder,
                "agents", agents,
                "tools", tools
        );
    }

    private long estimateTokens(AgentResult result) {
        if (result == null || result.getData() == null) {
            return 0;
        }
        return Math.max(1, result.getData().toString().length() / 4);
    }

    private boolean containsIgnoreCase(String value, String pattern) {
        return value != null && value.toLowerCase().contains(pattern.toLowerCase());
    }

    private static class AgentMetric {
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicInteger successCount = new AtomicInteger();
        private final AtomicLong totalLatency = new AtomicLong();
        private volatile String lastError = "";

        void record(AgentResult result) {
            callCount.incrementAndGet();
            if (result.isSuccess()) {
                successCount.incrementAndGet();
            } else {
                lastError = result.getError() == null ? "" : result.getError();
            }
            totalLatency.addAndGet(Math.round(result.getLatencyMs()));
        }

        Map<String, Object> snapshot() {
            int calls = callCount.get();
            return Map.of(
                    "call_count", calls,
                    "success_rate", calls == 0 ? 0.0 : Math.round((double) successCount.get() / calls * 10000.0) / 10000.0,
                    "avg_latency_ms", calls == 0 ? 0.0 : Math.round((double) totalLatency.get() / calls * 10.0) / 10.0,
                    "last_error", lastError
            );
        }
    }

    private static class ToolMetric {
        private final AtomicInteger callCount = new AtomicInteger();
        private final AtomicInteger successCount = new AtomicInteger();
        private final AtomicLong totalLatency = new AtomicLong();
        private volatile String lastBlockedOrError = "";

        void record(ToolCallRecord record) {
            callCount.incrementAndGet();
            if ("success".equals(record.getStatus())) {
                successCount.incrementAndGet();
            } else {
                lastBlockedOrError = record.getErrorMessage() == null ? record.getStatus() : record.getErrorMessage();
            }
            totalLatency.addAndGet(Math.round(record.getLatencyMs()));
        }

        Map<String, Object> snapshot() {
            int calls = callCount.get();
            return Map.of(
                    "call_count", calls,
                    "success_rate", calls == 0 ? 0.0 : Math.round((double) successCount.get() / calls * 10000.0) / 10000.0,
                    "avg_latency_ms", calls == 0 ? 0.0 : Math.round((double) totalLatency.get() / calls * 10.0) / 10.0,
                    "last_blocked_or_error", lastBlockedOrError
            );
        }
    }
}
