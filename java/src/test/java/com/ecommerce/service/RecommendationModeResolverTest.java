package com.ecommerce.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RecommendationModeResolverTest {
    @Test
    void liveLlmRequiresModeLiveSwitchKeyAndPositiveBudget() {
        assertThat(new RecommendationModeResolver("RULES", "sk-live-key-material", true, 4).llmEnabled())
                .isFalse();
        assertThat(new RecommendationModeResolver("LLM", "sk-live-key-material", false, 4).llmEnabled())
                .isFalse();
        assertThat(new RecommendationModeResolver("AUTO", "", true, 4).llmEnabled()).isFalse();
        assertThat(new RecommendationModeResolver("LLM", "sk-live-key-material", true, 0).llmEnabled())
                .isFalse();
        assertThat(new RecommendationModeResolver("LLM", "sk-live-key-material", true, 4).llmEnabled())
                .isTrue();
        assertThat(new RecommendationModeResolver("AUTO", "sk-live-key-material", true, 4).llmEnabled())
                .isTrue();
    }
}
