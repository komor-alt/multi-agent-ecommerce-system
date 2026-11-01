package com.ecommerce.eval.live;

import com.ecommerce.MultiAgentApplication;
import com.ecommerce.config.RuntimeLimitsProperties;
import com.ecommerce.eval.benchmark.AgentBenchmark;
import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import com.ecommerce.eval.benchmark.AgentBenchmarkEvaluator;
import com.ecommerce.eval.benchmark.AgentBenchmarkExecutionInput;
import com.ecommerce.eval.benchmark.AgentBenchmarkFaultRegistry;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.persistence.RecommendationRecoveryProperties;
import com.ecommerce.runtime.persistence.RecommendationRunEntity;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.runtime.persistence.RecommendationRunRepository;
import com.ecommerce.service.AgentEvaluationProfile;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.ScenePathEnforcer;
import com.ecommerce.service.TracedRecommendationLlmClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Full 9-case x 2-arm Fake Provider lifecycle using PostgreSQL 16 + pgvector. */
@EnabledIfEnvironmentVariable(named = "ECOM_RUN_POSTGRES_TESTS", matches = "true")
class LiveBenchmarkPilotPostgresTest {
    private static final String REVISION = LiveBenchmarkSmokeEvidence.BASELINE_REVISION;
    private static final int PER_RUN_BUDGET = 14;
    private static final int TOTAL_BUDGET = 252;

