package com.ecommerce.service;

import com.ecommerce.agent.InventoryAgent;
import com.ecommerce.agent.MarketingCopyAgent;
import com.ecommerce.agent.ProductRecAgent;
import com.ecommerce.agent.UserProfileAgent;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutonomousAgentLoopServiceTest {

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
                .contains("run.started", "planner.decision", "tool.started", "tool.completed", "observation", "run.completed");
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

        private AutonomousAgentLoopService service() {
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
                    chatClientBuilder
            );
        }
    }
}






