package com.ecommerce.eval.live;

import com.ecommerce.MultiAgentApplication;
import com.ecommerce.config.RuntimeLimitsProperties;
import com.ecommerce.data.repository.RecInventoryRepository;
import com.ecommerce.data.repository.RecOrderRepository;
import com.ecommerce.data.repository.RecProductRepository;
import com.ecommerce.data.repository.RecUserEventRepository;
import com.ecommerce.data.repository.RecUserRepository;
import com.ecommerce.eval.benchmark.AgentBenchmark;
import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.runtime.persistence.RecommendationRunEntity;
import com.ecommerce.runtime.persistence.RecommendationRunEventService;
import com.ecommerce.runtime.persistence.RecommendationRunRepository;
import com.ecommerce.runtime.persistence.RecommendationRuntimeStore;
import com.ecommerce.service.AgentEvaluationProfile;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.RecommendationModeResolver;
import com.ecommerce.service.ScenePathEnforcer;
import com.ecommerce.service.TracedRecommendationLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Full benchmark startup, fake-provider execution, abort and PostgreSQL cleanup contract. */
@EnabledIfEnvironmentVariable(named = "ECOM_RUN_POSTGRES_TESTS", matches = "true")
class LiveBenchmarkSpringLifecyclePostgresTest {
    private static final String REVISION = "272744f1546edd83cb45475eabddddc4121c4022";
    private static final String CONFIG_HASH = "c".repeat(64);
    private static final String DATASET_HASH = "d".repeat(64);

