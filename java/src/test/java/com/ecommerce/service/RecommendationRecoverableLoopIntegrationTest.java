package com.ecommerce.service;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.runtime.DynamicSubAgentRuntimeRegistry;
import com.ecommerce.runtime.persistence.RecommendationRecoveryProperties;
import com.ecommerce.runtime.persistence.RecommendationRunEntity;
import com.ecommerce.runtime.persistence.RecommendationRunEventEntity;
import com.ecommerce.runtime.persistence.RecommendationRunEventRepository;
import com.ecommerce.runtime.persistence.RecommendationRunRepository;
import com.ecommerce.runtime.persistence.RecommendationTaskEntity;
import com.ecommerce.runtime.persistence.RecommendationTaskRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises the complete Spring-injected loop and its real H2/JPA persistence,
 * instead of the compatibility constructors used by isolated loop unit tests.
 * RULES, zero budgets and loopback model endpoints guarantee no paid model calls.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recoverable_loop_full;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.sql.init.mode=never",
        "spring.ai.openai.api-key=offline-integration-test-only",
        "spring.ai.openai.base-url=http://127.0.0.1:1",
        "spring.data.redis.host=127.0.0.1",
        "spring.data.redis.port=1",
        "spring.data.redis.timeout=50ms",
        "spring.data.redis.connect-timeout=50ms",
        "agent.demo.seed-enabled=true",
        "agent.security.internal-api.enabled=false",
        "agent.recommend.mode=RULES",
        "agent.recommend.live-enabled=false",
        "agent.recommend.max-llm-calls=0",
        "agent.supervisor.max-llm-calls=0",
        "agent.embedding.live-enabled=false",
        "agent.embedding.max-calls=0",
        "agent.aftersales.max-llm-calls=0",
        "agent.recovery.enabled=true",
        "agent.recovery.worker-id=full-loop-integration-worker",
        "agent.recovery.scan-interval=1h",
        "agent.recovery.heartbeat-interval=5s",
        "agent.recovery.lease-duration=30s",
        "agent.persistence.outbox-scan-ms=600000"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RecommendationRecoverableLoopIntegrationTest {
    @Autowired AutonomousAgentLoopService loop;
    @Autowired RecommendationRunRepository runs;
    @Autowired RecommendationTaskRepository tasks;
    @Autowired RecommendationRunEventRepository events;
    @Autowired DynamicSubAgentRuntimeRegistry registry;
    @Autowired RecommendationRecoveryProperties recovery;
    @Autowired RecommendationLeaseHeartbeatService heartbeats;
    @Autowired ObjectMapper mapper;

    @ParameterizedTest(name = "{0}: managed execution persists exactly one complete answer")
    @ValueSource(strings = {"homepage", "campaign", "retention"})
    void fullAutowiredLoopPersistsAttemptAndOneTerminalAnswer(String scene) throws Exception {
        String runId = "managed-" + scene + "-" + UUID.randomUUID();
        RecommendationRequest request = RecommendationRequest.builder()
                .userId("user_001").scene(scene).numItems(3)
                .platform("shopify").region("SEA").country("SG").locale("en-SG").currency("SGD")
                .context(Map.of("campaign_id", "SEA-2026", "campaign_objective", "conversion",
                        "max_delivery_days", 7))
                .build();

        assertThat(recovery.isEnabled()).isTrue();
        AgentLoopResponse response = loop.run(ToolLoopRequest.builder()
                .runId(runId).request(request).build());

        assertThat(response.getStatus()).as("scene %s", scene).isEqualTo("completed");
        assertThat(response.getStopReason()).isEqualTo("final_answer");
        assertThat(response.getPlan()).isNotNull();
        assertThat(response.getPlan().getProducts()).isNotEmpty();
        assertThat(response.getLlmMetrics()).containsEntry("llmCallCount", 0);

        RecommendationRunEntity stored = runs.findById(runId).orElseThrow();
        assertThat(stored.isRecoverable()).isTrue();
        assertThat(stored.getExecutionAttempt()).isEqualTo(1);
        assertThat(stored.getStatus()).isEqualTo("COMPLETED");
        assertThat(stored.getCompletedAt()).isNotNull();
        assertThat(stored.getExecutionOwner()).isEqualTo(recovery.getWorkerId());
        assertThat(heartbeats.activeCount()).isZero();

        List<RecommendationTaskEntity> storedTasks = tasks.findByRunIdOrderByCreatedAtAsc(runId);
        assertThat(storedTasks).isNotEmpty().allSatisfy(task -> {
            assertThat(task.getId()).startsWith(runId + ":attempt:1:subagent:");
            assertThat(task.getStatus()).isEqualTo("COMPLETED");
            assertThat(task.getCompletedAt()).isNotNull();
        });

        List<RecommendationRunEventEntity> history = events.findByRunIdOrderBySequenceAsc(runId);
        List<RecommendationRunEventEntity> terminalEvents = history.stream()
                .filter(event -> "run.completed".equals(event.getName()) || "run.failed".equals(event.getName()))
                .toList();
        assertThat(terminalEvents).hasSize(1);
        RecommendationRunEventEntity terminal = terminalEvents.get(0);
        assertThat(terminal.getName()).isEqualTo("run.completed");
        assertThat(terminal.getId()).isEqualTo(history.get(history.size() - 1).getId());
        JsonNode terminalData = mapper.readTree(terminal.getDataJson());
        assertThat(terminalData.path("final_answer")).isEqualTo(mapper.valueToTree(response.getPlan()));

        // Deliberately install a conflicting local view, as could remain after
        // takeover. Repeated queries must still return the durable completed run.
        DynamicSubAgentRuntime staleLocalView = registry.create(runId, 2);
        assertThat(staleLocalView.runView().status()).isEqualTo(DynamicSubAgentRuntime.RunStatus.RUNNING);
        DynamicSubAgentRuntime.RunView firstRead = registry.find(runId).orElseThrow();
        DynamicSubAgentRuntime.RunView secondRead = registry.find(runId).orElseThrow();
        assertThat(firstRead.status()).isEqualTo(DynamicSubAgentRuntime.RunStatus.COMPLETED);
        assertThat(firstRead.tasks()).hasSize(storedTasks.size());
        assertThat(secondRead).isEqualTo(firstRead);
        assertThat(events.findByRunIdOrderBySequenceAsc(runId)).hasSize(history.size());
        assertThat(runs.findById(runId).orElseThrow().getExecutionAttempt()).isEqualTo(1);
        assertThat(heartbeats.activeCount()).isZero();
    }
}
