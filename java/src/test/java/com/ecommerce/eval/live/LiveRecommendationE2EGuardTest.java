package com.ecommerce.eval.live;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveRecommendationE2EGuardTest {
    private Map<String, String> validEnvironment() {
        Map<String, String> env = new HashMap<>();
        env.put(LiveRecommendationE2EGuard.RUN_ENV, "true");
        env.put(LiveRecommendationE2EGuard.MODE_ENV, "LLM");
        env.put(LiveRecommendationE2EGuard.LIVE_ENV, "true");
        env.put(LiveRecommendationE2EGuard.KEY_ENV, "sk-live-key-material-is-not-returned");
        env.put(LiveRecommendationE2EGuard.BUDGET_ENV, "24");
        env.put(LiveRecommendationE2EGuard.DATABASE_URL_ENV, "jdbc:postgresql://localhost:5432/recommendations");
        env.put("SPRING_DATASOURCE_USERNAME", "recommendation");
        return env;
    }

    @Test
    void acceptsExplicitLiveConfigurationWithoutReturningKey() {
        LiveRecommendationE2EGuard.Settings settings =
                LiveRecommendationE2EGuard.requireConfiguration(validEnvironment());

        assertThat(settings.mode()).isEqualTo("LLM");
        assertThat(settings.maxLlmCalls()).isEqualTo(24);
        assertThat(settings.jdbcUrl()).startsWith("jdbc:postgresql:");
        assertThat(settings.toString()).doesNotContain("sk-live-key-material");
    }

    @Test
    void supervisorBudgetOverrideMustAlsoBePositive() {
        Map<String, String> env = validEnvironment();
        env.put(LiveRecommendationE2EGuard.SUPERVISOR_BUDGET_ENV, "0");

        assertThatThrownBy(() -> LiveRecommendationE2EGuard.requireConfiguration(env))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(LiveRecommendationE2EGuard.SUPERVISOR_BUDGET_ENV);
    }

    @Test
    void refusesMissingOptInPlaceholderKeyAndNonPostgresDatabase() {
        Map<String, String> env = validEnvironment();
        env.put(LiveRecommendationE2EGuard.RUN_ENV, "false");
        Map<String, String> disabled = env;
        assertThatThrownBy(() -> LiveRecommendationE2EGuard.requireConfiguration(disabled))
                .hasMessageContaining(LiveRecommendationE2EGuard.RUN_ENV);

        env = validEnvironment();
        env.put(LiveRecommendationE2EGuard.KEY_ENV, LiveRecommendationE2EGuard.PLACEHOLDER_KEY);
        Map<String, String> placeholderKey = env;
        assertThatThrownBy(() -> LiveRecommendationE2EGuard.requireConfiguration(placeholderKey))
                .hasMessageContaining(LiveRecommendationE2EGuard.KEY_ENV);

        env = validEnvironment();
        env.put(LiveRecommendationE2EGuard.DATABASE_URL_ENV, "jdbc:h2:mem:test");
        Map<String, String> h2 = env;
        assertThatThrownBy(() -> LiveRecommendationE2EGuard.requireConfiguration(h2))
                .hasMessageContaining(LiveRecommendationE2EGuard.DATABASE_URL_ENV);
    }

    @Test
    void rejectsUnboundedBudgetAndObviousFakeKeys() {
        Map<String, String> env = validEnvironment();
        env.put(LiveRecommendationE2EGuard.BUDGET_ENV, "1000");
        Map<String, String> unbounded = env;
        assertThatThrownBy(() -> LiveRecommendationE2EGuard.requireConfiguration(unbounded))
                .hasMessageContaining("maximum 32");

        env = validEnvironment();
        env.put(LiveRecommendationE2EGuard.KEY_ENV, "sk-test-placeholder");
        Map<String, String> fakeKey = env;
        assertThatThrownBy(() -> LiveRecommendationE2EGuard.requireConfiguration(fakeKey))
                .hasMessageContaining(LiveRecommendationE2EGuard.KEY_ENV);
    }
}
