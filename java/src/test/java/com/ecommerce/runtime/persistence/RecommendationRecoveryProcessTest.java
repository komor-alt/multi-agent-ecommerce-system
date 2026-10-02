package com.ecommerce.runtime.persistence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** Real OS-process death and PostgreSQL takeover, with production Store/EventService implementations. */
@EnabledIfEnvironmentVariable(named = "ECOM_RUN_POSTGRES_TESTS", matches = "true")
class RecommendationRecoveryProcessTest {
    @Test
    @Timeout(value = 120, unit = TimeUnit.SECONDS)
    void secondJvmTakesOverAfterFirstJvmIsKilledWithoutLosingPreviousTrace() throws Exception {
        String schema = "rec_process_" + UUID.randomUUID().toString().replace("-", "");
        String runId = "process-" + UUID.randomUUID();
        Worker victim = null;
        Worker survivor = null;
        try {
            migrate(schema);
            // Boot B before starting the lease clock in A; B waits on stdin before attempting a claim.
            survivor = Worker.start("B", schema, runId);
            survivor.awaitMarker("FIXTURE_B_BOOTED", Duration.ofSeconds(35));
            victim = Worker.start("A", schema, runId);
            victim.awaitMarker("FIXTURE_A_READY", Duration.ofSeconds(35));
            assertThat(count(schema, "select count(*) from recommendation_agent_runs where id=? "
                    + "and execution_attempt=1 and status='RUNNING' and execution_lease_until>clock_timestamp()", runId))
                    .as("A committed while its database lease was still valid").isEqualTo(1);
            long victimPid = victim.process.pid();
            long survivorPid = survivor.process.pid();
            assertThat(victimPid).isNotEqualTo(survivorPid).isNotEqualTo(ProcessHandle.current().pid());
            assertThat(survivorPid).isNotEqualTo(ProcessHandle.current().pid());

            victim.process.destroyForcibly();
            assertThat(victim.process.waitFor(10, TimeUnit.SECONDS)).as("Victim process exited after forced kill").isTrue();
            assertThat(victim.process.exitValue()).isNotZero();
            survivor.process.getOutputStream().write("TAKEOVER\n".getBytes(StandardCharsets.UTF_8));
            survivor.process.getOutputStream().flush();
            survivor.awaitMarker("FIXTURE_B_COMPLETED", Duration.ofSeconds(35));
            assertThat(survivor.process.waitFor(10, TimeUnit.SECONDS)).as(survivor.output()).isTrue();
            assertThat(survivor.process.exitValue()).as(survivor.output()).isZero();

            assertThat(count(schema, "select count(*) from recommendation_agent_runs where id=? "
                    + "and execution_attempt=2 and execution_owner='process-worker-b' "
                    + "and status='COMPLETED' and completed_at is not null", runId)).isEqualTo(1);
            assertThat(count(schema, "select count(*) from recommendation_agent_tasks where run_id=? "
                    + "and id like '%:attempt:1:%' and status='COMPLETED'", runId)).isEqualTo(1);
            assertThat(count(schema, "select count(*) from recommendation_agent_tasks where run_id=? "
                    + "and id like '%:attempt:1:%' and status='CANCELLED' "
                    + "and stop_reason='execution_attempt_recovered'", runId)).isEqualTo(1);
            assertThat(count(schema, "select count(*) from recommendation_agent_tasks where run_id=? "
                    + "and id like '%:attempt:2:%' and status='COMPLETED'", runId)).isEqualTo(1);
            assertThat(count(schema, "select count(*) from recommendation_agent_artifacts where run_id=? "
                    + "and data_json::jsonb->>'worker'='worker-a'", runId)).isEqualTo(1);
            assertThat(count(schema, "select count(*) from recommendation_agent_artifacts where run_id=? "
                    + "and data_json::jsonb->>'worker'='worker-b'", runId)).isEqualTo(1);
            assertThat(count(schema, "select count(*) from recommendation_run_events where run_id=? "
                    + "and name='run.attempt_started'", runId)).isEqualTo(2);
            assertThat(count(schema, "select count(*) from recommendation_run_events where run_id=? "
                    + "and name in ('run.completed','run.failed','run.cancelled','run.blocked')", runId)).isEqualTo(1);
            assertThat(count(schema, "select count(*) from recommendation_outbox where aggregate_id=? "
                    + "and aggregate_type='RUN' and event_type='RUN_STATUS_CHANGED'", runId)).isEqualTo(1);
            assertOrderedHistory(schema, runId);
        } finally {
            try {
                if (victim != null) victim.close();
            } finally {
                try {
                    if (survivor != null) survivor.close();
                } finally {
                    drop(schema);
                }
            }
        }
    }