    @Test
    void executesAllPilotCasesForBothCoreArmsAndVerifiesFaultsClaimsAndIsolation() throws Exception {
        AgentBenchmarkDataset.Loaded dataset = AgentBenchmarkDataset.loadAll();
        List<AgentBenchmarkDataset.Case> cases = dataset.cases().stream()
                .filter(AgentBenchmarkDataset.Case::pilot).toList();
        assertThat(cases).hasSize(9);
        String schema = "agent_benchmark_pilot_" + UUID.randomUUID().toString().replace("-", "");
        String selectedHash = LiveAgentBenchmarkRunnerTest.selectedDatasetHash(
                dataset.sha256(), cases, LiveAgentBenchmarkGuard.Scope.PILOT);
        LiveAgentBenchmarkGuard.Settings settings = settings(schema, 9, 2, PER_RUN_BUDGET, TOTAL_BUDGET);
        String configHash = "f".repeat(64);
        LiveBenchmarkIsolation isolation = null;
        ConfigurableApplicationContext app = null;
        String previousEvaluation = System.getProperty(AgentEvaluationProfile.ENABLE_PROPERTY);
        String smokeHistoryHash = smokeArtifactHash();
        Map<String, String> publicSchemaBaseline = publicSchemaSnapshot();
        try {
            System.setProperty(AgentEvaluationProfile.ENABLE_PROPERTY, "true");
            isolation = LiveBenchmarkIsolation.open(settings, REVISION, configHash, selectedHash);
            LiveBenchmarkIsolation activeIsolation = isolation;
            app = new SpringApplicationBuilder(MultiAgentApplication.class,
                    LiveBenchmarkConfiguration.class,
                    LiveBenchmarkSpringLifecyclePostgresTest.FakeProviderConfiguration.class)
                    .web(WebApplicationType.NONE)
                    .initializers(context -> activeIsolation.install(context.getEnvironment()))
                    .run();
            isolation.verify(app);
            assertThat(app.getBean(RuntimeLimitsProperties.class).getRunTimeout())
                    .isEqualTo(Duration.ofSeconds(90));
            assertThat(app.getBean(RecommendationRecoveryProperties.class).isEnabled()).isFalse();

            AtomicInteger providerCalls = app.getBean("fakeProviderCalls", AtomicInteger.class);
            assertThat(providerCalls).hasValue(0);
            LiveBenchmarkFixtureSeeder.seed(app, cases);
            LiveBenchmarkFixtureSeeder.verify(app, cases);
            Map<String, Long> firstFixtureCounts = fixtureCounts(isolation);
            LiveBenchmarkFixtureSeeder.seed(app, cases);
            assertThat(fixtureCounts(isolation)).isEqualTo(firstFixtureCounts);

            BenchmarkCallBudgetLedger ledger = app.getBean(BenchmarkCallBudgetLedger.class);
            AutonomousAgentLoopService agent = app.getBean(AutonomousAgentLoopService.class);
            RecommendationRunRepository runs = app.getBean(RecommendationRunRepository.class);
            RecommendationRunEventService eventStore = app.getBean(RecommendationRunEventService.class);
            @SuppressWarnings("unchecked")
            java.util.Set<LlmCallBudget> budgets = app.getBean("fakeProviderBudgets", java.util.Set.class);
            Map<String, AgentBenchmark.Variant> variants = coreVariants();
            Map<String, AgentBenchmark.CaseObservation> observations = new LinkedHashMap<>();
            Map<String, AgentLoopResponse> responsesByRun = new LinkedHashMap<>();
            Map<String, List<String>> plannerDiagnostics = new LinkedHashMap<>();

            for (AgentBenchmarkDataset.Case testCase : cases) {
                for (AgentBenchmark.Variant variant : variants.values()) {
                    String runId = runId(isolation.namespace(), variant, testCase);
                    ledger.registerRun(runId, variant.id(), testCase.id(), variant.topology());
                    List<AgentRunEvent> emitted = new ArrayList<>();
                    AgentLoopResponse response = null;
                    RuntimeException failure = null;
                    long start = System.nanoTime();
                    LiveBenchmarkSpringLifecyclePostgresTest.FakeProviderConfiguration
                            .resetSingleAgentDiagnostics();
                    try (AgentBenchmarkFaultRegistry.Scope ignored = AgentBenchmarkFaultRegistry.bind(
                            runId, testCase.faultPlan())) {
                        AgentEvaluationProfile.Settings evaluation = new AgentEvaluationProfile.Settings(
                                "single".equals(variant.topology()) ? AgentEvaluationProfile.Topology.SINGLE
                                        : AgentEvaluationProfile.Topology.MULTI,
                                variant.vetoEnabled(), variant.pathConstraint(), variant.parallelScheduling(),
                                20261030L);
                        response = AgentEvaluationProfile.run(evaluation,
                                () -> agent.run(request(runId, testCase), emitted::add));
                    } catch (RuntimeException error) {
                        failure = error;
                    } finally {
                        RecommendationRunEntity run = runs.findById(runId).orElseThrow();
                        String terminal = failure == null && "completed".equalsIgnoreCase(run.getStatus())
                                ? "COMPLETED" : failure == null && "blocked".equalsIgnoreCase(run.getStatus())
                                ? "BLOCKED" : "FAILED";
                        ledger.markTerminal(runId, terminal);
                    }
                    double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
                    plannerDiagnostics.put(runId, LiveBenchmarkSpringLifecyclePostgresTest
                            .FakeProviderConfiguration.singleAgentDiagnostics());
                    List<Map<String, Object>> persisted = eventStore.history(runId);
                    boolean durable = runs.findById(runId).isPresent()
                            && LiveAgentBenchmarkRunnerTest.hasPersistedExecutionTrace(persisted, testCase)
                            && LiveAgentBenchmarkRunnerTest.configuredModelOnly(persisted, "fixture-model");
                    AgentBenchmark.CaseObservation observed = AgentBenchmarkEvaluator.evaluate(
                            testCase, response, failure, emitted, latencyMs, true, durable,
                            new AgentBenchmark.PriceConfig(null, null));
                    observations.put(variant.id() + "/" + testCase.id(), observed);
                    if (response != null) responsesByRun.put(runId, response);
                    assertThat(observed.providerInvoked()).as("provider invoked for %s/%s", variant.id(), testCase.id())
                            .isTrue();
                    assertThat(observed.validExperiment()).as("valid trace for %s/%s: invalidReason=%s; trace=%s",
                            variant.id(), testCase.id(), observed.invalidReason(),
                            "persistedNames=" + persisted.stream().map(event -> event.get("name")).toList()
                                    + "; emittedNames=" + emitted.stream().map(AgentRunEvent::getName).toList()
                                    + "; failure=" + (failure == null ? "none" : failure.getClass().getSimpleName()
                                    + ":" + failure.getMessage())
                                    + "; llm=" + llmCallDiagnostics(emitted)
                                    + "; planner=" + plannerDiagnostics.get(runId))
                            .isTrue();
                    assertThat(observed.ruleViolation()).as("no rule violation for %s/%s", variant.id(), testCase.id())
                            .isFalse();
                    if ("completed".equals(testCase.expectedOutcome())) {
                        assertThat(observed.businessTaskSuccess()).as("business invariants for %s/%s",
                                variant.id(), testCase.id()).isTrue();
                        assertThat(observed.safeTaskSuccess()).isTrue();
                    } else {
                        assertThat(testCase.expectedOutcome()).isEqualTo("timeout");
                        assertThat(observed.actualOutcome()).isEqualTo("timeout");
                        assertThat(observed.observedFailureReason()).contains("run_deadline_exceeded");
                        assertThat(persisted).filteredOn(event -> "run.failed".equals(event.get("name")))
                                .anySatisfy(event -> assertThat(event.get("data"))
                                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                                        .containsEntry("errorType", "RunDeadlineExceededException"));
                        assertThat(observed.expectedBehaviorMatch()).as("expected safe timeout for %s/%s: %s; trace=%s",
                                variant.id(), testCase.id(), observed.expectationMismatch(),
                                "persisted=" + persisted + "; emitted=" + emitted.stream()
                                        .map(event -> Map.of("name", event.getName(), "data", event.getData()))
                                        .toList() + "; llm=" + llmCallDiagnostics(emitted))
                                .isTrue();
                    }
                }
            }

            ledger.verifyComplete(18);
            assertThat(ledger.snapshot()).containsEntry("runCount", 18L)
                    .containsEntry("totalBudget", TOTAL_BUDGET)
                    .containsEntry("providerCallClaims", (long) providerCalls.get());
            assertThat(providerCalls.get()).isLessThanOrEqualTo(TOTAL_BUDGET);
            assertClaimsWithinBounds(isolation, cases, variants, providerCalls.get());
            assertInventoryFaults(cases, variants, observations, responsesByRun, isolation.namespace());
            assertTimeouts(cases, variants, observations, isolation.namespace());
            assertThat(budgets).hasSize(18);
            assertThat(AgentBenchmarkFaultRegistry.activeBindings()).isZero();
            assertThat(publicSchemaSnapshot()).isEqualTo(publicSchemaBaseline);
            assertThat(smokeArtifactHash()).isEqualTo(smokeHistoryHash);
            isolation.markSuccessful();
        } finally {
            if (app != null) app.close();
            if (isolation != null) isolation.close();
            if (previousEvaluation == null) System.clearProperty(AgentEvaluationProfile.ENABLE_PROPERTY);
            else System.setProperty(AgentEvaluationProfile.ENABLE_PROPERTY, previousEvaluation);
            dropSchema(schema);
        }
    }

