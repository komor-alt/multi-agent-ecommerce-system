package com.ecommerce.eval.live;

import com.ecommerce.MultiAgentApplication;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.persistence.RecommendationRunEntity;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.runtime.persistence.RecommendationRunRepository;
import com.ecommerce.service.AutonomousAgentLoopService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Paid end-to-end test. Not selected by default Maven tests; invoke only with the
 * live-recommendation-e2e Maven profile and the explicit environment opt-in.
 */
@EnabledIfEnvironmentVariable(named = LiveRecommendationE2EGuard.RUN_ENV, matches = "(?i)true")
class LiveRecommendationE2ERunnerTest {

    @Test
    void runsRealLlmSupervisorSpecialistsToolsAndDurableSseEventsOnPostgres() {
        Map<String, String> env = System.getenv();
        LiveRecommendationE2EGuard.Settings settings = LiveRecommendationE2EGuard.requireConfiguration(env);
        LiveRecommendationE2EGuard.verifyPostgres(settings);

        String runId = "live-recommendation-e2e-" + UUID.randomUUID().toString().replace("-", "");
        String userId = env.getOrDefault("ECOM_RECOMMENDATION_E2E_USER_ID", "user_889");
        try (ConfigurableApplicationContext app = new SpringApplicationBuilder(MultiAgentApplication.class)
                .web(WebApplicationType.NONE)
                .properties(Map.of("agent.embedding.live-enabled", "false",
                        "agent.embedding.max-calls", "0", "agent.recommend.llm-timeout-ms", "15000",
                        "spring.datasource.driver-class-name", "org.postgresql.Driver"))
                .run()) {
            AutonomousAgentLoopService loop = app.getBean(AutonomousAgentLoopService.class);
            RecommendationRunEventService eventService = app.getBean(RecommendationRunEventService.class);
            RecommendationRunRepository runRepository = app.getBean(RecommendationRunRepository.class);

            ToolLoopRequest request = ToolLoopRequest.builder().runId(runId)
                    .request(RecommendationRequest.builder().userId(userId).scene("auto").numItems(2)
                            .context(Map.of("intentText", "Win back this customer using recent order context and localized retention recommendations."))
                            .build())
                    .config(ToolLoopConfig.builder().maxSteps(12)
                            .toolWhitelist(com.ecommerce.service.ScenePathEnforcer.DEFAULT_WHITELIST).build())
                    .build();

            AgentLoopResponse response = loop.run(request);
            assertThat(response.getStatus()).as(response.getStopReason()).isEqualTo("completed");
            assertThat(response.getPlan()).isNotNull();
            assertThat(response.getPlan().getScene()).isEqualTo("retention");
            assertThat(response.getPlan().getProducts()).isNotEmpty();
            assertThat(response.getPlan().getStrategy()).containsKey("businessDecision");
            Map<?, ?> decision = (Map<?, ?>) response.getPlan().getStrategy().get("businessDecision");
            assertThat(decision.get("source")).isEqualTo("llm");

            RecommendationRunEntity persistedRun = runRepository.findById(runId).orElseThrow();
            assertThat(persistedRun.getStatus()).isEqualTo("COMPLETED");
            assertThat(persistedRun.getScene()).isEqualTo("retention");

            List<Map<String, Object>> events = eventService.history(runId);
            assertThat(events).extracting(event -> event.get("name"))
                    .contains("intent.scene_resolved", "agent.assigned", "tool.started", "tool.completed",
                            "llm.call", "run.completed");
            assertSuccessfulPhase(events, "scene_intent");
            assertSuccessfulPhase(events, "supervisor");
            assertSuccessfulPhase(events, "specialist_profile");
            assertSuccessfulPhase(events, "user_profile");
            assertSuccessfulPhase(events, "product_rerank");
            assertSuccessfulPhase(events, "marketing_copy");
            assertSuccessfulPhase(events, "final_decision");

            Map<String, Object> snapshot = response.getLlmMetrics();
            assertThat(snapshot).containsKey("usageStatus").doesNotContainKey("apiKey");
            assertThat(snapshot).containsKeys("llmCallCount", "providerCallCount", "fallbackCount",
                    "promptTokens", "completionTokens", "totalTokens");
        }
    }

    private void assertSuccessfulPhase(List<Map<String, Object>> events, String phase) {
        List<? extends Map<?, ?>> phaseEvents = events.stream()
                .filter(event -> "llm.call".equals(event.get("name")))
                .map(event -> event.get("data"))
                .filter(Map.class::isInstance)
                .map(value -> (Map<?, ?>) value)
                .filter(data -> phase.equals(data.get("phase")))
                .toList();
        assertThat(phaseEvents).anySatisfy(data -> {
            assertThat(data.get("status")).isEqualTo("success");
            assertThat(data.get("providerInvoked")).isEqualTo(true);
        });
    }
}
