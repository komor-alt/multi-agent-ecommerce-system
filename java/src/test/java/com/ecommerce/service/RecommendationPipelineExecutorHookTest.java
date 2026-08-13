package com.ecommerce.service;

import com.ecommerce.agent.InventoryAgent;
import com.ecommerce.agent.MarketingCopyAgent;
import com.ecommerce.agent.ProductRecAgent;
import com.ecommerce.agent.UserProfileAgent;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.model.UserProfile;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RecommendationPipelineExecutorHookTest {

    @Test
    void executesRegisteredToolWithLifecycleHooks() {
        UserProfileAgent userProfileAgent = mock(UserProfileAgent.class);
        RecordingHook hook = new RecordingHook();
        RecommendationPipelineExecutor executor = new RecommendationPipelineExecutor(
                userProfileAgent,
                mock(ProductRecAgent.class),
                mock(InventoryAgent.class),
                mock(MarketingCopyAgent.class),
                Runnable::run,
                List.of(hook)
        );
        AgentResult profileResult = AgentResult.builder()
                .agentName("user_profile")
                .success(true)
                .data(Map.of("profile", UserProfile.builder().userId("user_001").build()))
                .build();
        when(userProfileAgent.runAsync(anyMap(), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(profileResult));

        RecommendationPipelineState state = new RecommendationPipelineState(
                "run_001",
                RecommendationRequest.builder().userId("user_001").country("SG").currency("SGD").build()
        );
        ToolObservation observation = executor.executeTool(RecommendationPipelineExecutor.GET_USER_PROFILE, state);

        assertThat(observation.getEvidenceIds()).containsExactly("profile:user_001");
        assertThat(state.getAgentResults()).containsKey(RecommendationPipelineExecutor.USER_PROFILE_RESULT);
        assertThat(executor.registeredToolNames()).contains("search_products", "check_inventory");
        assertThat(hook.beforeTools).containsExactly(RecommendationPipelineExecutor.GET_USER_PROFILE);
        assertThat(hook.afterTools).containsExactly(RecommendationPipelineExecutor.GET_USER_PROFILE);
        assertThat(hook.errors).isEmpty();
    }

    @Test
    void notifiesHookWhenToolExecutionFails() {
        UserProfileAgent userProfileAgent = mock(UserProfileAgent.class);
        RecordingHook hook = new RecordingHook();
        RecommendationPipelineExecutor executor = new RecommendationPipelineExecutor(
                userProfileAgent,
                mock(ProductRecAgent.class),
                mock(InventoryAgent.class),
                mock(MarketingCopyAgent.class),
                Runnable::run,
                List.of(hook)
        );
        AgentResult failed = AgentResult.builder()
                .agentName("user_profile")
                .success(false)
                .error("profile timeout")
                .build();
        when(userProfileAgent.runAsync(anyMap(), any(Executor.class)))
                .thenReturn(CompletableFuture.completedFuture(failed));

        RecommendationPipelineState state = new RecommendationPipelineState(
                "run_001",
                RecommendationRequest.builder().userId("user_001").build()
        );

        assertThatThrownBy(() -> executor.executeTool(RecommendationPipelineExecutor.GET_USER_PROFILE, state))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("profile timeout");
        assertThat(hook.beforeTools).containsExactly(RecommendationPipelineExecutor.GET_USER_PROFILE);
        assertThat(hook.afterTools).isEmpty();
        assertThat(hook.errors).containsExactly("profile timeout");
    }

    private static class RecordingHook implements RecommendationPipelineHook {
        private final List<String> beforeTools = new ArrayList<>();
        private final List<String> afterTools = new ArrayList<>();
        private final List<String> errors = new ArrayList<>();

        @Override
        public void beforeTool(String toolName, RecommendationPipelineState state, Map<String, Object> trustedArguments) {
            beforeTools.add(toolName);
            assertThat(trustedArguments).containsKeys("userId", "country", "currency");
        }

        @Override
        public void afterTool(String toolName, RecommendationPipelineState state, ToolObservation observation, double latencyMs) {
            afterTools.add(toolName);
            assertThat(latencyMs).isGreaterThanOrEqualTo(0.0);
        }

        @Override
        public void onToolError(String toolName, RecommendationPipelineState state, Exception error, double latencyMs) {
            errors.add(error.getMessage());
            assertThat(latencyMs).isGreaterThanOrEqualTo(0.0);
        }
    }
}