    @Test
    void pilotBudgetExhaustionAndAbortRejectFurtherClaimsAndRecovery() throws Exception {
        String schema = "agent_benchmark_abort_" + UUID.randomUUID().toString().replace("-", "");
        LiveAgentBenchmarkGuard.Settings settings = settings(schema, 9, 2, PER_RUN_BUDGET, TOTAL_BUDGET);
        String configHash = "e".repeat(64);
        LiveBenchmarkIsolation isolation = null;
        try {
            isolation = LiveBenchmarkIsolation.open(settings, REVISION, configHash, "a".repeat(64));
            String namespace = isolation.namespace();
            DriverManagerDataSource source = new DriverManagerDataSource(isolation.isolatedJdbcUrl(),
                    required("ECOM_POSTGRES_TEST_USER"), required("ECOM_POSTGRES_TEST_PASSWORD"));
            BenchmarkCallBudgetLedger ledger = new BenchmarkCallBudgetLedger(new JdbcTemplate(source),
                    new DataSourceTransactionManager(source), isolation.namespace(), REVISION, configHash,
                    9, 2, PER_RUN_BUDGET, TOTAL_BUDGET);
            String runId = isolation.namespace() + "__single__budget-probe";
            ledger.registerRun(runId, "single", "complex-homepage-001", "single");
            AtomicInteger fakeCalls = new AtomicInteger();
            LlmCallBudget budget = new LlmCallBudget(PER_RUN_BUDGET,
                    (phase, fingerprint) -> ledger.reserve(runId, phase, fingerprint));
            for (int index = 0; index < PER_RUN_BUDGET; index++) {
                var reservation = budget.reserve("fake", "budget-" + index);
                assertThat(reservation.acquired()).isTrue();
                fakeCalls.incrementAndGet();
            }
            assertThat(budget.reserve("fake", "budget-overrun").reason()).isEqualTo("llm_budget_exhausted");
            assertThat(fakeCalls).hasValue(PER_RUN_BUDGET);
            assertThat(ledger.snapshot()).containsEntry("providerCallClaims", (long) PER_RUN_BUDGET);

            isolation.close();
            isolation = null;
            assertThatThrownBy(() -> ledger.reserve(runId, "late", "after-abort"))
                    .hasMessageContaining("identity mismatch");
            try (Connection connection = connection(LiveBenchmarkIsolation.isolatedJdbcUrl(
                    required("ECOM_POSTGRES_TEST_URL"), schema))) {
                assertThat(singleString(connection,
                        "select status from benchmark_execution_sessions where namespace=?",
                        namespace)).isEqualTo("ABORTED");
                assertThat(singleString(connection,
                        "select status from benchmark_run_manifests where run_id=?", runId)).isEqualTo("ABORTED");
                assertThat(singleLong(connection,
                        "select count(*) from benchmark_provider_call_claims where run_id=?", runId))
                        .isEqualTo(PER_RUN_BUDGET);
            }
        } finally {
            if (isolation != null) isolation.close();
            dropSchema(schema);
        }
    }

