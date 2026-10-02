package com.ecommerce.service;

import com.ecommerce.agent.InventoryAgent;
import com.ecommerce.agent.MarketingCopyAgent;
import com.ecommerce.agent.ProductRecAgent;
import com.ecommerce.agent.UserProfileAgent;
import com.ecommerce.config.RecommendationOrchestrationProperties;
import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentMessage;
import com.ecommerce.model.AgentMessageType;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.BlackboardField;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutonomousAgentLoopServiceTest {

    @Test
    void managedLoopPublishesTerminalOnlyAfterFencedCommitAndUsesAttemptTaskIds() {
        var store = mock(com.ecommerce.runtime.persistence.RecommendationRuntimeStore.class);
        var eventStore = mock(com.ecommerce.runtime.persistence.RecommendationRunEventService.class);
        var heartbeat = new RecommendationLeaseHeartbeatService(store);
        var service = new TestFixture().managedService(store, eventStore, heartbeat);
        when(store.startAndClaimRecoverableRun(any(), any())).thenAnswer(invocation -> {
            ToolLoopRequest request = invocation.getArgument(0);
            return new com.ecommerce.runtime.persistence.RecommendationExecutionLease(
                    request.getRunId(), "test-token", 2, "test-owner", request);
        });
        var events = new ArrayList<AgentRunEvent>();
        var committed = new AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> {
            DynamicSubAgentRuntime.RunView view = invocation.getArgument(0);
            AgentRunEvent terminal = invocation.getArgument(2);
            assertThat(view.tasks()).allMatch(task -> task.taskId().contains(":attempt:2:subagent:"));
            if (terminal != null) {
                assertThat(events).noneMatch(event -> "run.completed".equals(event.getName()));
                assertThat(view.status()).isEqualTo(DynamicSubAgentRuntime.RunStatus.COMPLETED);
                assertThat(terminal.getData()).containsKey("final_answer");
                committed.set(true);
            }
            return null;
        }).when(store).persist(any(), any(), org.mockito.ArgumentMatchers.nullable(AgentRunEvent.class));
        AgentLoopResponse result = service.run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build()).build(), event -> {
            if ("run.completed".equals(event.getName())) assertThat(committed).isTrue();
            events.add(event);
        });
        assertThat(result.getStatus()).isEqualTo("completed");
        assertThat(events.stream().filter(event -> "run.completed".equals(event.getName()))).hasSize(1);
        assertThat(heartbeat.activeCount()).isZero();
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).persist(any());
        org.mockito.Mockito.verify(eventStore, org.mockito.Mockito.never()).append(any());
    }

    @Test
    void staleExecutionCannotEmitTerminalOrFailItsReplacement() {
        var store = mock(com.ecommerce.runtime.persistence.RecommendationRuntimeStore.class);
        var eventStore = mock(com.ecommerce.runtime.persistence.RecommendationRunEventService.class);
        var heartbeat = new RecommendationLeaseHeartbeatService(store);
        var properties = new RecommendationOrchestrationProperties();
        properties.setMaxRetainedRuns(1);
        var registry = new com.ecommerce.runtime.DynamicSubAgentRuntimeRegistry(properties);
        var service = new TestFixture().managedService(store, eventStore, heartbeat, registry);
        var request = ToolLoopRequest.builder().runId("lost-owner")
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build()).build();
        var lease = new com.ecommerce.runtime.persistence.RecommendationExecutionLease(
                "lost-owner", "test-token", 1, "test-owner", request);
        when(store.startAndClaimRecoverableRun(any(), any())).thenReturn(lease);
        org.mockito.Mockito.doThrow(new com.ecommerce.runtime.persistence.StaleExecutionLeaseException("lost-owner"))
                .when(store).persist(any(), any(), org.mockito.ArgumentMatchers.isNull());
        var events = new ArrayList<AgentRunEvent>();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.run(request, events::add))
                .isInstanceOf(com.ecommerce.runtime.persistence.StaleExecutionLeaseException.class);
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).failRun(any(), any());
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).failRun(any(), any(), any());
        assertThat(events).noneMatch(event -> event.getName().equals("run.failed") || event.getName().equals("run.completed"));
        assertThat(heartbeat.activeCount()).isZero();
        assertThat(registry.find("lost-owner")).get().extracting(DynamicSubAgentRuntime.RunView::status)
                .isEqualTo(DynamicSubAgentRuntime.RunStatus.FAILED);
        registry.create("next-run", 1);
        assertThat(registry.find("lost-owner")).as("lost lease must not leave an unbounded RUNNING entry").isEmpty();
    }

    @Test
    void alreadyClaimedPreparedRunIsNotExecutedOrFailedTwice() {
        var store = mock(com.ecommerce.runtime.persistence.RecommendationRuntimeStore.class);
        var eventStore = mock(com.ecommerce.runtime.persistence.RecommendationRunEventService.class);
        var service = new TestFixture().managedService(store, eventStore, new RecommendationLeaseHeartbeatService(store));
        when(store.claimRun(any(), any())).thenReturn(java.util.Optional.empty());
        assertThat(service.runPrepared(ToolLoopRequest.builder().runId("already-owned").build())).isNull();
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).failRun(any(), any());
        org.mockito.Mockito.verifyNoInteractions(eventStore);
    }

    @Test
    void unknownCommitOutcomeDoesNotPublishAnUncommittedFailureToDirectSink() {
        var store = mock(com.ecommerce.runtime.persistence.RecommendationRuntimeStore.class);
        var eventStore = mock(com.ecommerce.runtime.persistence.RecommendationRunEventService.class);
        var service = new TestFixture().managedService(store, eventStore, new RecommendationLeaseHeartbeatService(store));
        when(store.startAndClaimRecoverableRun(any(), any())).thenAnswer(invocation -> {
            ToolLoopRequest request = invocation.getArgument(0);
            return new com.ecommerce.runtime.persistence.RecommendationExecutionLease(
                    request.getRunId(), "test-token", 1, "test-owner", request);
        });
        var committed = new AtomicBoolean();
        org.mockito.Mockito.doAnswer(invocation -> {
            AgentRunEvent terminal = invocation.getArgument(2);
            if (terminal == null) return null;
            if (committed.compareAndSet(false, true)) {
                throw new IllegalStateException("Simulated commit acknowledgement lost");
            }
            // The durable-store contract rejects an event that differs from the committed terminal.
            throw new IllegalStateException("Conflicting terminal event");
        }).when(store).persist(any(), any(), org.mockito.ArgumentMatchers.nullable(AgentRunEvent.class));
        var events = new ArrayList<AgentRunEvent>();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build()).build(), events::add))
                .isInstanceOf(IllegalStateException.class);
        assertThat(committed).isTrue();
        assertThat(events).noneMatch(event -> "run.failed".equals(event.getName()) || "run.completed".equals(event.getName()));
        org.mockito.Mockito.verify(store, org.mockito.Mockito.never()).failRun(any(), any());
    }

    @Test
    void supervisorDelegatesStructuredTasksToIndependentSpecialists() {
        AgentLoopResponse response = new TestFixture().service().run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build())
                .build());

        @SuppressWarnings("unchecked")
        List<AgentMessage> messages = (List<AgentMessage>) response.getLlmMetrics().get("collaborationMessages");
        assertThat(messages).extracting(AgentMessage::getType)
                .contains(AgentMessageType.DELEGATE, AgentMessageType.RESULT, AgentMessageType.COMPLETE);
        assertThat(messages.stream().filter(message -> message.getType() == AgentMessageType.DELEGATE)
                .map(AgentMessage::getTo).toList())
                .containsExactly(AgentId.PROFILE, AgentId.PRODUCT, AgentId.INVENTORY, AgentId.PRODUCT);
        assertThat(response.getPlan().getStrategy().get("mode")).isEqualTo("supervisor_multi_agent_loop");
    }

    @Test
    void fallbackPlannerRunsThoughtActionObservationLoop() {
        TestFixture fixture = new TestFixture();
        AutonomousAgentLoopService service = fixture.service();

        AgentLoopResponse response = service.run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build())
                .build());

        assertThat(response.getStatus()).as(response.toString()).isEqualTo("completed");
        assertThat(response.getStopReason()).isEqualTo("final_answer");
        assertThat(response.getThoughts()).isNotEmpty();
        assertThat(response.getObservations()).hasSize(4);
        assertThat(response.getPlan()).isNotNull();
        assertThat(response.getPlan().getScene()).isEqualTo("homepage");
        assertThat(response.getPlan().getMarketingCopies()).isEmpty();
        assertThat(response.getEvidences()).extracting("evidenceId")
                .contains("profile:user_001", "product:P001", "inventory:SG:P001");
        assertThat(response.getToolCalls()).extracting("toolName").containsExactly(
                "get_user_profile", "search_products", "check_inventory",
                "rerank", "final_answer");
    }

    @Test
    void campaignAndRetentionUseDifferentRequiredTools() {
        AgentLoopResponse campaign = new TestFixture().service().run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_208").scene("campaign").numItems(1)
                        .context(Map.of("campaign_id", "sea-88")).build()).build());
        assertThat(campaign.getStatus()).isEqualTo("completed");
        assertThat(campaign.getToolCalls()).extracting("toolName").containsExactly(
                "load_campaign_constraints", "search_products", "check_fulfillment",
                "check_inventory", "rerank", "generate_localized_copy", "final_answer");
        assertThat(campaign.getLlmMetrics().get("parallelBatchCount")).isEqualTo(2);
        assertThat(campaign.getLlmMetrics().get("parallelSpecialistCount")).isEqualTo(4);
        @SuppressWarnings("unchecked")
        List<DynamicSubAgentRuntime.SubAgentTaskView> campaignTasks =
                (List<DynamicSubAgentRuntime.SubAgentTaskView>) campaign.getLlmMetrics().get("subAgentTasks");
        assertThat(campaignTasks).hasSize(6).allMatch(task ->
                task.status() == DynamicSubAgentRuntime.TaskStatus.COMPLETED);
        assertThat(campaignTasks.stream().filter(task -> task.dependencies().isEmpty())).hasSize(2);
        assertThat(campaign.getLlmMetrics().get("subAgentArtifacts")).asList().hasSize(6);
        assertThat(campaign.getPlan().getScene()).isEqualTo("campaign");
        assertThat(campaign.getPlan().getMarketingCopies()).hasSize(1);

        AgentLoopResponse retention = new TestFixture().service().run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_889").scene("retention").numItems(1)
                        .context(Map.of("recent_orders", List.of(Map.of("order_id", "O-1")))).build()).build());
        assertThat(retention.getStatus()).isEqualTo("completed");
        assertThat(retention.getToolCalls()).extracting("toolName").containsExactly(
                "get_user_profile", "get_recent_orders", "search_products",
                "check_inventory", "rerank", "generate_retention_copy", "final_answer");
        assertThat(retention.getPlan().getScene()).isEqualTo("retention");
    }

    @Test
    void streamingLoopPropagatesGatewayRunIdAndEmitsIncrementalEvents() {
        TestFixture fixture = new TestFixture();
        List<AgentRunEvent> events = new ArrayList<>();
        AgentLoopResponse response = fixture.service().run(ToolLoopRequest.builder()
                .runId("gateway-run-123")
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build())
                .build(), events::add);

        assertThat(response.getRunId()).isEqualTo("gateway-run-123");
        assertThat(events).extracting(AgentRunEvent::getName)
                .contains("run.started", "agent.assigned", "planner.decision", "tool.started", "tool.completed", "observation", "run.completed");
        assertThat(events).allMatch(event -> "gateway-run-123".equals(event.getRequestId()));
        assertThat(events).extracting(AgentRunEvent::getSequence).isSorted().doesNotHaveDuplicates();
        assertThat(events.get(events.size() - 1).getData()).containsKeys("final_answer", "metrics");
    }
    @Test
    void blocksWhenPlannerSelectsToolOutsideWhitelist() {
        TestFixture fixture = new TestFixture();
        AutonomousAgentLoopService service = fixture.service();

        AgentLoopResponse response = service.run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build())
                .config(ToolLoopConfig.builder()
                        .maxSteps(3)
                        .toolWhitelist(List.of("search_products"))
                        .build())
                .build());

        assertThat(response.getStatus()).isEqualTo("blocked");
        assertThat(response.getStopReason()).isEqualTo("tool_not_whitelisted");
        assertThat(response.getToolCalls()).hasSize(1);
        assertThat(response.getToolCalls().get(0).getToolName()).isEqualTo("get_user_profile");
    }

    @Test
    void llmPickingCopyBeforeRecallFallsBackAndSpendsSupervisorBudgetOnce() {
        java.util.concurrent.atomic.AtomicInteger llmCalls = new java.util.concurrent.atomic.AtomicInteger();
        TestFixture fixture = new TestFixture();
        AutonomousAgentLoopService service = fixture.service(
                new RecommendationModeResolver("LLM", "sk-test", true),
                1,
                (systemPrompt, userPrompt) -> {
                    llmCalls.incrementAndGet();
                    return "{\"thought\":\"jump to copy\",\"agent\":\"COPY\"}";
                });

        AgentLoopResponse response = service.run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build())
                .build());

        assertThat(response.getStatus()).isEqualTo("completed");
        assertThat(response.getToolCalls()).extracting("toolName").startsWith("get_user_profile");
        assertThat(llmCalls.get()).isEqualTo(1);
        assertThat(response.getLlmMetrics().get("llmCallCount")).isEqualTo(1);
        @SuppressWarnings("unchecked")
        Map<String, Integer> byPhase = (Map<String, Integer>) response.getLlmMetrics().get("byPhase");
        assertThat(byPhase.get("supervisor")).isEqualTo(1);
        List<?> invalid = (List<?>) response.getLlmMetrics().get("invalidAgentSelections");
        assertThat(invalid).isNotEmpty();
        assertThat(invalid.get(0).toString()).contains("COPY").contains("PROFILE");
    }

    @Test
    void zeroSupervisorBudgetNeverCallsLlm() {
        java.util.concurrent.atomic.AtomicInteger llmCalls = new java.util.concurrent.atomic.AtomicInteger();
        TestFixture fixture = new TestFixture();
        AutonomousAgentLoopService service = fixture.service(
                new RecommendationModeResolver("LLM", "sk-test", true),
                0,
                (systemPrompt, userPrompt) -> {
                    llmCalls.incrementAndGet();
                    return "{\"thought\":\"should not run\",\"agent\":\"COPY\"}";
                });

        AgentLoopResponse response = service.run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build())
                .build());

        assertThat(response.getStatus()).isEqualTo("completed");
        assertThat(llmCalls.get()).isEqualTo(0);
        assertThat(response.getLlmMetrics().get("llmCallCount")).isEqualTo(0);
        // Parallel actions are selected by the server dependency planner, so they do not
        // spend or probe the LLM budget. Only genuinely model-plannable boundaries count.
        assertThat(response.getLlmMetrics().get("budgetBlockedCalls")).isEqualTo(5);
    }

    @Test
    void constraintVetoTriggersOneRerecallAndDropsVetoedProduct() {
        TestFixture fixture = new TestFixture();
        Product available = fixture.product("P001", "Phone");
        Product lowStock = fixture.product("P_LOW", "Low stock phone");
        Product replacement = fixture.product("P002", "Replacement phone");
        fixture.recallThen(List.of(available, lowStock), List.of(available, lowStock, replacement), List.of(available, replacement));
        fixture.inventoryThen(List.of("P001"), List.of("P001", "P002"));

        List<AgentRunEvent> events = new ArrayList<>();
        AgentLoopResponse response = fixture.service().run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_001").numItems(2).build())
                .build(), events::add);

        assertThat(response.getStatus()).as(response.toString()).isEqualTo("completed");
        assertThat(response.getToolCalls()).extracting("toolName").containsExactly(
                "get_user_profile", "search_products", "check_inventory",
                "rerank", "search_products", "check_inventory", "rerank", "final_answer");
        assertThat(response.getPlan().getProducts()).extracting("productId")
                .doesNotContain("P_LOW")
                .contains("P002");
        assertThat(events).extracting(AgentRunEvent::getName).contains("agent.vetoed", "agent.assigned");
        @SuppressWarnings("unchecked")
        List<AgentMessage> messages = (List<AgentMessage>) response.getLlmMetrics().get("collaborationMessages");
        assertThat(messages).extracting(AgentMessage::getType)
                .contains(AgentMessageType.VETO, AgentMessageType.REQUEST_REVISION);
        assertThat(events.stream().filter(event -> "agent.vetoed".equals(event.getName())).findFirst())
                .isPresent()
                .get()
                .extracting(event -> String.valueOf(event.getData().get("productIds")))
                .asString()
                .contains("P_LOW");
    }

    @Test
    void inventoryAndRerankActuallyOverlapOnSeparateCoordinatorThreads() throws Exception {
        TestFixture fixture = new TestFixture();
        Product product = fixture.product("P001", "Phone");
        CountDownLatch bothStarted = new CountDownLatch(2);
        AtomicBoolean overlapObserved = new AtomicBoolean(false);

        when(fixture.productRecAgent.runAsync(anyMap(), any(Executor.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> params = invocation.getArgument(0);
            if (params.containsKey("candidateProducts")) {
                bothStarted.countDown();
                overlapObserved.compareAndSet(false, bothStarted.await(2, TimeUnit.SECONDS));
            }
            return CompletableFuture.completedFuture(TestFixture.productsResult(List.of(product)));
        });
        when(fixture.inventoryAgent.runAsync(anyMap(), any(Executor.class))).thenAnswer(invocation -> {
            bothStarted.countDown();
            overlapObserved.compareAndSet(false, bothStarted.await(2, TimeUnit.SECONDS));
            return CompletableFuture.completedFuture(TestFixture.inventoryResult(List.of("P001")));
        });

        ExecutorService coordinator = Executors.newFixedThreadPool(2);
        try {
            RecommendationOrchestrationProperties properties = new RecommendationOrchestrationProperties();
            AgentLoopResponse response = fixture.service(
                    new RecommendationModeResolver("RULES", ""), 0, null, properties, coordinator)
                    .run(ToolLoopRequest.builder()
                            .request(RecommendationRequest.builder().userId("user_001").numItems(1).build())
                            .build());

            assertThat(response.getStatus()).isEqualTo("completed");
            assertThat(overlapObserved).isTrue();
            assertThat(response.getLlmMetrics().get("parallelBatchCount")).isEqualTo(1);
            assertThat(response.getLlmMetrics().get("parallelSpecialistCount")).isEqualTo(2);
        } finally {
            coordinator.shutdownNow();
        }
    }

    @Test
    void copyCannotWriteProductListOntoBlackboard() {
        RecommendationPipelineState state = new RecommendationPipelineState(
                "run-copy", RecommendationRequest.builder().userId("user_001").build());
        Product smuggled = Product.builder().productId("P_FAKE").name("smuggled").build();
        assertThat(state.write(AgentId.COPY, BlackboardField.FINAL_PRODUCTS, List.of(smuggled)).accepted()).isFalse();
        assertThat(state.getFinalProducts()).isNull();
    }

    private static class TestFixture {
        private final UserProfileAgent userProfileAgent = mock(UserProfileAgent.class);
        private final ProductRecAgent productRecAgent = mock(ProductRecAgent.class);
        private final InventoryAgent inventoryAgent = mock(InventoryAgent.class);
        private final MarketingCopyAgent marketingCopyAgent = mock(MarketingCopyAgent.class);
        private final ChatClient.Builder chatClientBuilder = mock(ChatClient.Builder.class);
        private final ChatClient chatClient = mock(ChatClient.class);

        private TestFixture() {
            when(chatClientBuilder.build()).thenReturn(chatClient);

            UserProfile profile = UserProfile.builder()
                    .userId("user_001")
                    .segments(List.of("active"))
                    .preferredCategories(List.of("手机"))
                    .priceRange(new double[]{0, 10000})
                    .build();
            Product product = Product.builder()
                    .productId("P001")
                    .name("Phone")
                    .category("手机")
                    .price(1000)
                    .stock(10)
                    .platform("shopify")
                    .currency("SGD")
                    .warehouseRegion("SG")
                    .deliveryDays(2)
                    .supportedRegions(List.of("SG"))
                    .crossBorderEligible(true)
                    .build();

            AgentResult profileResult = AgentResult.builder()
                    .agentName("user_profile")
                    .success(true)
                    .data(Map.of("profile", profile))
                    .build();
            AgentResult recallResult = AgentResult.builder()
                    .agentName("product_rec")
                    .success(true)
                    .data(Map.of("products", List.of(product)))
                    .build();
            AgentResult rerankResult = AgentResult.builder()
                    .agentName("product_rec")
                    .success(true)
                    .data(Map.of("products", List.of(product)))
                    .build();
            AgentResult inventoryResult = AgentResult.builder()
                    .agentName("inventory")
                    .success(true)
                    .data(Map.of("available_products", List.of("P001")))
                    .build();
            AgentResult copyResult = AgentResult.builder()
                    .agentName("marketing_copy")
                    .success(true)
                    .data(Map.of("copies", List.of(Map.of("product_id", "P001", "copy", "适合日常使用。", "locale", "en-SG"))))
                    .build();

            when(userProfileAgent.runAsync(anyMap(), any(Executor.class))).thenReturn(CompletableFuture.completedFuture(profileResult));
            when(productRecAgent.runAsync(anyMap(), any(Executor.class)))
                    .thenReturn(CompletableFuture.completedFuture(recallResult))
                    .thenReturn(CompletableFuture.completedFuture(rerankResult));
            when(inventoryAgent.runAsync(anyMap(), any(Executor.class))).thenReturn(CompletableFuture.completedFuture(inventoryResult));
            when(marketingCopyAgent.runAsync(anyMap(), any(Executor.class))).thenReturn(CompletableFuture.completedFuture(copyResult));
        }

        private Product product(String id, String name) {
            return Product.builder()
                    .productId(id)
                    .name(name)
                    .category("手机")
                    .price(1000)
                    .stock(10)
                    .platform("shopify")
                    .currency("SGD")
                    .warehouseRegion("SG")
                    .deliveryDays(2)
                    .supportedRegions(List.of("SG"))
                    .crossBorderEligible(true)
                    .build();
        }

        private void recallThen(List<Product> firstSearch, List<Product> secondSearch, List<Product> ranked) {
            when(productRecAgent.runAsync(anyMap(), any(Executor.class)))
                    .thenReturn(CompletableFuture.completedFuture(productsResult(firstSearch)))
                    .thenReturn(CompletableFuture.completedFuture(productsResult(secondSearch)))
                    .thenReturn(CompletableFuture.completedFuture(productsResult(ranked)));
        }

        private void inventoryThen(List<String> firstAvailable, List<String> secondAvailable) {
            when(inventoryAgent.runAsync(anyMap(), any(Executor.class)))
                    .thenReturn(CompletableFuture.completedFuture(inventoryResult(firstAvailable)))
                    .thenReturn(CompletableFuture.completedFuture(inventoryResult(secondAvailable)));
        }

        private static AgentResult productsResult(List<Product> products) {
            return AgentResult.builder()
                    .agentName("product_rec")
                    .success(true)
                    .data(Map.of("products", products))
                    .build();
        }

        private static AgentResult inventoryResult(List<String> available) {
            return AgentResult.builder()
                    .agentName("inventory")
                    .success(true)
                    .data(Map.of("available_products", available))
                    .build();
        }

        private AutonomousAgentLoopService service() {
            return service(new RecommendationModeResolver("RULES", ""), 0, null);
        }

        private AutonomousAgentLoopService managedService(
                com.ecommerce.runtime.persistence.RecommendationRuntimeStore store,
                com.ecommerce.runtime.persistence.RecommendationRunEventService events,
                RecommendationLeaseHeartbeatService heartbeat) {
            return managedService(store, events, heartbeat, null);
        }

        private AutonomousAgentLoopService managedService(
                com.ecommerce.runtime.persistence.RecommendationRuntimeStore store,
                com.ecommerce.runtime.persistence.RecommendationRunEventService events,
                RecommendationLeaseHeartbeatService heartbeat,
                com.ecommerce.runtime.DynamicSubAgentRuntimeRegistry registry) {
            return new AutonomousAgentLoopService(
                    new RecommendationPipelineExecutor(userProfileAgent, productRecAgent, inventoryAgent,
                            marketingCopyAgent, Runnable::run), new ABTestService(), chatClientBuilder,
                    new ScenePathEnforcer(), new RecommendationModeResolver("RULES", ""), 0,
                    new RecommendationOrchestrationProperties(), Runnable::run, registry, store, events,
                    new com.ecommerce.config.RuntimeLimitsProperties(), null,
                    new com.ecommerce.runtime.persistence.RecommendationRecoveryProperties(), heartbeat);
        }

        private AutonomousAgentLoopService service(
                RecommendationModeResolver modeResolver,
                int supervisorMaxLlmCalls,
                AutonomousAgentLoopService.SupervisorLlmClient supervisorLlmClient) {
            return service(modeResolver, supervisorMaxLlmCalls, supervisorLlmClient,
                    new RecommendationOrchestrationProperties(), Runnable::run);
        }

        private AutonomousAgentLoopService service(
                RecommendationModeResolver modeResolver,
                int supervisorMaxLlmCalls,
                AutonomousAgentLoopService.SupervisorLlmClient supervisorLlmClient,
                RecommendationOrchestrationProperties orchestrationProperties,
                Executor orchestrationExecutor) {
            RecommendationPipelineExecutor pipelineExecutor = new RecommendationPipelineExecutor(
                    userProfileAgent,
                    productRecAgent,
                    inventoryAgent,
                    marketingCopyAgent,
                    Runnable::run
            );
            return new AutonomousAgentLoopService(
                    pipelineExecutor,
                    new ABTestService(),
                    chatClientBuilder,
                    new ScenePathEnforcer(),
                    modeResolver,
                    supervisorMaxLlmCalls,
                    supervisorLlmClient,
                    orchestrationProperties,
                    orchestrationExecutor
            );
        }
    }
}






