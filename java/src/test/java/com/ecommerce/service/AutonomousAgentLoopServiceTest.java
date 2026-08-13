package com.ecommerce.service;

import com.ecommerce.agent.InventoryAgent;
import com.ecommerce.agent.MarketingCopyAgent;
import com.ecommerce.agent.ProductRecAgent;
import com.ecommerce.agent.UserProfileAgent;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.model.UserProfile;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

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
        assertThat(response.getObservations()).hasSize(6);
        assertThat(response.getEvidences())
                .extracting("evidenceId")
                .contains("profile:user_001", "product:P001", "inventory:P001", "copy:P001");
        assertThat(response.getToolCalls())
                .extracting("toolName")
                .containsExactly(
                        "get_user_profile",
                        "search_cross_border_products",
                        "rerank_products",
                        "check_fulfillment_inventory",
                        "filter_products",
                        "generate_localized_copy",
                        "final_answer"
                );
    }

    @Test
    void blocksWhenPlannerSelectsToolOutsideWhitelist() {
        TestFixture fixture = new TestFixture();
        AutonomousAgentLoopService service = fixture.service();

        AgentLoopResponse response = service.run(ToolLoopRequest.builder()
                .request(RecommendationRequest.builder().userId("user_001").numItems(1).build())
                .config(ToolLoopConfig.builder()
                        .maxSteps(3)
                        .toolWhitelist(List.of("search_cross_border_products"))
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






