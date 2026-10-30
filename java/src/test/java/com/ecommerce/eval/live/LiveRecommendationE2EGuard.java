package com.ecommerce.eval.live;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Locale;
import java.util.Map;

/** Fail-closed preflight for the explicitly invoked paid recommendation E2E. */
final class LiveRecommendationE2EGuard {
    static final String RUN_ENV = "ECOM_RUN_LIVE_RECOMMENDATION_E2E";
    static final String MODE_ENV = "ECOM_RECOMMEND_MODE";
    static final String LIVE_ENV = "ECOM_RECOMMEND_LIVE_ENABLED";
    static final String KEY_ENV = "ECOM_LLM_API_KEY";
    static final String BUDGET_ENV = "ECOM_RECOMMEND_MAX_LLM_CALLS";
    static final String SUPERVISOR_BUDGET_ENV = "ECOM_SUPERVISOR_MAX_LLM_CALLS";
    static final String DATABASE_URL_ENV = "SPRING_DATASOURCE_URL";
    static final String PLACEHOLDER_KEY = "your_api_key_here";
    static final int MAX_ALLOWED_LLM_CALLS = 32;

    private LiveRecommendationE2EGuard() {}

    static Settings requireConfiguration(Map<String, String> env) {
        if (!"true".equalsIgnoreCase(trimmed(env, RUN_ENV))) {
            throw failure(RUN_ENV + " must be true");
        }
        String mode = trimmed(env, MODE_ENV).toUpperCase(Locale.ROOT);
        if (!"LLM".equals(mode) && !"AUTO".equals(mode)) {
            throw failure(MODE_ENV + " must be LLM or AUTO");
        }
        if (!"true".equalsIgnoreCase(trimmed(env, LIVE_ENV))) {
            throw failure(LIVE_ENV + " must be true");
        }
        String key = trimmed(env, KEY_ENV);
        String normalizedKey = key.toLowerCase(Locale.ROOT);
        if (key.isEmpty() || PLACEHOLDER_KEY.equalsIgnoreCase(key)
                || normalizedKey.contains("placeholder") || normalizedKey.contains("your_api_key")
                || normalizedKey.startsWith("sk-test") || normalizedKey.startsWith("test-")
                || normalizedKey.startsWith("dummy-")) {
            throw failure(KEY_ENV + " must contain a non-placeholder key");
        }
        int recommendationBudget = positiveInt(env, BUDGET_ENV);
        if (recommendationBudget > MAX_ALLOWED_LLM_CALLS) {
            throw failure(BUDGET_ENV + " must be positive and maximum " + MAX_ALLOWED_LLM_CALLS);
        }
        String supervisorValue = trimmed(env, SUPERVISOR_BUDGET_ENV);
        int effectiveBudget = supervisorValue.isEmpty() ? recommendationBudget
                : positiveInt(env, SUPERVISOR_BUDGET_ENV);
        if (effectiveBudget > MAX_ALLOWED_LLM_CALLS) {
            throw failure(SUPERVISOR_BUDGET_ENV + " must be positive and maximum " + MAX_ALLOWED_LLM_CALLS);
        }
        String jdbcUrl = trimmed(env, DATABASE_URL_ENV);
        if (!jdbcUrl.toLowerCase(Locale.ROOT).startsWith("jdbc:postgresql:")) {
            throw failure(DATABASE_URL_ENV + " must point to PostgreSQL");
        }
        return new Settings(mode, effectiveBudget, jdbcUrl,
                trimmed(env, "SPRING_DATASOURCE_USERNAME"), env.getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
    }

    static void verifyPostgres(Settings settings) {
        try (Connection connection = DriverManager.getConnection(
                settings.jdbcUrl(), settings.username(), settings.password());
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("select 1")) {
            String product = connection.getMetaData().getDatabaseProductName();
            if (product == null || !product.toLowerCase(Locale.ROOT).contains("postgresql")
                    || !result.next() || result.getInt(1) != 1) {
                throw failure("PostgreSQL preflight did not identify a working PostgreSQL database");
            }
        } catch (java.sql.SQLException error) {
            throw failure("PostgreSQL connection preflight failed");
        }
    }

    private static int positiveInt(Map<String, String> env, String name) {
        try {
            int value = Integer.parseInt(trimmed(env, name));
            if (value > 0) return value;
        } catch (NumberFormatException ignored) {
            // Fail below without including any unrelated environment contents.
        }
        throw failure(name + " must be a positive integer");
    }

    private static String trimmed(Map<String, String> env, String name) {
        String value = env.get(name);
        return value == null ? "" : value.trim();
    }

    private static IllegalStateException failure(String reason) {
        return new IllegalStateException("LIVE_RECOMMENDATION_E2E_PREFLIGHT: " + reason);
    }

    record Settings(String mode, int maxLlmCalls, String jdbcUrl, String username, String password) {
        @Override
        public String toString() {
            return "Settings[mode=" + mode + ", maxLlmCalls=" + maxLlmCalls
                    + ", jdbcUrl=" + jdbcUrl + ", username=" + username + ", password=<redacted>]";
        }
    }
}