    private static Map<String, AgentBenchmark.Variant> coreVariants() {
        List<AgentBenchmark.Variant> selected = AgentBenchmark.variants().stream()
                .filter(item -> "java-single-agent-veto-on-path-on".equals(item.id())
                        || AgentBenchmark.RUNNABLE_VARIANT.equals(item.id())).toList();
        assertThat(selected).extracting(AgentBenchmark.Variant::id).containsExactly(
                "java-single-agent-veto-on-path-on", AgentBenchmark.RUNNABLE_VARIANT);
        return selected.stream().collect(Collectors.toMap(AgentBenchmark.Variant::id, item -> item,
                (left, right) -> left, LinkedHashMap::new));
    }

    private static ToolLoopRequest request(String runId, AgentBenchmarkDataset.Case testCase) {
        RecommendationRequest request = RecommendationRequest.builder()
                .userId(testCase.userId())
                .scene("auto")
                .numItems(testCase.numItems())
                .country(testCase.country())
                .region(testCase.region())
                .locale(testCase.locale())
                .currency(testCase.currency())
                .platform(testCase.platform())
                .context(AgentBenchmarkExecutionInput.context(testCase))
                .build();
        return ToolLoopRequest.builder().runId(runId).request(request)
                .config(ToolLoopConfig.builder().maxSteps(20)
                        .toolWhitelist(ScenePathEnforcer.DEFAULT_WHITELIST).build())
                .build();
    }

    private static LiveAgentBenchmarkGuard.Settings settings(String schema, int cases, int arms,
                                                              int perRun, int total) {
        LiveRecommendationE2EGuard.Settings live = new LiveRecommendationE2EGuard.Settings(
                "LLM", perRun, required("ECOM_POSTGRES_TEST_URL"), required("ECOM_POSTGRES_TEST_USER"),
                required("ECOM_POSTGRES_TEST_PASSWORD"));
        return new LiveAgentBenchmarkGuard.Settings(live, LiveAgentBenchmarkGuard.Scope.PILOT,
                LiveAgentBenchmarkGuard.ArmSet.CORE, "fixture", "fixture-model", perRun, total,
                cases, arms, total, 2048, 20261030L, 30_000, 90_000, schema,
                new AgentBenchmark.PriceConfig(null, null));
    }

    private static String runId(String namespace, AgentBenchmark.Variant variant,
                                AgentBenchmarkDataset.Case testCase) {
        String arm = variant.id().equals(AgentBenchmark.RUNNABLE_VARIANT) ? "guarded_multi" : "single";
        return (namespace + "__" + arm + "__" + testCase.id()).replaceAll("[^A-Za-z0-9_-]", "_");
    }

