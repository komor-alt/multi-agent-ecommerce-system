package com.ecommerce.eval.live;

import com.ecommerce.eval.benchmark.AgentBenchmark;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/** Additional opt-in, aggregate-budget and pricing guard for the paid Java benchmark. */
final class LiveAgentBenchmarkGuard {
    static final String RUN_ENV = "ECOM_RUN_LIVE_AGENT_BENCHMARK";
    static final String SCOPE_ENV = "ECOM_AGENT_BENCHMARK_SCOPE";
    static final String ARM_SET_ENV = "ECOM_AGENT_BENCHMARK_ARMS";
    static final String TOTAL_BUDGET_ENV = "ECOM_AGENT_BENCHMARK_MAX_TOTAL_LLM_CALLS";
    static final String SEED_ENV = "ECOM_AGENT_BENCHMARK_SEED";
    static final String INPUT_PRICE_ENV = "ECOM_AGENT_BENCHMARK_INPUT_USD_PER_1M";
    static final String OUTPUT_PRICE_ENV = "ECOM_AGENT_BENCHMARK_OUTPUT_USD_PER_1M";
    enum Scope { PILOT, FULL }
    enum ArmSet { CORE, FULL }
    private LiveAgentBenchmarkGuard() {}

    static List<AgentBenchmark.Variant> selectedVariants(Map<String, String> env) {
        ArmSet armSet = armSet(env);
        List<AgentBenchmark.Variant> supported = AgentBenchmark.variants().stream()
                .filter(item -> item.status() == AgentBenchmark.VariantStatus.SUPPORTED).toList();
        if (armSet == ArmSet.FULL) return supported;
        return supported.stream().filter(item ->
                "java-single-agent-veto-on-path-on".equals(item.id())
                        || AgentBenchmark.RUNNABLE_VARIANT.equals(item.id())).toList();
    }

    static Settings requireConfiguration(Map<String, String> env, int fullCaseCount,
                                         int pilotCaseCount, int variantCount) {
        if (!"true".equalsIgnoreCase(value(env, RUN_ENV))) throw fail(RUN_ENV + " must be true");
        Scope scope;
        try {
            scope = Scope.valueOf(value(env, SCOPE_ENV).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw fail(SCOPE_ENV + " must be explicitly PILOT or FULL");
        }
        int caseCount = scope == Scope.PILOT ? pilotCaseCount : fullCaseCount;
        if (caseCount <= 0) throw fail("selected scope has no executable cases");
        ArmSet armSet = armSet(env);
        int expectedVariantCount = selectedVariants(env).size();
        if (variantCount != expectedVariantCount) {
            throw fail("variantCount must match " + ARM_SET_ENV + "=" + armSet
                    + " (required " + expectedVariantCount + ")");
        }
        LiveRecommendationE2EGuard.Settings live = LiveRecommendationE2EGuard.requireConfiguration(env);
        int modeBudget = positiveInt(env, LiveRecommendationE2EGuard.BUDGET_ENV);
        if (live.maxLlmCalls() != modeBudget) {
            throw fail("ECOM_SUPERVISOR_MAX_LLM_CALLS and ECOM_RECOMMEND_MAX_LLM_CALLS must match");
        }
        int maxTotal = positiveInt(env, TOTAL_BUDGET_ENV);
        long required = Math.multiplyExact(Math.multiplyExact((long) modeBudget, caseCount), variantCount);
        if (required > Integer.MAX_VALUE || maxTotal != required) {
            throw fail(TOTAL_BUDGET_ENV + " must equal caseCount * runnableVariantCount * perCaseBudget (required "
                    + required + "); samples and budgets are not shared or truncated");
        }
        long seed;
        try {
            seed = Long.parseLong(value(env, SEED_ENV));
            if (seed < 0) throw new NumberFormatException("negative");
        } catch (NumberFormatException error) {
            throw fail(SEED_ENV + " must be a non-negative integer");
        }
        String model = value(env, "ECOM_LLM_MODEL");
        if (model.isBlank()) throw fail("ECOM_LLM_MODEL must be explicit for reproducible runs");
        String provider = value(env, "ECOM_RECOMMEND_LLM_PROVIDER");
        if (provider.isBlank()) provider = "openai-compatible";
        String inputPrice = value(env, INPUT_PRICE_ENV);
        String outputPrice = value(env, OUTPUT_PRICE_ENV);
        AgentBenchmark.PriceConfig prices = parsePrices(inputPrice, outputPrice);
        return new Settings(live, scope, armSet, provider, model, modeBudget, maxTotal, caseCount, variantCount,
                required, seed, prices);
    }

    private static ArmSet armSet(Map<String, String> env) {
        String configured = value(env, ARM_SET_ENV);
        try {
            return configured.isBlank() ? ArmSet.CORE : ArmSet.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw fail(ARM_SET_ENV + " must be CORE or FULL");
        }
    }

    private static AgentBenchmark.PriceConfig parsePrices(String input, String output) {
        if (input.isBlank() && output.isBlank()) return new AgentBenchmark.PriceConfig(null, null);
        if (input.isBlank() || output.isBlank()) {
            throw fail("both input and output token prices must be configured together");
        }
        try {
            return new AgentBenchmark.PriceConfig(new BigDecimal(input), new BigDecimal(output));
        } catch (NumberFormatException | NullPointerException error) {
            throw fail("both input and output token prices must be valid non-negative decimals");
        }
    }

    private static int positiveInt(Map<String, String> env, String key) {
        try {
            int number = Integer.parseInt(value(env, key));
            if (number > 0) return number;
        } catch (NumberFormatException ignored) { }
        throw fail(key + " must be a positive integer");
    }

    private static String value(Map<String, String> env, String key) {
        return env.getOrDefault(key, "").trim();
    }

    private static IllegalStateException fail(String reason) {
        return new IllegalStateException("LIVE_AGENT_BENCHMARK_PREFLIGHT: " + reason);
    }

    record Settings(LiveRecommendationE2EGuard.Settings live, Scope scope, ArmSet armSet,
                    String provider, String model,
                    int maxCallsPerCase, int maxTotalCalls, int caseCount, int variantCount,
                    long requiredTotalCalls, long seed, AgentBenchmark.PriceConfig prices) {
        @Override public String toString() {
            return "Settings[scope=" + scope + ", armSet=" + armSet + ", provider=" + provider
                    + ", model=" + model + ", maxCallsPerCase="
                    + maxCallsPerCase + ", maxTotalCalls=" + maxTotalCalls + ", seed=" + seed + ", key=<redacted>]";
        }
    }
}
