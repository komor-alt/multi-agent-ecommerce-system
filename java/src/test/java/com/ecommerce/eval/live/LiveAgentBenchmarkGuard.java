package com.ecommerce.eval.live;

import com.ecommerce.eval.benchmark.AgentBenchmark;
import org.springframework.boot.convert.DurationStyle;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Locale;
import java.util.List;
import java.util.Map;

/** Additional opt-in, aggregate-budget and pricing guard for the paid Java benchmark. */
final class LiveAgentBenchmarkGuard {
    static final String RUN_ENV = "ECOM_RUN_LIVE_AGENT_BENCHMARK";
    static final String SCOPE_ENV = "ECOM_AGENT_BENCHMARK_SCOPE";
    static final String ARM_SET_ENV = "ECOM_AGENT_BENCHMARK_ARMS";
    static final String TOTAL_BUDGET_ENV = "ECOM_AGENT_BENCHMARK_MAX_TOTAL_LLM_CALLS";
    static final String MAX_OUTPUT_TOKENS_ENV = "ECOM_AGENT_BENCHMARK_MAX_OUTPUT_TOKENS";
    static final String SEED_ENV = "ECOM_AGENT_BENCHMARK_SEED";
    static final String INPUT_PRICE_ENV = "ECOM_AGENT_BENCHMARK_INPUT_USD_PER_1M";
    static final String OUTPUT_PRICE_ENV = "ECOM_AGENT_BENCHMARK_OUTPUT_USD_PER_1M";
    static final String LLM_TIMEOUT_ENV = "ECOM_RECOMMEND_LLM_TIMEOUT_MS";
    static final String RUN_TIMEOUT_ENV = "ECOM_AGENT_RUN_TIMEOUT";
    static final String SCHEMA_ENV = "ECOM_AGENT_BENCHMARK_SCHEMA";
    static final String DEFAULT_SCHEMA = "agent_benchmark";
    private static final long DEFAULT_LLM_TIMEOUT_MS = 15_000L;
    private static final Duration DEFAULT_RUN_TIMEOUT = Duration.ofSeconds(60);
    private static final long PRODUCT_RERANK_RETRY_MARGIN_MS = 2_000L;
    enum Scope { SMOKE, PILOT, FULL }
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
                                         int pilotCaseCount, int smokeCaseCount, int variantCount) {
        return requireConfiguration(env, fullCaseCount, pilotCaseCount, smokeCaseCount,
                variantCount, null);
    }

    static Settings requireConfiguration(Map<String, String> env, int fullCaseCount,
                                         int pilotCaseCount, int smokeCaseCount, int variantCount,
                                         LiveBenchmarkSmokeEvidence.Verified smokeEvidence) {
        if (!"true".equalsIgnoreCase(value(env, RUN_ENV))) throw fail(RUN_ENV + " must be true");
        Scope scope;
        try {
            scope = Scope.valueOf(value(env, SCOPE_ENV).toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException error) {
            throw fail(SCOPE_ENV + " must be explicitly SMOKE, PILOT or FULL");
        }
        if (scope != Scope.SMOKE && (smokeEvidence == null || !smokeEvidence.valid())) {
            throw fail("verified pinned historical SMOKE report evidence is required before PILOT or FULL");
        }
        int caseCount = switch (scope) {
            case SMOKE -> smokeCaseCount;
            case PILOT -> pilotCaseCount;
            case FULL -> fullCaseCount;
        };
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
        long llmTimeoutMs = positiveLongOrDefault(env, LLM_TIMEOUT_ENV, DEFAULT_LLM_TIMEOUT_MS);
        long runTimeoutMs = positiveDurationMsOrDefault(env, RUN_TIMEOUT_ENV, DEFAULT_RUN_TIMEOUT);
        long rerankOuterTimeoutMs;
        try {
            rerankOuterTimeoutMs = Math.addExact(Math.multiplyExact(llmTimeoutMs, 2L),
                    PRODUCT_RERANK_RETRY_MARGIN_MS);
        } catch (ArithmeticException error) {
            throw fail(LLM_TIMEOUT_ENV + " is too large");
        }
        if (runTimeoutMs <= rerankOuterTimeoutMs) {
            throw fail(RUN_TIMEOUT_ENV + " must be greater than the ProductRecAgent retry deadline ("
                    + rerankOuterTimeoutMs + "ms for " + LLM_TIMEOUT_ENV + "=" + llmTimeoutMs
                    + "ms); configure an explicit Live run timeout before provider execution");
        }
        int maxTotal = positiveInt(env, TOTAL_BUDGET_ENV);
        long required = Math.multiplyExact(Math.multiplyExact((long) modeBudget, caseCount), variantCount);
        if (required > Integer.MAX_VALUE || maxTotal != required) {
            throw budgetFail(TOTAL_BUDGET_ENV + " must equal caseCount * runnableVariantCount * perCaseBudget (required "
                    + required + "); samples and budgets are not shared or truncated");
        }
        int maxOutputTokensPerCall = positiveInt(env, MAX_OUTPUT_TOKENS_ENV);
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
        String schema = value(env, SCHEMA_ENV);
        if (schema.isBlank()) schema = DEFAULT_SCHEMA;
        if (schema.length() > 63 || !schema.matches("agent_benchmark(?:_[a-z0-9_]+)?")) {
            throw fail(SCHEMA_ENV + " must be agent_benchmark or use the agent_benchmark_ prefix");
        }
        return new Settings(live, scope, armSet, provider, model, modeBudget, maxTotal, caseCount, variantCount,
                required, maxOutputTokensPerCall, seed, llmTimeoutMs, runTimeoutMs, schema, prices);
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

    private static long positiveLongOrDefault(Map<String, String> env, String key, long defaultValue) {
        String configured = value(env, key);
        if (configured.isBlank()) return defaultValue;
        try {
            long number = Long.parseLong(configured);
            if (number > 0) return number;
        } catch (NumberFormatException ignored) { }
        throw fail(key + " must be a positive integer number of milliseconds");
    }

    private static long positiveDurationMsOrDefault(Map<String, String> env, String key, Duration defaultValue) {
        String configured = value(env, key);
        Duration duration;
        try {
            duration = configured.isBlank() ? defaultValue : DurationStyle.detectAndParse(configured);
        } catch (IllegalArgumentException error) {
            throw fail(key + " must be a positive Spring duration such as 90s");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw fail(key + " must be a positive Spring duration such as 90s");
        }
        try {
            return duration.toMillis();
        } catch (ArithmeticException error) {
            throw fail(key + " is too large");
        }
    }

    private static String value(Map<String, String> env, String key) {
        return env.getOrDefault(key, "").trim();
    }

    private static IllegalStateException fail(String reason) {
        return new IllegalStateException("LIVE_AGENT_BENCHMARK_PREFLIGHT: " + reason);
    }

    private static IllegalStateException budgetFail(String reason) {
        return new IllegalStateException("LIVE_AGENT_BENCHMARK_BUDGET: " + reason);
    }

    record Settings(LiveRecommendationE2EGuard.Settings live, Scope scope, ArmSet armSet,
                    String provider, String model,
                    int maxCallsPerCase, int maxTotalCalls, int caseCount, int variantCount,
                    long requiredTotalCalls, int maxOutputTokensPerCall,
                    long seed, long llmTimeoutMs, long runTimeoutMs, String schema,
                    AgentBenchmark.PriceConfig prices) {
        @Override public String toString() {
            return "Settings[scope=" + scope + ", armSet=" + armSet + ", provider=" + provider
                    + ", model=" + model + ", maxCallsPerCase="
                    + maxCallsPerCase + ", maxTotalCalls=" + maxTotalCalls + ", llmTimeoutMs="
                    + llmTimeoutMs + ", runTimeoutMs=" + runTimeoutMs + ", maxOutputTokensPerCall="
                    + maxOutputTokensPerCall + ", seed=" + seed + ", schema=" + schema
                    + ", key=<redacted>]";
        }
    }
}
