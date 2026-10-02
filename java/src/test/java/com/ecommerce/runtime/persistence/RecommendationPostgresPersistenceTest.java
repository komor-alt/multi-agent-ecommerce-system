package com.ecommerce.runtime.persistence;

import com.ecommerce.model.AgentId;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.runtime.DynamicSubAgentRuntime;
import com.ecommerce.service.RecommendationPipelineState;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Opt in using ECOM_RUN_POSTGRES_TESTS=true, ECOM_POSTGRES_TEST_URL,
 * ECOM_POSTGRES_TEST_USER and ECOM_POSTGRES_TEST_PASSWORD. The migration and all
 * test writes are confined to a freshly-created random schema, dropped afterwards.
 * Reuses the exact lease/concurrency/rollback contract run against H2 in the default suite.
 */
@EnabledIfEnvironmentVariable(named = "ECOM_RUN_POSTGRES_TESTS", matches = "true")
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RecommendationRuntimeStore.class, RecommendationRuntimeIntegrityIntegrationTest.JacksonConfiguration.class})
class RecommendationPostgresPersistenceTest extends RecommendationOutboxContract {
    private static String schema;
    @Autowired RecommendationRuntimeStore store;
    @Autowired RecommendationRunEventRepository events;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String url = required("ECOM_POSTGRES_TEST_URL");
        if (!url.startsWith("jdbc:postgresql:") || url.toLowerCase().contains("currentschema=")) {
            throw new IllegalArgumentException("Use a PostgreSQL test URL without currentSchema");
        }
        schema = "rec_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("migration-recommendation-runtime-postgresql.sql"));
        } catch (Exception error) {
            try {
                dropIsolatedSchema();
            } catch (Exception cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
        registry.add("spring.datasource.url", () -> url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema);
        registry.add("spring.datasource.username", () -> required("ECOM_POSTGRES_TEST_USER"));
        registry.add("spring.datasource.password", () -> required("ECOM_POSTGRES_TEST_PASSWORD"));
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.properties.hibernate.default_schema", () -> schema);
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (schema == null || !schema.matches("rec_test_[a-f0-9]{32}")) return;
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
    }

    @Test
    void migrationAndHibernateRoundTripLargeUnicodeTextAcrossAllRuntimeTables() {
        String runId = UUID.randomUUID().toString();
        var request = RecommendationRequest.builder().userId("中文用户").scene("homepage").build();
        store.startRun(runId, request);
        var runtime = new DynamicSubAgentRuntime(runId);
        var task = runtime.spawn(new DynamicSubAgentRuntime.SubAgentSpec("PROFILE", AgentId.PROFILE,
                "读取画像".repeat(2000), List.of("load_profile"), 2, "load_profile"),
                new RecommendationPipelineState(runId, request));
        runtime.markRunning(task);
        String payload = "中文内容".repeat(2000);
        runtime.finish(task, true, "done", List.of(ToolObservation.builder().toolName("load_profile")
                .data(Map.of("longText", payload)).evidenceIds(List.of("evidence-1")).build()));
        runtime.finishRun("completed", "done");
        store.persist(runtime.runView());
        assertThat(store.findRunView(runId).orElseThrow().artifacts()).singleElement()
                .satisfies(artifact -> assertThat(artifact.data()).containsEntry("longText", payload));
        var event = RecommendationRunEventEntity.builder().id(UUID.randomUUID().toString()).runId(runId)
                .sequence(1).type("test").name("complete").status("ok").summary(payload)
                .dataJson("{\"text\":\"" + payload + "\"}").createdAt(Instant.now()).build();
        events.saveAndFlush(event);
        assertThat(events.findById(event.getId()).orElseThrow().getSummary()).isEqualTo(payload);
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(required("ECOM_POSTGRES_TEST_URL"),
                required("ECOM_POSTGRES_TEST_USER"), required("ECOM_POSTGRES_TEST_PASSWORD"));
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }
}
