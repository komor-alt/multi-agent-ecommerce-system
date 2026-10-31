package com.ecommerce.eval.live;

import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiveAgentBenchmarkGuardTest {
    private Map<String, String> env() {
        Map<String, String> env = new HashMap<>();
        env.put(LiveAgentBenchmarkGuard.RUN_ENV, "true");
        env.put(LiveAgentBenchmarkGuard.SCOPE_ENV, "PILOT");
        env.put(LiveAgentBenchmarkGuard.SMOKE_PASSED_ENV, "true");
        env.put(LiveRecommendationE2EGuard.RUN_ENV, "true");
        env.put(LiveRecommendationE2EGuard.MODE_ENV, "LLM");
        env.put(LiveRecommendationE2EGuard.LIVE_ENV, "true");
        env.put(LiveRecommendationE2EGuard.KEY_ENV, "sk-live-unit-fixture-never-used");
        env.put(LiveRecommendationE2EGuard.BUDGET_ENV, "4");
        env.put(LiveRecommendationE2EGuard.SUPERVISOR_BUDGET_ENV, "4");
        env.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "48");
        env.put(LiveAgentBenchmarkGuard.SEED_ENV, "20261030");
        env.put("ECOM_LLM_MODEL", "deepseek-flash");
        env.put("ECOM_RECOMMEND_LLM_PROVIDER", "openai-compatible");
        env.put(LiveRecommendationE2EGuard.DATABASE_URL_ENV, "jdbc:postgresql://localhost:5432/ecommerce_agent");
        env.put("SPRING_DATASOURCE_USERNAME", "agent");
        return env;
    }

    @Test
    void requiresBothLiveOptInsFiniteAggregateBudgetSeedAndExplicitModel() {
        LiveAgentBenchmarkGuard.Settings settings = LiveAgentBenchmarkGuard.requireConfiguration(env(), 102, 6, 3, 2);
        assertThat(settings.scope()).isEqualTo(LiveAgentBenchmarkGuard.Scope.PILOT);
        assertThat(settings.maxCallsPerCase()).isEqualTo(4);
        assertThat(settings.maxTotalCalls()).isEqualTo(48);
        assertThat(settings.requiredTotalCalls()).isEqualTo(48);
        assertThat(settings.armSet()).isEqualTo(LiveAgentBenchmarkGuard.ArmSet.CORE);
        assertThat(settings.seed()).isEqualTo(20261030L);
        assertThat(settings.prices().status()).isEqualTo("unavailable_price_not_configured");
        assertThat(settings.toString()).doesNotContain("sk-live-unit-fixture-never-used");

        Map<String, String> noOuterOptIn = env();
        noOuterOptIn.put(LiveAgentBenchmarkGuard.RUN_ENV, "false");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(noOuterOptIn, 102, 6, 3, 2))
                .hasMessageContaining(LiveAgentBenchmarkGuard.RUN_ENV);

        Map<String, String> insufficient = env();
        insufficient.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "47");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(insufficient, 102, 6, 3, 2))
                .hasMessageContaining(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV);
    }

    @Test
    void defaultsPilotPreflightToCoreTwoArmsAndRequiresExplicitFullAblation() {
        List<String> core = LiveAgentBenchmarkGuard.selectedVariants(env()).stream()
                .map(com.ecommerce.eval.benchmark.AgentBenchmark.Variant::id).toList();
        assertThat(core).containsExactly("java-single-agent-veto-on-path-on",
                com.ecommerce.eval.benchmark.AgentBenchmark.RUNNABLE_VARIANT);

        Map<String, String> pilot = env();
        pilot.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "72");
        assertThat(LiveAgentBenchmarkGuard.requireConfiguration(pilot, 102, 9, 3, core.size())
                .requiredTotalCalls()).isEqualTo(72);

        Map<String, String> fullArms = env();
        fullArms.put(LiveAgentBenchmarkGuard.ARM_SET_ENV, "FULL");
        fullArms.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "216");
        assertThat(LiveAgentBenchmarkGuard.selectedVariants(fullArms)).hasSize(6);
        assertThat(LiveAgentBenchmarkGuard.requireConfiguration(fullArms, 102, 9, 3, 6).armSet())
                .isEqualTo(LiveAgentBenchmarkGuard.ArmSet.FULL);

        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(pilot, 102, 9, 3, 6))
                .hasMessageContaining("variantCount must match");

        Map<String, String> invalid = env();
        invalid.put(LiveAgentBenchmarkGuard.ARM_SET_ENV, "AUTO");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.selectedVariants(invalid))
                .hasMessageContaining("CORE or FULL");
    }

    @Test
    void smokeUsesThreeNormalScenesAndGatesPilotWithoutChangingCoreArms() {
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.loadAll();
        assertThat(AgentBenchmarkDataset.selectSmoke(dataset.cases()))
                .extracting(AgentBenchmarkDataset.Case::id)
                .containsExactly("homepage-my-002", "campaign-th-002", "retention-vn-002");

        Map<String, String> smoke = env();
        smoke.put(LiveAgentBenchmarkGuard.SCOPE_ENV, "SMOKE");
        smoke.remove(LiveAgentBenchmarkGuard.SMOKE_PASSED_ENV);
        smoke.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "24");
        LiveAgentBenchmarkGuard.Settings settings = LiveAgentBenchmarkGuard.requireConfiguration(
                smoke, 102, 9, 3, 2);
        assertThat(settings.scope()).isEqualTo(LiveAgentBenchmarkGuard.Scope.SMOKE);
        assertThat(settings.caseCount()).isEqualTo(3);
        assertThat(settings.requiredTotalCalls()).isEqualTo(24);

        Map<String, String> unqualifiedPilot = env();
        unqualifiedPilot.remove(LiveAgentBenchmarkGuard.SMOKE_PASSED_ENV);
        unqualifiedPilot.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "72");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(
                unqualifiedPilot, 102, 9, 3, 2))
                .hasMessageContaining(LiveAgentBenchmarkGuard.SMOKE_PASSED_ENV);
    }

    @Test
    void rejectsUnequalPerRunBudgetsUnboundedTotalsMissingSeedAndPartialPricing() {
        Map<String, String> unequal = env();
        unequal.put(LiveRecommendationE2EGuard.SUPERVISOR_BUDGET_ENV, "3");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(unequal, 102, 6, 3, 2))
                .hasMessageContaining("must match");

        Map<String, String> surplus = env();
        surplus.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, "49");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(surplus, 102, 6, 3, 2))
                .hasMessageContaining("must equal");

        Map<String, String> noSeed = env();
        noSeed.remove(LiveAgentBenchmarkGuard.SEED_ENV);
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(noSeed, 102, 6, 3, 2))
                .hasMessageContaining(LiveAgentBenchmarkGuard.SEED_ENV);

        Map<String, String> partialPrice = env();
        partialPrice.put(LiveAgentBenchmarkGuard.INPUT_PRICE_ENV, "0.2");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(partialPrice, 102, 6, 3, 2))
                .hasMessageContaining("both input and output token prices");
    }

    @Test
    void checksDatasetBudgetAgainstAllCasesWithoutStartingAnyProvider() {
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.loadAll();
        int count = dataset.cases().size();
        int pilotCount = (int) dataset.cases().stream().filter(AgentBenchmarkDataset.Case::pilot).count();
        Map<String, String> tooSmall = env();
        tooSmall.put(LiveAgentBenchmarkGuard.SCOPE_ENV, "FULL");
        tooSmall.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, String.valueOf(count * 2 * 4 - 1));
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(tooSmall, count, pilotCount, 3, 2))
                .hasMessageContaining("LIVE_AGENT_BENCHMARK_BUDGET");

        Map<String, String> full = env();
        full.put(LiveAgentBenchmarkGuard.SCOPE_ENV, "FULL");
        full.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, String.valueOf(count * 2 * 4));
        assertThat(LiveAgentBenchmarkGuard.requireConfiguration(full, count, pilotCount, 3, 2).caseCount())
                .isEqualTo(count);

        Map<String, String> pilot = env();
        int exactPilotBudget = pilotCount * 2 * 4;
        pilot.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, String.valueOf(exactPilotBudget));
        LiveAgentBenchmarkGuard.Settings pilotSettings = LiveAgentBenchmarkGuard.requireConfiguration(
                pilot, count, pilotCount, 3, 2);
        assertThat(pilotSettings.caseCount()).isEqualTo(pilotCount);
        assertThat(pilotSettings.requiredTotalCalls()).isEqualTo(exactPilotBudget);

        Map<String, String> pilotSurplus = new HashMap<>(pilot);
        pilotSurplus.put(LiveAgentBenchmarkGuard.TOTAL_BUDGET_ENV, String.valueOf(exactPilotBudget + 1));
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(
                pilotSurplus, count, pilotCount, 3, 2))
                .hasMessageContaining("LIVE_AGENT_BENCHMARK_BUDGET");
    }

    @Test
    void requiresExplicitValidScopeBeforeAnyProviderCanStart() {
        Map<String, String> missing = env();
        missing.remove(LiveAgentBenchmarkGuard.SCOPE_ENV);
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(missing, 102, 6, 3, 2))
                .hasMessageContaining("SMOKE, PILOT or FULL");

        Map<String, String> invalid = env();
        invalid.put(LiveAgentBenchmarkGuard.SCOPE_ENV, "sample");
        assertThatThrownBy(() -> LiveAgentBenchmarkGuard.requireConfiguration(invalid, 102, 6, 3, 2))
                .hasMessageContaining("SMOKE, PILOT or FULL");
    }
}
