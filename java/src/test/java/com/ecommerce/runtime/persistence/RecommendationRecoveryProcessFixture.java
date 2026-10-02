package com.ecommerce.runtime.persistence;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentRunEvent;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolLoopConfig;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.service.RecommendationPipelineState;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.persistence.EntityManagerFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import javax.sql.DataSource;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * Separate-process fixture for the production persistence/lease boundary, not an HTTP or LLM fixture.
 * It deliberately does not run the recovery scheduler: the parent controls the crash/takeover timing.
 */
public final class RecommendationRecoveryProcessFixture {
    private RecommendationRecoveryProcessFixture() { }

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !args[1].matches("rec_process_[a-f0-9]{32}")) {
            throw new IllegalArgumentException("Expected worker role, isolated schema and run id");
        }
        System.setProperty("ecom.process-test.schema", args[1]);
        String role = args[0];
        String runId = args[2];
        try (var context = new AnnotationConfigApplicationContext(FixtureConfiguration.class)) {
            var store = context.getBean(RecommendationRuntimeStore.class);
            var events = context.getBean(RecommendationRunEventService.class);
            if ("A".equals(role)) {
                prepareCrashVictim(store, events, runId);
                System.out.println("FIXTURE_A_READY");
                System.out.flush();
                new CountDownLatch(1).await(); // Must be killed; there is no graceful completion path.
            } else if ("B".equals(role)) {
                System.out.println("FIXTURE_B_BOOTED");
                System.out.flush();
                String command = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
                if (!"TAKEOVER".equals(command)) throw new IllegalStateException("Expected takeover signal");
                completeRecoveredAttempt(store, events, runId);
                System.out.println("FIXTURE_B_COMPLETED");
                System.out.flush();
            } else {
                throw new IllegalArgumentException("Unknown fixture worker role");
            }
        }
    }

    private static void prepareCrashVictim(RecommendationRuntimeStore store,
                                           RecommendationRunEventService events, String runId) {
        var request = ToolLoopRequest.builder().runId(runId)
                .request(RecommendationRequest.builder().userId("process-test-user").scene("homepage").build())
                .config(ToolLoopConfig.builder().toolWhitelist(
                        List.of("get_user_profile", "search_products", "final_answer")).build()).build();
        var lease = store.startAndClaimRecoverableRun(request, "process-worker-a");
        var runtime = new DynamicSubAgentRuntime(runId, lease.attempt());
        var state = new RecommendationPipelineState(runId, request.getRequest());
        finishProfile(runtime, state, "worker-a");
        var interruptedTask = runtime.spawn(new DynamicSubAgentRuntime.SubAgentSpec(
                "PRODUCT", AgentId.PRODUCT, "Task interrupted by process death",
                List.of("search_products"), 2, "search_products"), state);
        runtime.markRunning(interruptedTask);
        store.persist(runtime.runView(), lease, null);
        events.append(progress(runId, "fixture.a.committed"), lease);
        if (!store.renewLease(lease)) throw new IllegalStateException("Victim lease expired before readiness");
    }

    private static void completeRecoveredAttempt(RecommendationRuntimeStore store,
                                                 RecommendationRunEventService events, String runId)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        RecommendationExecutionLease lease = null;
        while (lease == null && System.nanoTime() < deadline) {
            lease = store.claimRun(runId, "process-worker-b").orElse(null);
            if (lease == null) Thread.sleep(50); // Bounded polling in test code only.
        }
        if (lease == null || lease.attempt() != 2) {
            throw new IllegalStateException("Expected second attempt after the dead worker's lease expires");
        }
        var runtime = new DynamicSubAgentRuntime(runId, lease.attempt());
        var state = new RecommendationPipelineState(runId, lease.request().getRequest());
        finishProfile(runtime, state, "worker-b");
        events.append(progress(runId, "fixture.b.recovered"), lease);
        runtime.finishRun("completed", "process_crash_recovered");
        store.persist(runtime.runView(), lease, AgentRunEvent.builder().requestId(runId)
                .type("run_completed").name("run.completed").status("completed")
                .summary("Recovered fixture execution completed").data(Map.of("attempt", lease.attempt())).build());
    }

    private static void finishProfile(DynamicSubAgentRuntime runtime, RecommendationPipelineState state, String worker) {
        var task = runtime.spawn(new DynamicSubAgentRuntime.SubAgentSpec(
                "PROFILE", AgentId.PROFILE, "Persist fixture profile evidence",
                List.of("get_user_profile"), 2, "get_user_profile"), state);
        runtime.markRunning(task);
        runtime.finish(task, true, "fixture_profile_completed", List.of(ToolObservation.builder()
                .toolName("get_user_profile").data(Map.of("worker", worker, "evidence", "preserved"))
                .evidenceIds(List.of(worker + "-evidence")).build()));
    }

    private static AgentRunEvent progress(String runId, String name) {
        return AgentRunEvent.builder().requestId(runId).name(name).type("fixture_progress")
                .status("running").summary("Separate JVM committed progress").data(Map.of()).build();
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableTransactionManagement
    @EnableJpaRepositories(basePackageClasses = RecommendationRunRepository.class)
    @Import({RecommendationRuntimeStore.class, RecommendationRunEventService.class})
    static class FixtureConfiguration {
        @Bean(destroyMethod = "close")
        HikariDataSource dataSource() {
            String url = required("ECOM_POSTGRES_TEST_URL");
            String schema = System.getProperty("ecom.process-test.schema");
            var source = new HikariDataSource();
            source.setJdbcUrl(url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
            source.setUsername(required("ECOM_POSTGRES_TEST_USER"));
            source.setPassword(required("ECOM_POSTGRES_TEST_PASSWORD"));
            source.setMaximumPoolSize(3);
            source.setMinimumIdle(1);
            source.setConnectionTimeout(5000);
            return source;
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource source) {
            var factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(source);
            factory.setPackagesToScan(RecommendationRunEntity.class.getPackageName());
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of("hibernate.hbm2ddl.auto", "none",
                    "hibernate.default_schema", System.getProperty("ecom.process-test.schema"),
                    "hibernate.physical_naming_strategy", "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    "hibernate.show_sql", "false"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory factory) {
            return new JpaTransactionManager(factory);
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper().findAndRegisterModules();
        }

        @Bean
        RecommendationRecoveryProperties recommendationRecoveryProperties() {
            var properties = new RecommendationRecoveryProperties();
            properties.setLeaseDuration(Duration.ofSeconds(6));
            properties.setHeartbeatInterval(Duration.ofSeconds(1));
            return properties;
        }
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
