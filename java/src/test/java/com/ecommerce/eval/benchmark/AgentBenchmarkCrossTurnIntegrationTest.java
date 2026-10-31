package com.ecommerce.eval.benchmark;

import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.runtime.persistence.RecommendationRunEntity;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.runtime.persistence.RecommendationRunRepository;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.RecommendationPipelineHook;
import com.ecommerce.service.RecommendationRecoveryWorker;
import com.ecommerce.service.ScenePathEnforcer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benchmark_cross_turn;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
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
        "agent.recovery.worker-id=cross-turn-test-worker",
        "agent.recovery.scan-interval=1h",
        "agent.recovery.heartbeat-interval=5s",
        "agent.recovery.lease-duration=30s",
        "agent.persistence.outbox-scan-ms=600000"
})
@Import(AgentBenchmarkCrossTurnIntegrationTest.FaultConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AgentBenchmarkCrossTurnIntegrationTest {
    @Autowired AutonomousAgentLoopService loop;
    @Autowired RecommendationRuntimeStore runtimeStore;
    @Autowired RecommendationRunRepository runs;
    @Autowired RecommendationRunEventService events;
    @Autowired RecommendationRecoveryWorker recoveryWorker;
    @Autowired ObjectMapper mapper;

    @Test
    void persistsExplicitCrossTurnSourcesOverridesConflictsAndReplaysFullRequestForRecovery()
            throws Exception {
        AgentBenchmarkCrossTurnHarness harness = new AgentBenchmarkCrossTurnHarness(
                runtimeStore, runs, events, mapper);
        String prefix = "cross-turn-" + UUID.randomUUID().toString().substring(0, 8);

        RecommendationRequest firstRequest = RecommendationRequest.builder()
                .userId("user_001").scene("campaign").numItems(2)
                .platform("shopify").region("SEA").country("SG").locale("en-SG").currency("SGD")
                .context(Map.of("intentText", "Create a Singapore campaign plan",
                        "campaign_id", "Q4-SG", "campaign_objective", "conversion",
                        "max_delivery_days", 7))
                .build();
        AgentLoopResponse first = loop.run(request(prefix + "-1", firstRequest));
        assertThat(first.getStatus()).isEqualTo("completed");
        assertThat(runtimeStore.findRunView(first.getRunId()).orElseThrow().artifacts()).isNotEmpty();

        RecommendationRequest secondRequest = harness.continueFrom(first.getRunId(),
                new AgentBenchmarkCrossTurnHarness.TurnUpdate(
                        "Move the campaign to Thailand with tighter delivery",
                        "TH", null, "th-TH", "THB", null, 2,
                        Map.of("campaign_id", "Q4-TH", "max_delivery_days", 5)));
        assertHandoff(secondRequest, first.getRunId(), "COMPLETED", "SG", "TH", 5);
        String secondRunId = prefix + "-2";
        AgentLoopResponse second;
        try (AgentBenchmarkFaultRegistry.Scope ignored = AgentBenchmarkFaultRegistry.bind(secondRunId,
                new AgentBenchmarkDataset.FaultPlan("tool_failure", "generate_localized_copy", 1))) {
            second = loop.run(request(secondRunId, secondRequest));
        }
        assertThat(second.getStatus()).isEqualTo("failed");
        assertThat(runs.findById(secondRunId).orElseThrow().getStatus()).isEqualTo("FAILED");
        assertThat(events.history(secondRunId)).extracting(row -> row.get("name")).contains("run.failed");

        RecommendationRequest thirdRequest = harness.continueFrom(secondRunId,
                new AgentBenchmarkCrossTurnHarness.TurnUpdate(
                        "Recover with Singapore constraints and a stricter delivery limit",
                        "SG", null, "en-SG", "SGD", null, 2,
                        Map.of("campaign_id", "Q4-SG-RECOVERY", "max_delivery_days", 4)));
        assertHandoff(thirdRequest, secondRunId, "FAILED", "TH", "SG", 4);
        AgentLoopResponse third = loop.run(request(prefix + "-3", thirdRequest));
        assertThat(third.getStatus()).isEqualTo("completed");
        assertThat(third.getPlan().getMarket().getCountry()).isEqualTo("SG");
        assertThat(AgentBenchmarkFaultRegistry.activeBindings()).isZero();

        RecommendationRequest recoveredRequest = harness.continueFrom(third.getRunId(),
                new AgentBenchmarkCrossTurnHarness.TurnUpdate(
                        "Continue the recovered campaign with the persisted evidence",
                        null, null, null, null, null, 2,
                        Map.of("max_delivery_days", 3)));
        ToolLoopRequest prepared = loop.prepareRun(request(prefix + "-4", recoveredRequest));
        RecommendationRunEntity queued = runs.findById(prepared.getRunId()).orElseThrow();
        assertThat(queued.getExecutionAttempt()).isZero();
        ToolLoopRequest persistedReplay = mapper.readValue(queued.getExecutionRequestJson(), ToolLoopRequest.class);
        assertThat(persistedReplay.getRunId()).isEqualTo(prepared.getRunId());
        assertThat(persistedReplay.getRequest().getContext()).containsEntry("max_delivery_days", 3);
        assertThat(persistedReplay.getConfig().getMaxSteps()).isEqualTo(20);
        assertThat(persistedReplay.getConfig().getToolWhitelist())
                .containsExactlyInAnyOrderElementsOf(ScenePathEnforcer.DEFAULT_WHITELIST);
        recoveryWorker.scan();
        RecommendationRunEntity recovered = awaitTerminal(prepared.getRunId(), Duration.ofSeconds(10));
        assertThat(recovered.getStatus()).isEqualTo("COMPLETED");
        assertThat(recovered.getExecutionAttempt()).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> persistedHandoff = (Map<String, Object>) persistedContext(prepared.getRunId())
                .get(AgentBenchmarkCrossTurnHarness.STATE_KEY);
        assertThat(persistedHandoff).containsEntry("sourceRunId", third.getRunId());
    }

    @SuppressWarnings("unchecked")
    private void assertHandoff(RecommendationRequest request, String sourceRunId, String sourceStatus,
                               String previousCountry, String currentCountry, int deliveryDays) {
        assertThat(request.getCountry()).isEqualTo(currentCountry).isNotEqualTo(previousCountry);
        assertThat(request.getContext()).containsEntry("max_delivery_days", deliveryDays);
        Map<String, Object> state = (Map<String, Object>) request.getContext()
                .get(AgentBenchmarkCrossTurnHarness.STATE_KEY);
        assertThat(state).containsEntry("sourceRunId", sourceRunId)
                .containsEntry("sourceStatus", sourceStatus);
        assertThat((Map<String, Object>) state.get("sourceRunRevision"))
                .containsKeys("stateVersion", "executionAttempt")
                .containsEntry("requestInherited", true);
        assertThat((List<Map<String, Object>>) state.get("sourceTasks")).isNotEmpty();
        assertThat((List<Map<String, Object>>) state.get("sourceArtifacts")).isNotEmpty();
        assertThat(state).containsKeys("sourceEventId", "sourceEventSequence", "sourceEventName");
        assertThat((List<String>) state.get("overriddenKeys"))
                .contains("country", "currency", "locale", "context.max_delivery_days");
    }

    private ToolLoopRequest request(String runId, RecommendationRequest request) {
        return ToolLoopRequest.builder().runId(runId).request(request)
                .config(ToolLoopConfig.builder().maxSteps(20)
                        .toolWhitelist(ScenePathEnforcer.DEFAULT_WHITELIST).build())
                .build();
    }

    private RecommendationRunEntity awaitTerminal(String runId, Duration timeout) throws InterruptedException {
        Instant deadline = Instant.now().plus(timeout);
        RecommendationRunEntity current;
        do {
            current = runs.findById(runId).orElseThrow();
            if (!"RUNNING".equals(current.getStatus())) return current;
            Thread.sleep(50);
        } while (Instant.now().isBefore(deadline));
        return current;
    }

    private Map<String, Object> persistedContext(String runId) throws Exception {
        RecommendationRunEntity run = runs.findById(runId).orElseThrow();
        RecommendationRequest request = mapper.readValue(run.getRequestJson(), RecommendationRequest.class);
        return mapper.convertValue(request.getContext(), new TypeReference<>() {});
    }

    @TestConfiguration
    static class FaultConfiguration {
        @Bean
        RecommendationPipelineHook benchmarkFaultHook() {
            return new AgentBenchmarkFaultHook();
        }
    }
}
