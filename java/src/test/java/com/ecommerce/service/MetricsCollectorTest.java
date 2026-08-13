package com.ecommerce.service;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.RecommendationResponse;
import com.ecommerce.model.ToolCallRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsCollectorTest {

    @Test
    @SuppressWarnings("unchecked")
    void recordsProtectionMetricsForRejectedTimeoutAndBlockedToolCalls() {
        MetricsCollector collector = new MetricsCollector();
        collector.recordRejectedRun("agent_loop");
        collector.recordRecommendation(RecommendationResponse.builder()
                .country("SG")
                .currency("SGD")
                .agentResults(Map.of("profile", AgentResult.builder()
                        .agentName("profile")
                        .success(false)
                        .error("timeout after 1000 ms")
                        .build()))
                .build());
        collector.recordToolCalls(List.of(ToolCallRecord.builder()
                .toolName("delete_order")
                .status("blocked")
                .errorMessage("tool_not_whitelisted")
                .build()));

        Map<String, Object> snapshot = collector.snapshot();
        Map<String, Object> protection = (Map<String, Object>) snapshot.get("protection");

        assertThat(protection).containsEntry("rejected_runs", 1);
        assertThat(protection).containsEntry("timed_out_agents", 1);
        assertThat(protection).containsEntry("blocked_tool_calls", 1);
        assertThat((Map<String, Object>) snapshot.get("cross_border")).containsEntry("SG:SGD", 1);
    }
}
