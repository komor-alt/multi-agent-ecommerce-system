package com.ecommerce.eval.live;

import com.ecommerce.data.CatalogSeedInitializer;
import com.ecommerce.config.RuntimeLimitsProperties;
import com.ecommerce.runtime.persistence.RecommendationRecoveryProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** PostgreSQL/schema isolation and fail-closed lifecycle for the paid benchmark only. */
final class LiveBenchmarkIsolation implements AutoCloseable {
    static final String PROPERTY_SOURCE = "liveBenchmarkIsolation";
    private final LiveAgentBenchmarkGuard.Settings settings;
    private final String revision;
    private final String configHash;
    private final String datasetHash;
    private final String namespace;
    private final String isolatedJdbcUrl;
    private final Connection lockConnection;
    private final Thread shutdownHook;
    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile boolean successful;

    private LiveBenchmarkIsolation(LiveAgentBenchmarkGuard.Settings settings, String revision,
                                   String configHash, String datasetHash, String namespace,
                                   String isolatedJdbcUrl, Connection lockConnection) {
        this.settings = settings;
        this.revision = revision;
        this.configHash = configHash;
        this.datasetHash = datasetHash;
        this.namespace = namespace;
        this.isolatedJdbcUrl = isolatedJdbcUrl;
        this.lockConnection = lockConnection;
        this.shutdownHook = new Thread(this::cleanupAfterAbort, "benchmark-isolation-cleanup");
        Runtime.getRuntime().addShutdownHook(shutdownHook);
    }

    static LiveBenchmarkIsolation open(LiveAgentBenchmarkGuard.Settings settings, String revision,
                                       String configHash, String datasetHash) {
        if (revision == null || !revision.matches("[a-f0-9]{7,40}")) {
            throw fail("Git revision must be a concrete hexadecimal commit");
        }
        if (configHash == null || !configHash.matches("[a-f0-9]{64}")) {
            throw fail("Benchmark config hash must be SHA-256");
        }
        String isolatedUrl = isolatedJdbcUrl(settings.live().jdbcUrl(), settings.schema());
        String namespace = namespace(revision, configHash);
        Connection connection = null;
        try {
            connection = DriverManager.getConnection(settings.live().jdbcUrl(),
                    settings.live().username(), settings.live().password());
            connection.setAutoCommit(true);
            try (PreparedStatement lock = connection.prepareStatement("select pg_try_advisory_lock(hashtext(?))")) {
                lock.setString(1, "ecom-live-agent-benchmark:" + settings.schema());
                try (ResultSet row = lock.executeQuery()) {
                    if (!row.next() || !row.getBoolean(1)) {
                        throw fail("another benchmark execution instance holds schema " + settings.schema());
                    }
                }
            }
            createSchemaAndManifestTables(connection, settings.schema());
            failIfExternalExecutionExists(connection, settings.schema());
            insertSession(connection, settings, namespace, revision, configHash, datasetHash);
            return new LiveBenchmarkIsolation(settings, revision, configHash, datasetHash,
                    namespace, isolatedUrl, connection);
        } catch (RuntimeException error) {
            closeQuietly(connection);
            throw error;
        } catch (Exception error) {
            closeQuietly(connection);
            throw fail("cannot initialize isolated PostgreSQL benchmark schema", error);
        }
    }

