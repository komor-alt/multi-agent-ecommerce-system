package com.ecommerce.service;

import com.ecommerce.config.RecommendationOrchestrationProperties;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AutonomousAgentLoopFailurePersistenceTest {

    @Test
    void unexpectedRuntimeFailureClosesRunAsFailedAndCompletesPersistentStream() {
        ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        when(chatClientBuilder.build()).thenReturn(mock(ChatClient.class));
        RecommendationRuntimeStore runtimeStore = mock(RecommendationRuntimeStore.class);
        RecommendationRunEventService eventService = mock(RecommendationRunEventService.class);
        AutonomousAgentLoopService service = new AutonomousAgentLoopService(
                mock(RecommendationPipelineExecutor.class),
                new ABTestService(),
                chatClientBuilder,
                new ScenePathEnforcer(),
                new RecommendationModeResolver("RULES", ""),
                0,
                (systemPrompt, userPrompt) -> "{}",
                new RecommendationOrchestrationProperties(),
                Runnable::run,
                null,
                runtimeStore,
                eventService);
        ToolLoopRequest request = ToolLoopRequest.builder()
                .runId("run-unexpected-failure")
                .request(RecommendationRequest.builder()
                        .userId("user-failure")
                        .scene("homepage")
                        .build())
                .build();

        assertThatThrownBy(() -> service.run(request, event -> {
            throw new IllegalStateException("client stream disconnected");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("client stream disconnected");

        verify(runtimeStore).startRun("run-unexpected-failure", request.getRequest());
        verify(runtimeStore).persist(argThat(view ->
                view.status() == DynamicSubAgentRuntime.RunStatus.FAILED
                        && view.stopReason().contains("client stream disconnected")));
        verify(eventService).complete("run-unexpected-failure");
    }
}