    private static void assertClaimsWithinBounds(LiveBenchmarkIsolation isolation,
                                                 List<AgentBenchmarkDataset.Case> cases,
                                                 Map<String, AgentBenchmark.Variant> variants,
                                                 int fakeProviderCalls) throws Exception {
        try (Connection connection = connection(isolation.isolatedJdbcUrl())) {
            assertThat(singleLong(connection,
                    "select count(*) from benchmark_run_manifests where namespace=? and status in ('COMPLETED','FAILED','BLOCKED')",
                    isolation.namespace())).isEqualTo(18);
            assertThat(singleLong(connection,
                    "select count(*) from benchmark_run_manifests where namespace=? and status='RUNNING'",
                    isolation.namespace())).isZero();
            assertThat(singleLong(connection,
                    "select count(*) from benchmark_provider_call_claims where namespace=?",
                    isolation.namespace())).isEqualTo(fakeProviderCalls);
            assertThat(singleLong(connection,
                    "select count(*) from benchmark_provider_call_claims where namespace=? and run_id in "
                            + "(select run_id from benchmark_run_manifests where namespace=?)",
                    isolation.namespace(), isolation.namespace())).isEqualTo(fakeProviderCalls);
            try (Statement query = connection.createStatement();
                 ResultSet rows = query.executeQuery("select count(*) from (select run_id, count(*) calls "
                         + "from benchmark_provider_call_claims where namespace='" + isolation.namespace()
                         + "' group by run_id having count(*) > " + PER_RUN_BUDGET + ") over_budget")) {
                assertThat(rows.next()).isTrue();
                assertThat(rows.getLong(1)).isZero();
            }
            assertThat(singleLong(connection,
                    "select count(*) from benchmark_run_manifests where namespace=? and revision=? and config_hash=?",
                    isolation.namespace(), REVISION, isolation.configHash())).isEqualTo(18);
            for (AgentBenchmarkDataset.Case testCase : cases) {
                String expectedStatus = "completed".equals(testCase.expectedOutcome()) ? "COMPLETED" : "FAILED";
                for (AgentBenchmark.Variant variant : variants.values()) {
                    assertThat(singleString(connection,
                            "select status from benchmark_run_manifests where run_id=?",
                            runId(isolation.namespace(), variant, testCase)))
                            .as("terminal manifest state for %s/%s", variant.id(), testCase.id())
                            .isEqualTo(expectedStatus);
                }
            }
        }
        assertThat((long) cases.size() * variants.size()).isEqualTo(18L);
    }

    private static void assertInventoryFaults(List<AgentBenchmarkDataset.Case> cases,
                                              Map<String, AgentBenchmark.Variant> variants,
                                              Map<String, AgentBenchmark.CaseObservation> observations,
                                              Map<String, AgentLoopResponse> responsesByRun,
                                              String namespace) {
        for (String caseId : List.of("complex-homepage-005", "complex-campaign-017")) {
            AgentBenchmarkDataset.Case testCase = cases.stream().filter(item -> item.id().equals(caseId))
                    .findFirst().orElseThrow();
            for (AgentBenchmark.Variant variant : variants.values()) {
                AgentBenchmark.CaseObservation observation = observations.get(variant.id() + "/" + caseId);
                assertThat(observation.observedTools().stream().filter("search_products"::equals).count())
                        .as("real second recall on %s/%s", variant.id(), caseId).isGreaterThanOrEqualTo(2);
                String runId = runId(namespace, variant, testCase);
                AgentLoopResponse response = responsesByRun.get(runId);
                assertThat(response).as("completed fake-provider response for %s/%s", variant.id(), caseId)
                        .isNotNull();
                assertThat(response.getObservations()).anySatisfy(toolObservation -> {
                    assertThat(toolObservation.getToolName()).isEqualTo("check_inventory");
                    assertThat(toolObservation.getData()).containsEntry("faultInjected", "inventory_shortage")
                            .containsEntry("revisionRequested", true);
                });
            }
        }
    }

    private static void assertTimeouts(List<AgentBenchmarkDataset.Case> cases,
                                       Map<String, AgentBenchmark.Variant> variants,
                                       Map<String, AgentBenchmark.CaseObservation> observations,
                                       String namespace) {
        for (String caseId : List.of("complex-campaign-018", "complex-retention-030")) {
            AgentBenchmarkDataset.Case testCase = cases.stream().filter(item -> item.id().equals(caseId))
                    .findFirst().orElseThrow();
            for (AgentBenchmark.Variant variant : variants.values()) {
                AgentBenchmark.CaseObservation observation = observations.get(variant.id() + "/" + caseId);
                assertThat(observation.actualOutcome()).isEqualTo("timeout");
                assertThat(observation.expectedBehaviorMatch()).isTrue();
                assertThat(observation.ruleViolation()).isFalse();
                assertThat(observation.observedFailureReason()).contains("run_deadline_exceeded");
                String targetTool = "complex-campaign-018".equals(caseId)
                        ? "check_fulfillment" : "get_recent_orders";
                assertThat(observation.observedTools()).contains(targetTool);
            }
        }
    }