    Map<String, Object> forcedProperties() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("agent.recommend.mode", "LLM");
        properties.put("agent.recommend.live-enabled", "true");
        properties.put("agent.recommend.max-llm-calls", settings.maxCallsPerCase());
        properties.put("agent.supervisor.max-llm-calls", settings.maxCallsPerCase());
        properties.put("agent.recommend.llm-timeout-ms", settings.llmTimeoutMs());
        properties.put("agent.limits.run-timeout", settings.runTimeoutMs() + "ms");
        properties.put("spring.ai.openai.chat.options.max-tokens", settings.maxOutputTokensPerCall());
        properties.put("spring.datasource.url", isolatedJdbcUrl);
        properties.put("spring.datasource.username", settings.live().username());
        properties.put("spring.datasource.password", settings.live().password());
        properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        properties.put("spring.jpa.properties.hibernate.default_schema", settings.schema());
        properties.put("spring.jpa.hibernate.ddl-auto", "update");
        properties.put("spring.sql.init.mode", "always");
        properties.put("agent.embedding.live-enabled", "false");
        properties.put("agent.embedding.max-calls", "0");
        properties.put("agent.demo.seed-enabled", "false");
        properties.put("agent.recovery.enabled", "false");
        properties.put("agent.benchmark.namespace", namespace);
        properties.put("agent.benchmark.revision", revision);
        properties.put("agent.benchmark.config-hash", configHash);
        properties.put("agent.benchmark.dataset-hash", datasetHash);
        properties.put("agent.benchmark.schema", settings.schema());
        properties.put("agent.benchmark.case-count", settings.caseCount());
        properties.put("agent.benchmark.arm-count", settings.variantCount());
        properties.put("agent.benchmark.per-case-budget", settings.maxCallsPerCase());
        properties.put("agent.benchmark.total-budget", settings.maxTotalCalls());
        return Map.copyOf(properties);
    }

    void install(ConfigurableEnvironment environment) {
        installFirst(environment, forcedProperties());
    }

    static void installFirst(ConfigurableEnvironment environment, Map<String, Object> properties) {
        environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE, properties));
    }

    void verify(ConfigurableApplicationContext app) {
        ConfigurableEnvironment environment = app.getEnvironment();
        verifySafetyProperties(environment, isolatedJdbcUrl, settings.schema(), namespace, revision, configHash);
        require(environment, "agent.recommend.llm-timeout-ms", Long.toString(settings.llmTimeoutMs()));
        require(environment, "agent.limits.run-timeout", settings.runTimeoutMs() + "ms");
        verifyRuntimeLimitsBean(app, settings.runTimeoutMs());
        requireSecret(environment, "spring.datasource.username", settings.live().username());
        requireSecret(environment, "spring.datasource.password", settings.live().password());
        if (app.getBean(RecommendationRecoveryProperties.class).isEnabled()) {
            throw fail("Recommendation recovery is active inside the benchmark context");
        }
        if (!app.getBeansOfType(CatalogSeedInitializer.class).isEmpty()) {
            throw fail("automatic demo seed is active inside the benchmark context");
        }
    }

    static void verifySafetyProperties(ConfigurableEnvironment environment, String datasourceUrl,
                                       String schema, String namespace, String revision, String configHash) {
        require(environment, "agent.embedding.live-enabled", "false");
        require(environment, "agent.embedding.max-calls", "0");
        require(environment, "agent.demo.seed-enabled", "false");
        require(environment, "agent.recovery.enabled", "false");
        require(environment, "spring.datasource.url", datasourceUrl);
        require(environment, "spring.jpa.properties.hibernate.default_schema", schema);
        require(environment, "agent.benchmark.namespace", namespace);
        require(environment, "agent.benchmark.revision", revision);
        require(environment, "agent.benchmark.config-hash", configHash);
    }

    static void verifyRuntimeLimitsBean(ConfigurableApplicationContext app, long expectedRunTimeoutMs) {
        RuntimeLimitsProperties limits = app.getBean(RuntimeLimitsProperties.class);
        long actualRunTimeoutMs = limits.getRunTimeout() == null ? -1L : limits.getRunTimeout().toMillis();
        if (actualRunTimeoutMs != expectedRunTimeoutMs) {
            throw fail("effective RuntimeLimitsProperties run timeout must equal "
                    + expectedRunTimeoutMs + "ms");
        }
    }

    String namespace() { return namespace; }
    String configHash() { return configHash; }
    String revision() { return revision; }
    String schema() { return settings.schema(); }
    String isolatedJdbcUrl() { return isolatedJdbcUrl; }

    void markSuccessful() {
        successful = true;
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            cleanupRunningRows(successful ? "benchmark_context_closed" : "benchmark_aborted");
            updateSession(successful ? "COMPLETED" : "ABORTED");
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdownHook);
            } catch (IllegalStateException ignored) {
                // JVM shutdown already owns the hook.
            }
            closeQuietly(lockConnection);
        }
    }

    private void cleanupAfterAbort() {
        if (!closed.compareAndSet(false, true)) return;
        try {
            cleanupRunningRows("benchmark_process_terminated");
            updateSession("ABORTED");
        } finally {
            closeQuietly(lockConnection);
        }
    }

    private void cleanupRunningRows(String reason) {
        try (Statement statement = lockConnection.createStatement()) {
            String schema = quoted(settings.schema());
            if (tableExists(lockConnection, settings.schema(), "recommendation_agent_runs")) {
                try (PreparedStatement update = lockConnection.prepareStatement(
                        "update " + schema + ".recommendation_agent_runs set status='FAILED', recoverable=false, "
                                + "stop_reason=?, completed_at=clock_timestamp(), updated_at=clock_timestamp(), "
                                + "execution_token=null, execution_owner=null, execution_lease_until=null "
                                + "where status='RUNNING' and id like ?")) {
                    update.setString(1, reason);
                    update.setString(2, namespace + "__%");
                    update.executeUpdate();
                }
            }
            statement.executeUpdate("update " + schema + ".benchmark_run_manifests set status='ABORTED', "
                    + "completed_at=clock_timestamp() where namespace='" + sqlLiteral(namespace)
                    + "' and status='RUNNING'");
        } catch (Exception ignored) {
            // Best effort during JVM shutdown. The schema and non-recoverable rows still isolate the run.
        }
    }

    private void updateSession(String status) {
        try (PreparedStatement update = lockConnection.prepareStatement("update " + quoted(settings.schema())
                + ".benchmark_execution_sessions set status=?, completed_at=clock_timestamp() where namespace=?")) {
            update.setString(1, status);
            update.setString(2, namespace);
            update.executeUpdate();
        } catch (Exception ignored) {
            // See cleanupRunningRows: hard process death remains isolated by schema and recoverable=false.
        }
    }

    static String isolatedJdbcUrl(String baseUrl, String schema) {
        if (baseUrl == null || !baseUrl.toLowerCase().startsWith("jdbc:postgresql:")) {
            throw fail("benchmark datasource must be PostgreSQL");
        }
        String lower = baseUrl.toLowerCase();
        int current = lower.indexOf("currentschema=");
        if (current >= 0) {
            String value = baseUrl.substring(current + "currentSchema=".length()).split("[&#]", 2)[0];
            if (!schema.equals(value)) throw fail("datasource currentSchema must equal " + schema);
            return baseUrl;
        }
        String separator = baseUrl.contains("?") ? "&" : "?";
        return baseUrl + separator + "currentSchema="
                + URLEncoder.encode(schema, StandardCharsets.UTF_8)
                + "&ApplicationName=ecom-live-agent-benchmark";
    }

    private static void createSchemaAndManifestTables(Connection connection, String schemaName) throws Exception {
        String schema = quoted(schemaName);
        try (Statement statement = connection.createStatement()) {
            statement.execute("create schema if not exists " + schema);
            statement.execute("create table if not exists " + schema + ".benchmark_execution_sessions ("
                    + "namespace varchar(180) primary key, revision varchar(40) not null, config_hash varchar(64) not null, "
                    + "dataset_hash varchar(64) not null, scope varchar(16) not null, arm_set varchar(16) not null, "
                    + "case_count integer not null, arm_count integer not null, per_case_budget integer not null, "
                    + "total_budget integer not null, status varchar(16) not null, created_at timestamptz not null default clock_timestamp(), "
                    + "completed_at timestamptz)");
            statement.execute("create table if not exists " + schema + ".benchmark_run_manifests ("
                    + "run_id varchar(255) primary key, namespace varchar(180) not null references " + schema
                    + ".benchmark_execution_sessions(namespace), arm varchar(160) not null, case_id varchar(160) not null, "
                    + "topology varchar(32) not null, revision varchar(40) not null, config_hash varchar(64) not null, "
                    + "status varchar(16) not null, created_at timestamptz not null default clock_timestamp(), "
                    + "completed_at timestamptz, unique(namespace,arm,case_id))");
            statement.execute("create table if not exists " + schema + ".benchmark_provider_call_claims ("
                    + "id bigserial primary key, namespace varchar(180) not null references " + schema
                    + ".benchmark_execution_sessions(namespace), run_id varchar(255) not null references " + schema
                    + ".benchmark_run_manifests(run_id), arm varchar(160) not null, fingerprint_hash varchar(64) not null, "
                    + "created_at timestamptz not null default clock_timestamp(), unique(run_id,fingerprint_hash))");
        }
    }

    private static void failIfExternalExecutionExists(Connection connection, String schema) throws Exception {
        String quoted = quoted(schema);
        try (Statement statement = connection.createStatement();
             ResultSet sessions = statement.executeQuery("select count(*) from " + quoted
                     + ".benchmark_execution_sessions where status='RUNNING'")) {
            if (sessions.next() && sessions.getLong(1) > 0) {
                throw fail("benchmark schema contains an active external execution session");
            }
        }
        if (tableExists(connection, schema, "recommendation_agent_runs")) {
            try (Statement statement = connection.createStatement();
                 ResultSet runs = statement.executeQuery("select count(*) from " + quoted
                         + ".recommendation_agent_runs where status='RUNNING'")) {
                if (runs.next() && runs.getLong(1) > 0) {
                    throw fail("benchmark schema contains externally running recommendation Runs");
                }
            }
        }
    }

    private static void insertSession(Connection connection, LiveAgentBenchmarkGuard.Settings settings,
                                      String namespace, String revision, String configHash,
                                      String datasetHash) throws Exception {
        String sql = "insert into " + quoted(settings.schema())
                + ".benchmark_execution_sessions(namespace,revision,config_hash,dataset_hash,scope,arm_set,"
                + "case_count,arm_count,per_case_budget,total_budget,status) values (?,?,?,?,?,?,?,?,?,?, 'RUNNING')";
        try (PreparedStatement insert = connection.prepareStatement(sql)) {
            insert.setString(1, namespace);
            insert.setString(2, revision);
            insert.setString(3, configHash);
            insert.setString(4, datasetHash);
            insert.setString(5, settings.scope().name());
            insert.setString(6, settings.armSet().name());
            insert.setInt(7, settings.caseCount());
            insert.setInt(8, settings.variantCount());
            insert.setInt(9, settings.maxCallsPerCase());
            insert.setInt(10, settings.maxTotalCalls());
            insert.executeUpdate();
        }
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws Exception {
        try (PreparedStatement query = connection.prepareStatement("select exists(select 1 from information_schema.tables "
                + "where table_schema=? and table_name=?)")) {
            query.setString(1, schema);
            query.setString(2, table);
            try (ResultSet row = query.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }

    private static void require(ConfigurableEnvironment environment, String key, String expected) {
        String actual = environment.getProperty(key);
        if (!expected.equals(actual)) throw fail("effective Spring property " + key + " must equal " + expected);
    }

    private static void requireSecret(ConfigurableEnvironment environment, String key, String expected) {
        String actual = environment.getProperty(key);
        if (!java.util.Objects.equals(expected, actual)) {
            throw fail("effective Spring credential property " + key + " does not match guarded settings");
        }
    }

    private static String namespace(String revision, String configHash) {
        return "bench_" + revision.substring(0, Math.min(12, revision.length())) + "_"
                + configHash.substring(0, 10) + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static String quoted(String identifier) {
        if (!identifier.matches("[a-z][a-z0-9_]{0,62}")) throw fail("unsafe PostgreSQL identifier");
        return "\"" + identifier + "\"";
    }

    private static String sqlLiteral(String value) {
        return value.replace("'", "''");
    }

    private static void closeQuietly(Connection connection) {
        if (connection == null) return;
        try { connection.close(); } catch (Exception ignored) { }
    }

    private static IllegalStateException fail(String reason) {
        return new IllegalStateException("LIVE_BENCHMARK_ISOLATION: " + reason);
    }

    private static IllegalStateException fail(String reason, Throwable cause) {
        return new IllegalStateException("LIVE_BENCHMARK_ISOLATION: " + reason, cause);
    }
}
