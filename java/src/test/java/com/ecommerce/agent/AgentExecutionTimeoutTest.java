package com.ecommerce.agent;

import com.ecommerce.connector.CommerceConnector;
import com.ecommerce.data.RecommendationDataService;
import com.ecommerce.service.RecommendationModeResolver;
import com.ecommerce.service.RedisFeatureStoreService;
import com.ecommerce.service.TracedRecommendationLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentExecutionTimeoutTest {

    @ParameterizedTest
    @ValueSource(longs = {15_000L, 30_000L})
    void llmBackedAgentsKeepOuterTimeoutBeyondEffectiveClientTimeout(long llmTimeoutMs) {
        TracedRecommendationLlmClient llmClient = mock(TracedRecommendationLlmClient.class);
        when(llmClient.timeoutMs()).thenReturn(llmTimeoutMs);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        RecommendationModeResolver mode = new RecommendationModeResolver("LLM", "test-key");

        UserProfileAgent profile = new UserProfileAgent(builder, mock(RedisFeatureStoreService.class), mode, llmClient);
        ProductRecAgent products = new ProductRecAgent(builder, List.of(), mode,
                mock(RecommendationDataService.class), llmClient);
        MarketingCopyAgent copy = new MarketingCopyAgent(builder, mode, llmClient);

        assertThat(profile.executionTimeoutMs()).isEqualTo(llmTimeoutMs + 2_000L).isGreaterThan(llmTimeoutMs);
        assertThat(products.executionTimeoutMs()).isEqualTo(llmTimeoutMs + 2_000L).isGreaterThan(llmTimeoutMs);
        assertThat(copy.executionTimeoutMs()).isEqualTo(llmTimeoutMs + 2_000L).isGreaterThan(llmTimeoutMs);
    }

    @Test
    void inventoryKeepsDeterministicFiveSecondTimeout() {
        assertThat(new InventoryAgent().executionTimeoutMs()).isEqualTo(5_000L);
    }

    @Test
    void preservesExistingPublicConstructorSignatures() throws Exception {
        assertThat(UserProfileAgent.class.getConstructor(ChatClient.Builder.class,
                RedisFeatureStoreService.class)).isNotNull();
        assertThat(UserProfileAgent.class.getConstructor(ChatClient.Builder.class,
                RedisFeatureStoreService.class, RecommendationModeResolver.class)).isNotNull();
        assertThat(ProductRecAgent.class.getConstructor(ChatClient.Builder.class, List.class)).isNotNull();
        assertThat(ProductRecAgent.class.getConstructor(ChatClient.Builder.class, List.class,
                RecommendationModeResolver.class)).isNotNull();
        assertThat(ProductRecAgent.class.getConstructor(ChatClient.Builder.class, List.class,
                RecommendationModeResolver.class, RecommendationDataService.class)).isNotNull();
        assertThat(MarketingCopyAgent.class.getConstructor(ChatClient.Builder.class)).isNotNull();
        assertThat(MarketingCopyAgent.class.getConstructor(ChatClient.Builder.class,
                RecommendationModeResolver.class)).isNotNull();
        assertThat(InventoryAgent.class.getConstructor()).isNotNull();
    }
}
