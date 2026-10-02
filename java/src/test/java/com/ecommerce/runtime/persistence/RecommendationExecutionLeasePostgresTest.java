package com.ecommerce.runtime.persistence;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
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
import java.util.Locale;
import java.util.UUID;

/** Uses the same opt-in credentials as the outbox PostgreSQL contract, in a separate random schema. */
@EnabledIfEnvironmentVariable(named = "ECOM_RUN_POSTGRES_TESTS", matches = "true")
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=none")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({RecommendationRuntimeStore.class, RecommendationRunEventService.class,
        RecommendationRuntimeIntegrityIntegrationTest.JacksonConfiguration.class})
class RecommendationExecutionLeasePostgresTest extends RecommendationExecutionLeaseContract {
    private static String schema;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) throws Exception {
        String url = required("ECOM_POSTGRES_TEST_URL");
        if (!url.startsWith("jdbc:postgresql:") || url.toLowerCase(Locale.ROOT).contains("currentschema=")) {
            throw new IllegalArgumentException("Use a PostgreSQL test URL without currentSchema");
        }
        schema = "rec_lease_test_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("migration-recommendation-runtime-postgresql.sql"));
        } catch (Exception error) {
            try { dropIsolatedSchema(); } catch (Exception cleanupError) { error.addSuppressed(cleanupError); }
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
        if (schema == null || !schema.matches("rec_lease_test_[a-f0-9]{32}")) return;
        try (Connection connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA " + schema + " CASCADE");
        }
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