    private static void assertOrderedHistory(String schema, String runId) throws Exception {
        try (Connection connection = connection(schema);
             PreparedStatement query = connection.prepareStatement(
                     "select sequence,name from recommendation_run_events where run_id=? order by sequence")) {
            query.setString(1, runId);
            try (var rows = query.executeQuery()) {
                List<String> names = new ArrayList<>();
                int sequence = 0;
                while (rows.next()) {
                    assertThat(rows.getInt(1)).isEqualTo(++sequence);
                    names.add(rows.getString(2));
                }
                assertThat(names).containsExactly("run.attempt_started", "fixture.a.committed",
                        "run.attempt_started", "fixture.b.recovered", "run.completed");
            }
        }
    }

    private static long count(String schema, String sql, String runId) throws Exception {
        try (Connection connection = connection(schema); PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, runId);
            try (var rows = query.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    private static void migrate(String schema) throws Exception {
        try (Connection connection = connection(null); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            connection.setSchema(schema);
            ScriptUtils.executeSqlScript(connection,
                    new ClassPathResource("migration-recommendation-runtime-postgresql.sql"));
        }
    }

    private static void drop(String schema) throws Exception {
        if (!schema.matches("rec_process_[a-f0-9]{32}")) throw new IllegalArgumentException("Unexpected schema");
        try (Connection connection = connection(null); var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private static Connection connection(String schema) throws Exception {
        String url = required("ECOM_POSTGRES_TEST_URL");
        if (!url.startsWith("jdbc:postgresql:") || url.toLowerCase(java.util.Locale.ROOT).contains("currentschema=")) {
            throw new IllegalArgumentException("Use a PostgreSQL test URL without currentSchema");
        }
        Connection connection = DriverManager.getConnection(url, required("ECOM_POSTGRES_TEST_USER"),
                required("ECOM_POSTGRES_TEST_PASSWORD"));
        if (schema != null) connection.setSchema(schema);
        return connection;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing " + name);
        return value;
    }

    private static final class Worker implements AutoCloseable {
        private final Process process;
        private final Path classPathJar;
        private final LinkedBlockingQueue<String> lines = new LinkedBlockingQueue<>();
        private final StringBuilder output = new StringBuilder();
        private final Thread reader;

        private Worker(Process process, Path classPathJar) {
            this.process = process;
            this.classPathJar = classPathJar;
            reader = new Thread(() -> {
                try (var stream = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = stream.readLine()) != null) {
                        synchronized (output) {
                            output.append(line).append('\n');
                            if (output.length() > 24_000) output.delete(0, output.length() - 24_000);
                        }
                        if (line.startsWith("FIXTURE_")) lines.offer(line);
                    }
                } catch (Exception error) {
                    synchronized (output) { output.append(error.getClass().getSimpleName()); }
                }
            }, "recovery-test-child-output-" + process.pid());
            reader.setDaemon(true);
            reader.start();
        }

        static Worker start(String role, String schema, String runId) throws Exception {
            String executable = System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")
                    ? "java.exe" : "java";
            String java = Path.of(System.getProperty("java.home"), "bin", executable).toString();
            String classPath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
            // A manifest classpath avoids both Windows' command-line length limit and Java 17's
            // platform-dependent argument-file decoding. ASCII file URIs preserve Chinese paths.
            Path classPathJar = Files.createTempFile("ecom-recovery-classpath-", ".jar");
            try {
                var manifest = new Manifest();
                manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
                manifest.getMainAttributes().put(Attributes.Name.CLASS_PATH,
                        Arrays.stream(classPath.split(Pattern.quote(File.pathSeparator)))
                                .filter(entry -> !entry.isBlank())
                                .map(entry -> Path.of(entry).toAbsolutePath().toUri().toASCIIString())
                                .collect(Collectors.joining(" ")));
                try (var ignored = new JarOutputStream(Files.newOutputStream(classPathJar), manifest)) {
                    // Manifest-only classpath bridge: application/test classes are not copied.
                }
                var builder = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-cp", classPathJar.toString(),
                        RecommendationRecoveryProcessFixture.class.getName(), role, schema, runId);
                builder.redirectErrorStream(true);
                return new Worker(builder.start(), classPathJar);
            } catch (Exception error) {
                Files.deleteIfExists(classPathJar);
                throw error;
            }
        }

        void awaitMarker(String expected, Duration timeout) throws Exception {
            long deadline = System.nanoTime() + timeout.toNanos();
            while (System.nanoTime() < deadline) {
                String line = lines.poll(100, TimeUnit.MILLISECONDS);
                if (expected.equals(line)) return;
                if (!process.isAlive() && lines.isEmpty()) {
                    reader.join(1000);
                    if (expected.equals(lines.poll())) return;
                    break;
                }
            }
            throw new AssertionError("Child did not emit " + expected + "\n" + output());
        }

        String output() {
            synchronized (output) { return output.toString(); }
        }

        @Override
        public void close() throws Exception {
            try {
                if (process.isAlive()) {
                    process.destroyForcibly();
                    if (!process.waitFor(10, TimeUnit.SECONDS)) throw new IllegalStateException("Child process did not exit");
                }
                reader.join(1000);
            } finally {
                Files.deleteIfExists(classPathJar);
            }
        }
    }
}
