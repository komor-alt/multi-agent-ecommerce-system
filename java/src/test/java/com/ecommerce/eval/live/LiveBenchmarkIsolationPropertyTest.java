package com.ecommerce.eval.live;

import com.ecommerce.config.RuntimeLimitsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.env.MockEnvironment;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LiveBenchmarkIsolationPropertyTest {
    @Test
    void benchmarkPropertySourceOverridesHostEnvironmentValues() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("agent.embedding.live-enabled", "true")
                .withProperty("agent.embedding.max-calls", "100")
                .withProperty("agent.demo.seed-enabled", "true")
                .withProperty("agent.recovery.enabled", "true")
                .withProperty("agent.limits.run-timeout", "5s")
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost/shared");

        LiveBenchmarkIsolation.installFirst(environment, Map.of(
                "agent.embedding.live-enabled", "false",
                "agent.embedding.max-calls", "0",
                "agent.demo.seed-enabled", "false",
                "agent.recovery.enabled", "false",
                "agent.limits.run-timeout", "90000ms",
                "spring.datasource.url", "jdbc:postgresql://localhost/isolated?currentSchema=agent_benchmark",
                "spring.datasource.username", "benchmark_user",
                "spring.datasource.password", "benchmark_password"));

        assertThat(environment.getProperty("agent.embedding.live-enabled")).isEqualTo("false");
        assertThat(environment.getProperty("agent.embedding.max-calls")).isEqualTo("0");
        assertThat(environment.getProperty("agent.demo.seed-enabled")).isEqualTo("false");
        assertThat(environment.getProperty("agent.recovery.enabled")).isEqualTo("false");
        assertThat(environment.getProperty("agent.limits.run-timeout")).isEqualTo("90000ms");
        assertThat(environment.getProperty("spring.datasource.url"))
                .contains("currentSchema=agent_benchmark");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("benchmark_user");
        assertThat(environment.getPropertySources().iterator().next().getName())
                .isEqualTo(LiveBenchmarkIsolation.PROPERTY_SOURCE);
    }

    @Test
    void runtimeLimitsBeanVerificationRejectsAConflictingBoundTimeout() {
        ConfigurableApplicationContext app = mock(ConfigurableApplicationContext.class);
        RuntimeLimitsProperties limits = new RuntimeLimitsProperties();
        limits.setRunTimeout(Duration.ofSeconds(5));
        when(app.getBean(RuntimeLimitsProperties.class)).thenReturn(limits);

        assertThatThrownBy(() -> LiveBenchmarkIsolation.verifyRuntimeLimitsBean(app, 90_000L))
                .hasMessageContaining("RuntimeLimitsProperties")
                .hasMessageContaining("90000ms");

        limits.setRunTimeout(Duration.ofSeconds(90));
        LiveBenchmarkIsolation.verifyRuntimeLimitsBean(app, 90_000L);
    }

    @Test
    void datasourceDerivationRejectsWrongOrNonPostgresSchema() {
        assertThat(LiveBenchmarkIsolation.isolatedJdbcUrl(
                "jdbc:postgresql://localhost/ecommerce", "agent_benchmark"))
                .contains("currentSchema=agent_benchmark")
                .contains("ApplicationName=ecom-live-agent-benchmark");
        assertThatThrownBy(() -> LiveBenchmarkIsolation.isolatedJdbcUrl(
                "jdbc:postgresql://localhost/ecommerce?currentSchema=public", "agent_benchmark"))
                .hasMessageContaining("currentSchema");
        assertThatThrownBy(() -> LiveBenchmarkIsolation.isolatedJdbcUrl(
                "jdbc:h2:mem:test", "agent_benchmark"))
                .hasMessageContaining("PostgreSQL");
    }

    @Test
    void effectiveEnvironmentVerificationFailsClosedAfterAnySafetyOverride() {
        String url = "jdbc:postgresql://localhost/ecommerce?currentSchema=agent_benchmark";
        MockEnvironment valid = new MockEnvironment();
        LiveBenchmarkIsolation.installFirst(valid, Map.of(
                "agent.embedding.live-enabled", "false",
                "agent.embedding.max-calls", "0",
                "agent.demo.seed-enabled", "false",
                "agent.recovery.enabled", "false",
                "spring.datasource.url", url,
                "spring.jpa.properties.hibernate.default_schema", "agent_benchmark",
                "agent.benchmark.namespace", "bench_revision_config_00000001",
                "agent.benchmark.revision", "272744f1546edd83cb45475eabddddc4121c4022",
                "agent.benchmark.config-hash", "a".repeat(64)));
        LiveBenchmarkIsolation.verifySafetyProperties(valid, url, "agent_benchmark",
                "bench_revision_config_00000001", "272744f1546edd83cb45475eabddddc4121c4022",
                "a".repeat(64));

        MockEnvironment unsafe = new MockEnvironment();
        LiveBenchmarkIsolation.installFirst(unsafe, Map.of(
                "agent.embedding.live-enabled", "true",
                "agent.embedding.max-calls", "0",
                "agent.demo.seed-enabled", "false",
                "agent.recovery.enabled", "false",
                "spring.datasource.url", url,
                "spring.jpa.properties.hibernate.default_schema", "agent_benchmark",
                "agent.benchmark.namespace", "bench_revision_config_00000001",
                "agent.benchmark.revision", "272744f1546edd83cb45475eabddddc4121c4022",
                "agent.benchmark.config-hash", "a".repeat(64)));
        assertThatThrownBy(() -> LiveBenchmarkIsolation.verifySafetyProperties(unsafe, url,
                "agent_benchmark", "bench_revision_config_00000001",
                "272744f1546edd83cb45475eabddddc4121c4022", "a".repeat(64)))
                .hasMessageContaining("agent.embedding.live-enabled");
    }
}
