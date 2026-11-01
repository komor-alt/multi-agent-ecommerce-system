package com.ecommerce.eval.live;

import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.LlmCallBudgetFactory;
import com.ecommerce.service.AgentEvaluationProfile;
import com.ecommerce.service.RecommendationPipelineHook;
import com.ecommerce.eval.benchmark.AgentBenchmarkFaultHook;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@TestConfiguration(proxyBeanMethods = false)
class LiveBenchmarkConfiguration {
    @Bean
    RecommendationPipelineHook benchmarkFaultHook() {
        return new AgentBenchmarkFaultHook();
    }

    @Bean
    BenchmarkCallBudgetLedger benchmarkCallBudgetLedger(JdbcTemplate jdbc,
                                                        PlatformTransactionManager transactionManager,
                                                        Environment environment) {
        return new BenchmarkCallBudgetLedger(jdbc, transactionManager, environment);
    }

    @Bean
    LlmCallBudgetFactory benchmarkLlmCallBudgetFactory(BenchmarkCallBudgetLedger ledger) {
        return (runId, maxCalls) -> {
            String topology = AgentEvaluationProfile.current().topology()
                    == AgentEvaluationProfile.Topology.SINGLE ? "single" : "multi";
            ledger.verifyRunIdentity(runId, topology);
            return new LlmCallBudget(maxCalls,
                    (phase, fingerprint) -> ledger.reserve(runId, phase, fingerprint));
        };
    }
}
