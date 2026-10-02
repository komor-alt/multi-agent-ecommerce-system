package com.ecommerce.runtime.persistence;

import com.ecommerce.config.RecommendationOrchestrationProperties;
import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.runtime.DynamicSubAgentRuntimeRegistry;
import com.ecommerce.service.RecommendationPipelineState;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:recommendation-runtime;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({
        RecommendationRuntimeStore.class,
        RecommendationRunEventService.class,
        RecommendationRuntimePersistenceIntegrationTest.JacksonConfiguration.class
})
class RecommendationRuntimePersistenceIntegrationTest {

    @Autowired
    private RecommendationRuntimeStore runtimeStore;

    @Autowired
    private RecommendationRunEventService eventService;

    @Autowired
    private RecommendationOutboxRepository outboxRepository;

    @Test
    void persistsRunTaskArtifactAndReconstructsViewWithoutInMemoryRuntime() {
        String runId = "recommendation-runtime-persistence";
        RecommendationRequest request = RecommendationRequest.builder()
                .userId("user-42")
                .scene("homepage")
                .build();
        RecommendationPipelineState state = new RecommendationPipelineState(runId, request);
        DynamicSubAgentRuntime runtime = new DynamicSubAgentRuntime(runId);

        runtimeStore.startRun(runId, request);
        DynamicSubAgentRuntime.TaskHandle task = runtime.spawn(
                new DynamicSubAgentRuntime.SubAgentSpec(
                        "PROFILE", AgentId.PROFILE, "Load profile evidence",
                        List.of("load_profile"), 2, "load_profile"),
                state);
        DynamicSubAgentRuntime.RunView pendingSnapshot = runtime.runView();
        runtimeStore.persist(pendingSnapshot);

        runtime.markRunning(task);
        DynamicSubAgentRuntime.RunView runningSnapshot = runtime.runView();
        runtimeStore.persist(runningSnapshot);
        runtime.finish(task, true, "completed", List.of(ToolObservation.builder()
                .toolName("load_profile")
                .summary("profile loaded")
                .data(Map.of("segment", "repeat-buyer"))
                .evidenceIds(List.of("evidence-profile-1"))
                .build()));
        runtime.finishRun("completed", "final_answer");
        runtimeStore.persist(runtime.runView());

        // A slow parallel writer may arrive with an older full projection.
        // Persisted lifecycle state must never move backwards.
        runtimeStore.persist(runningSnapshot);
        runtimeStore.persist(pendingSnapshot);

        DynamicSubAgentRuntime.RunView restored = runtimeStore.findRunView(runId).orElseThrow();
        assertThat(restored.status()).isEqualTo(DynamicSubAgentRuntime.RunStatus.COMPLETED);
        assertThat(restored.stopReason()).isEqualTo("final_answer");
        assertThat(restored.tasks()).singleElement().satisfies(persistedTask -> {
            assertThat(persistedTask.taskId()).isEqualTo(task.taskId());
            assertThat(persistedTask.status()).isEqualTo(DynamicSubAgentRuntime.TaskStatus.COMPLETED);
            assertThat(persistedTask.context().state()).containsEntry("userId", "user-42");
            assertThat(persistedTask.artifactIds()).containsExactly(task.taskId() + ":artifact:1");
        });
        assertThat(restored.artifacts()).singleElement().satisfies(artifact -> {
            assertThat(artifact.type()).isEqualTo("load_profile");
            assertThat(artifact.data()).containsEntry("segment", "repeat-buyer");
            assertThat(artifact.evidenceIds()).containsExactly("evidence-profile-1");
        });

        RecommendationOrchestrationProperties properties = new RecommendationOrchestrationProperties();
        DynamicSubAgentRuntimeRegistry restartedRegistry =
                new DynamicSubAgentRuntimeRegistry(properties, runtimeStore);
        assertThat(restartedRegistry.find(runId)).contains(restored);

        assertThat(outboxRepository.count()).isEqualTo(7);
        assertThat(outboxRepository.findAll())
                .extracting(RecommendationOutboxEntity::getDedupKey)
                .doesNotHaveDuplicates();
    }

    @Test
    void storesOrderedEventsForReconnectReplayAndRejectsDuplicateRunIds() {
        String runId = "recommendation-event-replay";
        RecommendationRequest request = RecommendationRequest.builder()
                .userId("user-events")
                .scene("campaign")
                .build();
        runtimeStore.startRun(runId, request);

        eventService.append(event(runId, "event-1", 1, "run.started"));
        eventService.append(event(runId, "event-2", 2, "agent.completed"));

        assertThat(eventService.history(runId))
                .extracting(row -> row.get("eventId"))
                .containsExactly("event-1", "event-2");
        assertThat(eventService.history(runId))
                .extracting(row -> row.get("sequence"))
                .containsExactly(1, 2);
        assertThatThrownBy(() -> runtimeStore.startRun(runId, request))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RECOMMENDATION_RUN_ALREADY_EXISTS");
    }

    private static AgentRunEvent event(String runId, String id, int sequence, String name) {
        return AgentRunEvent.builder()
                .eventId(id)
                .requestId(runId)
                .sequence(sequence)
                .type("runtime")
                .name(name)
                .status("success")
                .summary(name)
                .data(Map.of("sequence", sequence))
                .elapsedMs(sequence)
                .timestamp(Instant.parse("2026-09-25T00:00:0" + sequence + "Z"))
                .build();
    }

    @TestConfiguration
    static class JacksonConfiguration {
        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }
    }
}
