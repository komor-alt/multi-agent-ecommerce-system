package com.ecommerce.eval.live;

import com.ecommerce.eval.benchmark.AgentBenchmark;
import com.ecommerce.service.LlmCallBudget;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "ECOM_RUN_POSTGRES_TESTS", matches = "true")
class LiveBenchmarkIsolationPostgresTest {
    @Test
    void advisoryLockBlocksExternalInstanceAndAbortCleansRunningRows() throws Exception {
        String schema = "agent_benchmark_test_" + UUID.randomUUID().toString().replace("-", "");
        LiveAgentBenchmarkGuard.Settings settings = settings(schema);
        LiveBenchmarkIsolation isolation = null;
        try {
            isolation = LiveBenchmarkIsolation.open(settings, "272744f1546edd83cb45475eabddddc4121c4022",
                    "a".repeat(64), "b".repeat(64));
            LiveBenchmarkIsolation active = isolation;
            assertThatThrownBy(() -> LiveBenchmarkIsolation.open(settings,
                    "272744f1546edd83cb45475eabddddc4121c4022", "a".repeat(64), "b".repeat(64)))
                    .hasMessageContaining("another benchmark execution instance");

            try (Connection connection = connection(isolation.isolatedJdbcUrl()); Statement statement = connection.createStatement()) {
                statement.execute("create table recommendation_agent_runs(id varchar(255) primary key, status varchar(32), "
                        + "recoverable boolean, stop_reason varchar(255), completed_at timestamptz, updated_at timestamptz, "
                        + "execution_token varchar(255), execution_owner varchar(255), execution_lease_until timestamptz)");
                statement.execute("insert into recommendation_agent_runs(id,status,recoverable,updated_at) values ('"
                        + isolation.namespace() + "__single__case-1','RUNNING',true,clock_timestamp())");
            }

            DriverManagerDataSource source = new DriverManagerDataSource(isolation.isolatedJdbcUrl(),
                    required("ECOM_POSTGRES_TEST_USER"), required("ECOM_POSTGRES_TEST_PASSWORD"));
            BenchmarkCallBudgetLedger ledger = new BenchmarkCallBudgetLedger(new JdbcTemplate(source),
                    new DataSourceTransactionManager(source), isolation.namespace(), isolation.revision(),
                    isolation.configHash(), 3, 2, 2, 12);
            String runId = isolation.namespace() + "__single__case-1";
            ledger.registerRun(runId, "single", "case-1", "single");
            AtomicInteger mockProviderCalls = new AtomicInteger();
            LlmCallBudget budget = new LlmCallBudget(2,
                    (phase, fingerprint) -> ledger.reserve(runId, phase, fingerprint));
            if (budget.reserve("planner", "first").acquired()) mockProviderCalls.incrementAndGet();
            assertThat(mockProviderCalls).hasValue(1);

            isolation.close();
            isolation = null;

            assertThatThrownBy(() -> ledger.reserve(runId, "planner", "after-abort"))
                    .hasMessageContaining("identity mismatch");

            try (Connection connection = connection(LiveBenchmarkIsolation.isolatedJdbcUrl(
                    required("ECOM_POSTGRES_TEST_URL"), schema)); Statement statement = connection.createStatement()) {
                try (ResultSet row = statement.executeQuery("select status,recoverable,stop_reason from recommendation_agent_runs")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("FAILED");
                    assertThat(row.getBoolean(2)).isFalse();
                    assertThat(row.getString(3)).isEqualTo("benchmark_aborted");
                }
                try (ResultSet row = statement.executeQuery("select status from benchmark_execution_sessions")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("ABORTED");
                }
                try (ResultSet row = statement.executeQuery("select status from benchmark_run_manifests")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString(1)).isEqualTo("ABORTED");
                }
                try (ResultSet row = statement.executeQuery("select count(*) from benchmark_provider_call_claims")) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getInt(1)).isEqualTo(1);
                }
            }
        } finally {
            if (isolation != null) isolation.close();
            try (Connection connection = connection(required("ECOM_POSTGRES_TEST_URL")); Statement statement = connection.createStatement()) {
                statement.execute("drop schema if exists \"" + schema + "\" cascade");
            }
        }
    }

    private LiveAgentBenchmarkGuard.Settings settings(String schema) {
        LiveRecommendationE2EGuard.Settings live = new LiveRecommendationE2EGuard.Settings(
                "LLM", 2, required("ECOM_POSTGRES_TEST_URL"), required("ECOM_POSTGRES_TEST_USER"),
                required("ECOM_POSTGRES_TEST_PASSWORD"));
        return new LiveAgentBenchmarkGuard.Settings(live, LiveAgentBenchmarkGuard.Scope.SMOKE,
                LiveAgentBenchmarkGuard.ArmSet.CORE, "fixture", "fixture-model", 2, 12,
                3, 2, 12, 256, 1, 1_000, 5_000, schema,
                new AgentBenchmark.PriceConfig(null, null));
    }

    private Connection connection(String url) throws Exception {
        return DriverManager.getConnection(url, required("ECOM_POSTGRES_TEST_USER"),
                required("ECOM_POSTGRES_TEST_PASSWORD"));
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