    @Test
    void springBenchmarkContextExecutesFakeProviderThenAbortsAndCleansItsNamespace() throws Exception {
        String schema = "agent_benchmark_lifecycle_" + UUID.randomUUID().toString().replace("-", "");
        LiveBenchmarkIsolation isolation = null;
        ConfigurableApplicationContext app = null;
        String previousRunTimeout = System.getProperty("agent.limits.run-timeout");
        System.setProperty(AgentEvaluationProfile.ENABLE_PROPERTY, "true");
        System.setProperty("agent.limits.run-timeout", "5s");
        try {
            isolation = LiveBenchmarkIsolation.open(settings(schema), REVISION, CONFIG_HASH, DATASET_HASH);
            LiveBenchmarkIsolation activeIsolation = isolation;
            Map<String, Long> sharedSchemaBaseline = sharedSchemaCounts();
            app = new SpringApplicationBuilder(MultiAgentApplication.class,
                    LiveBenchmarkConfiguration.class, FakeProviderConfiguration.class)
                    .web(WebApplicationType.NONE)
                    .initializers(context -> activeIsolation.install(context.getEnvironment()))
                    .run();
            AtomicInteger providerCalls = app.getBean("fakeProviderCalls", AtomicInteger.class);
            isolation.verify(app);
            assertThat(app.getEnvironment().getProperty("agent.limits.run-timeout")).isEqualTo("90000ms");
            assertThat(app.getBean(RuntimeLimitsProperties.class).getRunTimeout()).isEqualTo(Duration.ofSeconds(90));
            assertThat(providerCalls).as("Provider must not run before isolation verification").hasValue(0);
            List<AgentBenchmarkDataset.Case> smokeCases = AgentBenchmarkDataset.selectSmoke(
                    AgentBenchmarkDataset.loadAll().cases());
            LiveBenchmarkFixtureSeeder.seed(app, smokeCases);
            LiveBenchmarkFixtureSeeder.verify(app, smokeCases);

            BenchmarkCallBudgetLedger ledger = app.getBean(BenchmarkCallBudgetLedger.class);
            AutonomousAgentLoopService agent = app.getBean(AutonomousAgentLoopService.class);
            RecommendationRunRepository runs = app.getBean(RecommendationRunRepository.class);
            RecommendationRunEventService events = app.getBean(RecommendationRunEventService.class);
            RecommendationRuntimeStore runtimeStore = app.getBean(RecommendationRuntimeStore.class);
            @SuppressWarnings("unchecked")
            Set<LlmCallBudget> providerBudgets = app.getBean("fakeProviderBudgets", Set.class);
            @SuppressWarnings("unchecked")
            Set<String> providerPhases = app.getBean("fakeProviderPhases", Set.class);

            String completedRunId = runId(isolation.namespace(), "completed");
            ToolLoopRequest completedRequest = request(completedRunId);
            ledger.registerRun(completedRunId, "guarded-multi", "completed-case", "multi");
            AgentLoopResponse response;
            try {
                response = AgentEvaluationProfile.run(
                        new AgentEvaluationProfile.Settings(
                                AgentEvaluationProfile.Topology.MULTI, true, true, true, 20261030L),
                        () -> agent.run(completedRequest, ignored -> { }));
            } finally {
                RecommendationRunEntity completed = runs.findById(completedRunId).orElseThrow();
                ledger.markTerminal(completedRunId,
                        "COMPLETED".equals(completed.getStatus()) ? "COMPLETED" : "FAILED");
            }

            List<Map<String, Object>> persistedEvents = events.history(completedRunId);
            List<? extends Map<?, ?>> llmCalls = persistedEvents.stream()
                    .filter(event -> "llm.call".equals(event.get("name")))
                    .map(event -> event.get("data"))
                    .filter(Map.class::isInstance)
                    .map(value -> (Map<?, ?>) value)
                    .toList();
            assertThat(response).isNotNull();
            assertThat(providerCalls).hasPositiveValue();
            assertThat(llmCalls).anySatisfy(call -> {
                assertThat(call.get("providerInvoked")).isEqualTo(true);
                assertThat(call.get("provider")).isEqualTo("fixture");
                assertThat(call.get("model")).isEqualTo("fixture-model");
            });

            String singleRunId = runId(isolation.namespace(), "single-auto");
            ledger.registerRun(singleRunId, "single", "single-auto-case", "single");
            AgentLoopResponse singleResponse;
            try {
                singleResponse = AgentEvaluationProfile.run(
                        new AgentEvaluationProfile.Settings(
                                AgentEvaluationProfile.Topology.SINGLE, true, true, false, 20261030L),
                        () -> agent.run(request(singleRunId, "auto"), ignored -> { }));
            } finally {
                RecommendationRunEntity single = runs.findById(singleRunId).orElseThrow();
                ledger.markTerminal(singleRunId,
                        "COMPLETED".equals(single.getStatus()) ? "COMPLETED" : "FAILED");
            }
            assertThat(singleResponse).isNotNull();

            String campaignRunId = runId(isolation.namespace(), "campaign");
            ledger.registerRun(campaignRunId, "guarded-multi", "campaign-case", "multi");
            AgentLoopResponse campaignResponse;
            try {
                campaignResponse = AgentEvaluationProfile.run(
                        new AgentEvaluationProfile.Settings(
                                AgentEvaluationProfile.Topology.MULTI, true, true, true, 20261030L),
                        () -> agent.run(request(campaignRunId, "campaign"), ignored -> { }));
            } finally {
                RecommendationRunEntity campaign = runs.findById(campaignRunId).orElseThrow();
                ledger.markTerminal(campaignRunId,
                        "COMPLETED".equals(campaign.getStatus()) ? "COMPLETED" : "FAILED");
            }
            assertThat(campaignResponse).isNotNull();

            // Parallel batches execute server-frozen actions and intentionally do not ask a
            // specialist LLM to choose again. Exercise the real specialist planner entry point
            // with parallel scheduling and the scene path guard disabled, where PROFILE has two
            // valid bounded actions and must choose through the same Run-scoped budget.
            String specialistRunId = runId(isolation.namespace(), "specialist-planner");
            ledger.registerRun(specialistRunId, "guarded-multi", "specialist-planner-case", "multi");
            AgentLoopResponse specialistResponse;
            try {
                specialistResponse = AgentEvaluationProfile.run(
                        new AgentEvaluationProfile.Settings(
                                AgentEvaluationProfile.Topology.MULTI, true, false, false, 20261030L),
                        () -> agent.run(request(specialistRunId), ignored -> { }));
            } finally {
                RecommendationRunEntity specialist = runs.findById(specialistRunId).orElseThrow();
                ledger.markTerminal(specialistRunId,
                        "COMPLETED".equals(specialist.getStatus()) ? "COMPLETED" : "FAILED");
            }
            assertThat(specialistResponse).isNotNull();
            assertThat(providerBudgets).hasSize(4);
            assertThat(providerPhases).contains("scene_intent", "single_agent", "supervisor",
                    "specialist_profile", "user_profile", "product_rerank", "marketing_copy",
                    "final_decision");
            assertThat(((Number) ledger.snapshot().get("providerCallClaims")).longValue())
                    .isEqualTo(providerCalls.get());

            String interruptedRunId = runId(isolation.namespace(), "interrupted");
            ToolLoopRequest interruptedRequest = request(interruptedRunId);
            ledger.registerRun(interruptedRunId, "single", "interrupted-case", "single");
            runtimeStore.startRecoverableRun(interruptedRequest);
            RecommendationRunEntity interruptedBeforeAbort = runs.findById(interruptedRunId).orElseThrow();
            assertThat(interruptedBeforeAbort.getStatus()).isEqualTo("RUNNING");
            assertThat(interruptedBeforeAbort.isRecoverable()).isTrue();

            LlmCallBudget interruptedBudget = new LlmCallBudget(20,
                    (phase, fingerprint) -> ledger.reserve(interruptedRunId, phase, fingerprint));
            TracedRecommendationLlmClient fakeClient = app.getBean(TracedRecommendationLlmClient.class);
            CountDownLatch attemptStarted = new CountDownLatch(1);
            CountDownLatch attemptReleased = new CountDownLatch(1);
            ExecutorService executor = Executors.newSingleThreadExecutor();
            CompletableFuture<Object> lateAsyncCall = CompletableFuture.supplyAsync(() -> {
                attemptStarted.countDown();
                try {
                    if (!attemptReleased.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("late benchmark call was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return fakeClient.complete("user_profile", "system", "input", interruptedBudget,
                        "after-abort:async", raw -> raw);
            }, executor);
            assertThat(attemptStarted.await(5, TimeUnit.SECONDS)).isTrue();
            int callsBeforeAbort = providerCalls.get();

            isolation.close();
            isolation = null;
            attemptReleased.countDown();

            assertThatThrownBy(() -> ledger.reserve(interruptedRunId, "supervisor", "after-abort"))
                    .hasMessageContaining("identity mismatch");
            assertThatThrownBy(lateAsyncCall::join)
                    .hasRootCauseInstanceOf(IllegalStateException.class)
                    .hasRootCauseMessage("LIVE_BENCHMARK_LEDGER: benchmark session commit/config/budget identity mismatch");
            assertThatThrownBy(() -> fakeClient.complete("user_profile", "system", "input",
                    interruptedBudget, "after-abort:retry:1", raw -> raw))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("identity mismatch");
            assertThat(providerCalls).hasValue(callsBeforeAbort);
            executor.shutdownNow();
            assertLifecycleRows(schema, activeIsolation.namespace(), completedRunId, interruptedRunId,
                    providerCalls.get());
            assertThat(sharedSchemaCounts()).isEqualTo(sharedSchemaBaseline);
        } finally {
            if (app != null) app.close();
            if (isolation != null) isolation.close();
            System.clearProperty(AgentEvaluationProfile.ENABLE_PROPERTY);
            if (previousRunTimeout == null) {
                System.clearProperty("agent.limits.run-timeout");
            } else {
                System.setProperty("agent.limits.run-timeout", previousRunTimeout);
            }
            dropSchema(schema);
        }
    }

    private static void assertSmokeFixtures(ConfigurableApplicationContext app) {
        RecUserRepository users = app.getBean(RecUserRepository.class);
        RecProductRepository products = app.getBean(RecProductRepository.class);
        RecInventoryRepository inventory = app.getBean(RecInventoryRepository.class);
        RecOrderRepository orders = app.getBean(RecOrderRepository.class);
        RecUserEventRepository events = app.getBean(RecUserEventRepository.class);
        for (AgentBenchmarkDataset.Case benchmarkCase : AgentBenchmarkDataset.selectSmoke(
                AgentBenchmarkDataset.loadAll().cases())) {
            assertThat(users.findByUserId(benchmarkCase.userId()))
                    .as("user for %s", benchmarkCase.id()).get()
                    .satisfies(user -> {
                        assertThat(user.getCountry()).isEqualTo(benchmarkCase.country());
                        assertThat(user.getCurrency()).isEqualTo(benchmarkCase.currency());
                        assertThat(user.getLocale()).isEqualTo(benchmarkCase.locale());
                        assertThat(user.getPlatform()).isEqualTo(benchmarkCase.platform());
                    });
            var eligible = products.findByMarket(benchmarkCase.platform(), benchmarkCase.country(),
                    benchmarkCase.region(), benchmarkCase.currency());
            assertThat(eligible).as("market products for %s", benchmarkCase.id()).isNotEmpty();
            assertThat(eligible).anySatisfy(product -> assertThat(inventory.findByProductIdAndCountry(
                            product.getProductId(), benchmarkCase.country()))
                    .get().satisfies(row -> {
                        assertThat(row.getStock()).isPositive();
                        assertThat(row.isRestricted()).isFalse();
                    }));
            assertThat(orders.findByUserId(benchmarkCase.userId()))
                    .anySatisfy(order -> {
                        assertThat(order.getCountry()).isEqualTo(benchmarkCase.country());
                        assertThat(order.getCurrency()).isEqualTo(benchmarkCase.currency());
                        assertThat(order.getPlatform()).isEqualTo(benchmarkCase.platform());
                    });
            assertThat(events.countByUserId(benchmarkCase.userId()))
                    .as("user events for %s", benchmarkCase.id()).isPositive();
        }
    }

    private static ToolLoopRequest request(String runId) {
        return request(runId, "homepage");
    }

    private static ToolLoopRequest request(String runId, String scene) {
        boolean campaign = "campaign".equals(scene);
        RecommendationRequest request = RecommendationRequest.builder()
                .userId(campaign ? "user_889" : "user_001")
                .scene(scene)
                .numItems(1)
                .platform("shopify")
                .region("SEA")
                .country(campaign ? "TH" : "SG")
                .locale(campaign ? "th-TH" : "en-SG")
                .currency(campaign ? "THB" : "SGD")
                .context(Map.of("intentText", "Build a homepage recommendation for the current customer."))
                .build();
        return ToolLoopRequest.builder().runId(runId).request(request)
                .config(ToolLoopConfig.builder().maxSteps(16)
                        .toolWhitelist(ScenePathEnforcer.DEFAULT_WHITELIST).build())
                .build();
    }

    private static LiveAgentBenchmarkGuard.Settings settings(String schema) {
        LiveRecommendationE2EGuard.Settings live = new LiveRecommendationE2EGuard.Settings(
                "LLM", 20, required("ECOM_POSTGRES_TEST_URL"), required("ECOM_POSTGRES_TEST_USER"),
                required("ECOM_POSTGRES_TEST_PASSWORD"));
        return new LiveAgentBenchmarkGuard.Settings(live, LiveAgentBenchmarkGuard.Scope.SMOKE,
                LiveAgentBenchmarkGuard.ArmSet.CORE, "fixture", "fixture-model", 20, 120,
                3, 2, 120, 256, 20261030L, 1_000, 90_000, schema,
                new AgentBenchmark.PriceConfig(null, null));
    }

    private static String runId(String namespace, String suffix) {
        return namespace + "__guarded_multi__" + suffix;
    }

    private static void assertLifecycleRows(String schema, String namespace, String completedRunId,
                                            String interruptedRunId, int providerCalls) throws Exception {
        try (Connection connection = connection(LiveBenchmarkIsolation.isolatedJdbcUrl(
                required("ECOM_POSTGRES_TEST_URL"), schema))) {
            try (PreparedStatement query = connection.prepareStatement(
                    "select status,recoverable,stop_reason from recommendation_agent_runs where id=?")) {
                query.setString(1, interruptedRunId);
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    assertThat(row.getString("status")).isEqualTo("FAILED");
                    assertThat(row.getBoolean("recoverable")).isFalse();
                    assertThat(row.getString("stop_reason")).isEqualTo("benchmark_aborted");
                }
            }
            assertThat(singleString(connection,
                    "select status from benchmark_execution_sessions where namespace=?", namespace))
                    .isEqualTo("ABORTED");
            assertThat(singleString(connection,
                    "select status from benchmark_run_manifests where run_id=?", interruptedRunId))
                    .isEqualTo("ABORTED");
            assertThat(singleString(connection,
                    "select status from benchmark_run_manifests where run_id=?", completedRunId))
                    .isIn("COMPLETED", "FAILED");
            assertThat(singleLong(connection,
                    "select count(*) from benchmark_provider_call_claims where namespace=?", namespace))
                    .isEqualTo(providerCalls);
        }
    }

    private static Map<String, Long> sharedSchemaCounts() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = connection(required("ECOM_POSTGRES_TEST_URL"))) {
            for (String table : List.of(
                    "recommendation_agent_runs", "recommendation_run_events",
                    "recommendation_agent_tasks", "recommendation_agent_artifacts",
                    "recommendation_outbox", "products", "inventory", "orders", "users", "user_events")) {
                counts.put(table, tableExists(connection, "public", table)
                        ? countRows(connection, "public", table) : 0L);
            }
        }
        return Map.copyOf(counts);
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

