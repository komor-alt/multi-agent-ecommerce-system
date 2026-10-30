package com.ecommerce.eval.live;

import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveAgentBenchmarkGuardTest {
    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put(LiveAgentBenchmarkGuard.RUN_ENV, "true");
        env.put(LiveRecommendationE2EGuard.RUN_ENV, "true");
        env.put(LiveRecommendationE2EGuard.MODE_ENV, "LLM");
        env.put(LiveRecommendationE2EGuard.LIVE_ENV, "true");
        env.put(LiveRecommendationE2EGuard.KEY_ENV, "sk-live-unit-fixture-never-used");
        env.put(LiveRecommendationE2EGuard.BUDGET_ENV, "4");
        env.put(LiveRecommendationE2EGuard.SUPERVISOR_BUDGET_ENV, "4");
        env.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "24");
        env.put(LiveAgentBenchmarkGuard.SEED_ENV, "20261030");
        env.put("ECOM_LLM_MODEL", "deepseek-flash");
        env.put("ECOM_RECOMMEND_LLM_PROVIDER", "openai-compatible");
        env.put(LiveRecommendationE2EGuard.DATABASE_URL_ENV, "jdbc:postgresql://localhost:5432/ecommerce_agent");
        env.put("SPRING_DATASOURCE_USERNAME", "agent");
        return env;
    }

    @Test
    void requiresBothLiveOptInsFiniteAggregateBudgetSeedAndExplicitModel() {
        LiveAgentBenchmarkGuard.Settings settings = LiveAgentBenchmarkGuard.requireConfiguration(env(), 6);
        assertThat(settings.maxCallsPerCase()).isEqualTo(4);
        assertThat(settings.maxTotalCalls()).isEqualTo(24);
        assertThat(settings.seed()).isEqualTo(20261030L);
        assertThat(settings.prices().status()).isEqualTo("unavailable_price_not_configured");
        assertThat(settings.toString()).doesNotContain("sk-live-unit-fixture-never-used");

        Map<String, String> noOuterOptIn = env();
        noOuterOptIn.put(LiveAgentBenchmarkGuard.RUN_ENV, "false");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(noOuterOptIn, 6))
                .hasMessageContaining(LiveAgentBenchmarkGuard.RUN_ENV);

        Map<String, String> insufficient = env();
        insufficient.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "23");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(insufficient, 6))
                .hasMessageContaining(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV);
    }

    @Test
    void rejectsUnequalPerRunBudgetsUnboundedTotalsMissingSeedAndPartialPricing() {
        Map<String, String> unequal = env();
        unequal.put(LiveRecommendationE2EGuard.SUPERVISOR_BUDGET_ENV, "3");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(unequal, 6))
                .hasMessageContaining("must match");

        Map<String, String> tooLarge = env();
        tooLarge.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "97");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(tooLarge, 6))
                .hasMessageContaining("at most 96");

        Map<String, String> noSeed = env();
        noSeed.remove(LiveAgentBenchmarkGuard.SEED_ENV);
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(noSeed, 6))
                .hasMessageContaining(LiveAgentBenchmarkGuard.SEED_ENV);

        Map<String, String> partialPrice = env();
        partialPrice.put(LiveAgentBenchmarkGuard.INPUT_PRICE_ENV, "0.2");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(partialPrice, 6))
                .hasMessageContaining("both input and output token prices");
    }

    @Test
    void checksDatasetBudgetAgainstAllCasesWithoutStartingAnyProvider() {
        int count = AgentBenchmarkDataset.load().cases().size();
        Map<String, String> tooSmall = env();
        tooSmall.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "20");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(tooSmall, count))
                .hasMessageContaining(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV);
    }
}