    private static Map<String, String> publicSchemaSnapshot() throws Exception {
        Map<String, String> result = new HashMap<>();
        try (Connection connection = connection(required("ECOM_POSTGRES_TEST_URL"))) {
            for (String table : List.of("recommendation_agent_runs", "recommendation_run_events",
                    "recommendation_agent_tasks", "recommendation_agent_artifacts", "recommendation_outbox",
                    "products", "inventory", "orders", "users", "user_events")) {
                if (!tableExists(connection, "public", table)) {
                    result.put(table, "absent");
                    continue;
                }
                String sql = "select count(*), md5(coalesce(string_agg(row_json, chr(10) order by row_json), '')) "
                        + "from (select to_jsonb(t)::text as row_json from public.\"" + table + "\" t) public_rows";
                try (Statement query = connection.createStatement(); ResultSet row = query.executeQuery(sql)) {
                    assertThat(row.next()).isTrue();
                    result.put(table, row.getLong(1) + ":" + row.getString(2));
                }
            }
        }
        return Map.copyOf(result);
    }

    private static Map<String, Long> fixtureCounts(LiveBenchmarkIsolation isolation) throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = connection(isolation.isolatedJdbcUrl())) {
            for (String table : List.of("products", "inventory", "orders", "users", "user_events")) {
                counts.put(table, countRows(connection, isolation.schema(), table));
            }
        }
        return Map.copyOf(counts);
    }

    private static List<Map<String, Object>> llmCallDiagnostics(List<AgentRunEvent> events) {
        return events.stream().filter(event -> "llm.call".equals(event.getName()))
                .map(AgentRunEvent::getData).map(data -> {
                    Map<String, Object> safe = new LinkedHashMap<>();
                    for (String key : List.of("phase", "status", "fallbackReason", "providerInvoked")) {
                        Object value = data.get(key);
                        if (value != null) safe.put(key, value);
                    }
                    return Map.copyOf(safe);
                }).toList();
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws Exception {
        try (var query = connection.prepareStatement(
                "select exists(select 1 from information_schema.tables where table_schema=? and table_name=?)")) {
            query.setString(1, schema);
            query.setString(2, table);
            try (ResultSet row = query.executeQuery()) { return row.next() && row.getBoolean(1); }
        }
    }

    private static long countRows(Connection connection, String table) throws Exception {
        return countRows(connection, "public", table);
    }

    private static long countRows(Connection connection, String schema, String table) throws Exception {
        if (!schema.matches("[a-z][a-z0-9_]*") || !table.matches("[a-z][a-z0-9_]*")) {
            throw new IllegalArgumentException("Unsafe table identifier");
        }
        try (Statement query = connection.createStatement();
             ResultSet row = query.executeQuery("select count(*) from \"" + schema + "\".\"" + table + "\"")) {
            assertThat(row.next()).isTrue();
            return row.getLong(1);
        }
    }

    private static String smokeArtifactHash() throws Exception {
        Path history = Path.of("target", "agent-benchmark", "java-recommendation-agent-ablation-v4.json");
        if (Files.isRegularFile(history)) return AgentBenchmarkDataset.sha256(Files.readAllBytes(history));
        try (var stream = LiveBenchmarkPilotPostgresTest.class.getClassLoader()
                .getResourceAsStream(LiveBenchmarkSmokeEvidence.TEST_REPORT_RESOURCE)) {
            if (stream == null) throw new IllegalStateException("pinned smoke evidence fixture missing");
            return AgentBenchmarkDataset.sha256(stream.readAllBytes());
        }
    }

    private static String singleString(Connection connection, String sql, String argument) throws Exception {
        try (var query = connection.prepareStatement(sql)) {
            query.setString(1, argument);
            try (ResultSet row = query.executeQuery()) { return row.next() ? row.getString(1) : null; }
        }
    }

    private static long singleLong(Connection connection, String sql, Object... arguments) throws Exception {
        try (var query = connection.prepareStatement(sql)) {
            for (int index = 0; index < arguments.length; index++) query.setObject(index + 1, arguments[index]);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static Connection connection(String url) throws Exception {
        return DriverManager.getConnection(url, required("ECOM_POSTGRES_TEST_USER"),
                required("ECOM_POSTGRES_TEST_PASSWORD"));
    }

    private static void dropSchema(String schema) throws Exception {
        try (Connection connection = connection(required("ECOM_POSTGRES_TEST_URL"));
             Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists \"" + schema + "\" cascade");
        }
        try (Connection connection = connection(required("ECOM_POSTGRES_TEST_URL"));
             var query = connection.prepareStatement(
                     "select exists(select 1 from information_schema.schemata where schema_name=?)")) {
            query.setString(1, schema);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getBoolean(1)).as("temporary benchmark schema cleanup").isFalse();
            }
        }
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }
}