    private static String singleString(Connection connection, String sql, String argument) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, argument);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long singleLong(Connection connection, String sql, String argument) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, argument);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).isTrue();
                return row.getLong(1);
            }
        }
    }

    private static boolean tableExists(Connection connection, String schema, String table) throws Exception {
        try (PreparedStatement query = connection.prepareStatement(
                "select exists(select 1 from information_schema.tables where table_schema=? and table_name=?)")) {
            query.setString(1, schema);
            query.setString(2, table);
            try (ResultSet row = query.executeQuery()) {
                return row.next() && row.getBoolean(1);
            }
        }
    }

    private static void dropSchema(String schema) throws Exception {
        try (Connection connection = connection(required("ECOM_POSTGRES_TEST_URL"));
             Statement statement = connection.createStatement()) {
            statement.execute("drop schema if exists \"" + schema + "\" cascade");
        }
    }

    private static Connection connection(String url) throws Exception {
        return DriverManager.getConnection(url, required("ECOM_POSTGRES_TEST_USER"),
                required("ECOM_POSTGRES_TEST_PASSWORD"));
    }

    private static String required(String key) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + key);
        return value;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class FakeProviderConfiguration {
        private static final java.util.List<String> SINGLE_AGENT_DIAGNOSTICS =
                new java.util.concurrent.CopyOnWriteArrayList<>();

        static void resetSingleAgentDiagnostics() { SINGLE_AGENT_DIAGNOSTICS.clear(); }
        static java.util.List<String> singleAgentDiagnostics() { return java.util.List.copyOf(SINGLE_AGENT_DIAGNOSTICS); }

        @Bean
        @Primary
        RecommendationModeResolver fakeRecommendationModeResolver() {
            return new RecommendationModeResolver("LLM", "benchmark_fake_provider_no_network", true);
        }

        @Bean("fakeProviderCalls")
        AtomicInteger fakeProviderCalls() {
            return new AtomicInteger();
        }

        @Bean("fakeProviderBudgets")
        Set<LlmCallBudget> fakeProviderBudgets() {
            return java.util.concurrent.ConcurrentHashMap.newKeySet();
        }

        @Bean("fakeProviderPhases")
        Set<String> fakeProviderPhases() {
            return java.util.concurrent.ConcurrentHashMap.newKeySet();
        }

        @Bean
        @Primary
        @SuppressWarnings({"unchecked", "rawtypes"})
        TracedRecommendationLlmClient fakeRecommendationLlmClient(
                AtomicInteger fakeProviderCalls,
                @org.springframework.beans.factory.annotation.Qualifier("fakeProviderBudgets")
                Set<LlmCallBudget> fakeProviderBudgets,
                @org.springframework.beans.factory.annotation.Qualifier("fakeProviderPhases")
                Set<String> fakeProviderPhases) {
            TracedRecommendationLlmClient client = mock(TracedRecommendationLlmClient.class);
            when(client.timeoutMs()).thenReturn(1_000L);
            doAnswer(invocation -> {
                LlmCallBudget budget = invocation.getArgument(1);
                String reason = invocation.getArgument(2);
                budget.recordTrace(trace("local", "fallback", reason, false));
                return null;
            }).when(client).recordLocalFallback(anyString(), any(LlmCallBudget.class), anyString());
            when(client.complete(anyString(), anyString(), anyString(), any(LlmCallBudget.class),
                    anyString(), any(TracedRecommendationLlmClient.OutputParser.class)))
                    .thenAnswer(invocation -> {
                        String phase = invocation.getArgument(0);
                        String prompt = invocation.getArgument(2);
                        LlmCallBudget budget = invocation.getArgument(3);
                        String fingerprint = invocation.getArgument(4);
                        TracedRecommendationLlmClient.OutputParser parser = invocation.getArgument(5);
                        LlmCallBudget.Reservation reservation = budget.reserve(phase, fingerprint);
                        if (!reservation.acquired()) {
                            budget.recordTrace(trace(phase, "fallback", reservation.reason(), false));
                            throw new TracedRecommendationLlmClient.TracedLlmException(reservation.reason());
                        }
                        fakeProviderBudgets.add(budget);
                        fakeProviderPhases.add(phase);
                        fakeProviderCalls.incrementAndGet();
                        try {
                            Object parsed = parser.parse(responseFor(phase, prompt));
                            budget.recordTrace(trace(phase, "success", null, true));
                            return parsed;
                        } catch (TracedRecommendationLlmClient.OutputValidationException invalid) {
                            budget.recordTrace(trace(phase, "fallback", invalid.reason(), true));
                            throw new TracedRecommendationLlmClient.TracedLlmException(
                                    invalid.reason(), invalid.diagnostics());
                        } catch (Exception invalid) {
                            budget.recordTrace(trace(phase, "fallback", "invalid_json", true));
                            throw new TracedRecommendationLlmClient.TracedLlmException("invalid_json");
                        }
                    });
            return client;
        }

        private static LlmCallBudget.CallTrace trace(String phase, String status, String reason,
                                                      boolean providerInvoked) {
            return new LlmCallBudget.CallTrace(phase, "fixture", "fixture-model", 1,
                    status, reason, providerInvoked ? 3 : null, providerInvoked ? 2 : null,
                    providerInvoked ? 5 : null, providerInvoked ? "available" : "unavailable",
                    providerInvoked);
        }

        private static String responseFor(String phase, String prompt) throws Exception {
            return switch (phase) {
                case "scene_intent" -> "{\"scene\":\"" + inferScene(prompt) + "\"}";
                case "user_profile" -> """
                        {"segments":["active"],"preferred_categories":[],"price_range":[0,2000],
                         "rfm_score":{"recency":0.8,"frequency":0.5,"monetary":0.6},
                         "real_time_tags":{}}
                        """;
                case "supervisor" -> "{\"thought\":\"Select the next state-allowed specialist\",\"agent\":\""
                        + new ObjectMapper().readTree(prompt).path("expectedNextAgent").asText("SUPERVISOR") + "\"}";
                case "single_agent" -> "{\"thought\":\"Select a state-valid tool that advances the observed run\",\"action\":\""
                        + nextUnobservedTool(prompt) + "\"}";
                case "final_decision" -> "{\"summary\":\"Fixture recommendation decision.\",\"productReasons\":[]}";
                case "product_rerank" -> jsonProductIds(prompt);
                case "marketing_copy" -> jsonCopies(prompt);
                default -> "{\"action\":\"get_user_profile\"}";
            };
        }

        private static String inferScene(String prompt) {
            String lower = prompt == null ? "" : prompt.toLowerCase(java.util.Locale.ROOT);
            if (lower.contains("retention") || lower.contains("win-back")) return "retention";
            if (lower.contains("campaign")) return "campaign";
            return "homepage";
        }

        private static String nextUnobservedTool(String prompt) throws Exception {
            JsonNode state = new ObjectMapper().readTree(prompt);
            List<String> candidates = new java.util.ArrayList<>();
            state.path("candidateTools").forEach(item -> candidates.add(item.asText()));
            JsonNode observations = state.path("observations");
            Map<String, Integer> observedCounts = new java.util.HashMap<>();
            boolean revisionRequested = false;
            for (JsonNode observation : observations) {
                String tool = observation.path("toolName").asText("");
                if (!tool.isBlank()) observedCounts.merge(tool, 1, Integer::sum);
                revisionRequested |= observation.path("data").path("revisionRequested").asBoolean(false);
            }
            String scene = state.path("scene").asText("homepage");
            int requiredSearches = revisionRequested ? 2 : 1;
            int observedSearches = observedCounts.getOrDefault("search_products", 0);
            int observedFulfillmentChecks = observedCounts.getOrDefault("check_fulfillment", 0);
            for (String tool : preferredTools(scene)) {
                int required = switch (tool) {
                    case "search_products" -> requiredSearches;
                    case "check_fulfillment" -> observedSearches;
                    case "check_inventory" -> "campaign".equals(scene)
                            ? observedFulfillmentChecks : observedSearches;
                    default -> 1;
                };
                if (observedCounts.getOrDefault(tool, 0) < required && candidates.contains(tool)) {
                    SINGLE_AGENT_DIAGNOSTICS.add("scene=" + scene + "; candidates=" + candidates
                            + "; observed=" + observedCounts + "; revision=" + revisionRequested
                            + "; selected=" + tool);
                    return tool;
                }
            }
            String selected = candidates.stream()
                    .filter(candidate -> observedCounts.getOrDefault(candidate, 0) == 0).findFirst()
                    .orElseGet(() -> candidates.stream().findFirst().orElse("final_answer"));
            SINGLE_AGENT_DIAGNOSTICS.add("scene=" + scene + "; candidates=" + candidates
                    + "; observed=" + observedCounts
                    + "; revision=" + revisionRequested + "; selected=" + selected);
            return selected;
        }

        private static List<String> preferredTools(String scene) {
            return switch (scene) {
                case "campaign" -> List.of("load_campaign_constraints", "search_products",
                        "check_fulfillment", "check_inventory", "rerank", "generate_localized_copy");
                case "retention" -> List.of("get_user_profile", "get_recent_orders", "search_products",
                        "check_inventory", "rerank", "generate_retention_copy");
                default -> List.of("get_user_profile", "search_products", "check_inventory", "rerank");
            };
        }

        private static List<String> productIds(String prompt) {
            Matcher matcher = Pattern.compile("(?m)^ID:([^ ]+)").matcher(prompt);
            java.util.LinkedHashSet<String> ids = new java.util.LinkedHashSet<>();
            while (matcher.find()) ids.add(matcher.group(1));
            return List.copyOf(ids);
        }

        private static String jsonProductIds(String prompt) throws Exception {
            return new ObjectMapper().writeValueAsString(Map.of("productIds", productIds(prompt)));
        }

        private static String jsonCopies(String prompt) throws Exception {
            List<Map<String, String>> copies = productIds(prompt).stream()
                    .map(id -> Map.of("product_id", id, "copy", "Fixture copy grounded in the selected product."))
                    .toList();
            return new ObjectMapper().writeValueAsString(copies);
        }
    }
}
