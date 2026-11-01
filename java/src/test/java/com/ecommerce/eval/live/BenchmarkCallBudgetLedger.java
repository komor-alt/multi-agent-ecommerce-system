package com.ecommerce.eval.live;

import com.ecommerce.service.LlmCallBudget;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

/** Durable benchmark-only identity and provider-call ledger. */
final class BenchmarkCallBudgetLedger {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String namespace;
    private final String revision;
    private final String configHash;
    private final int caseCount;
    private final int armCount;
    private final int perCaseBudget;
    private final int totalBudget;

    BenchmarkCallBudgetLedger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                              Environment environment) {
        this(jdbc, transactionManager,
                required(environment, "agent.benchmark.namespace"),
                required(environment, "agent.benchmark.revision"),
                required(environment, "agent.benchmark.config-hash"),
                positive(environment, "agent.benchmark.case-count"),
                positive(environment, "agent.benchmark.arm-count"),
                positive(environment, "agent.benchmark.per-case-budget"),
                positive(environment, "agent.benchmark.total-budget"));
    }

    BenchmarkCallBudgetLedger(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
                              String namespace, String revision, String configHash,
                              int caseCount, int armCount, int perCaseBudget, int totalBudget) {
        this.jdbc = jdbc;
        this.transactions = new TransactionTemplate(transactionManager);
        this.namespace = namespace;
        this.revision = revision;
        this.configHash = configHash;
        this.caseCount = caseCount;
        this.armCount = armCount;
        this.perCaseBudget = perCaseBudget;
        this.totalBudget = totalBudget;
        if ((long) caseCount * armCount * perCaseBudget != totalBudget) {
            throw fail("total budget does not equal cases * arms * per-case budget");
        }
    }

    void registerRun(String runId, String arm, String caseId, String topology) {
        requireNamespaced(runId);
        transactions.executeWithoutResult(status -> {
            Session session = lockSession();
            requireIdentity(session);
            Integer existing = jdbc.queryForObject("select count(*) from benchmark_run_manifests where run_id=?",
                    Integer.class, runId);
            if (existing != null && existing > 0) throw fail("duplicate benchmark Run registration");
            if (count("select count(*) from benchmark_run_manifests where namespace=? and arm=?",
                    namespace, arm) >= caseCount) {
                throw fail("benchmark arm already registered its complete case set");
            }
            jdbc.update("insert into benchmark_run_manifests(run_id,namespace,arm,case_id,topology,revision,"
                            + "config_hash,status) values (?,?,?,?,?,?,?,'RUNNING')",
                    runId, namespace, arm, caseId, topology, revision, configHash);
        });
    }

    LlmCallBudget.Reservation reserve(String runId, String phase, String fingerprint) {
        requireNamespaced(runId);
        try {
            return transactions.execute(status -> {
                Session session = lockSession();
                requireIdentity(session);
                RunManifest run = lockRun(runId);
                String digest = digest((phase == null ? "unknown" : phase) + ":"
                        + (fingerprint == null ? "" : fingerprint));
                Integer duplicate = jdbc.queryForObject("select count(*) from benchmark_provider_call_claims "
                        + "where run_id=? and fingerprint_hash=?", Integer.class, runId, digest);
                if (duplicate != null && duplicate > 0) {
                    return new LlmCallBudget.Reservation(false, "duplicate_call_blocked");
                }
                if (count("select count(*) from benchmark_provider_call_claims where run_id=?", runId)
                        >= perCaseBudget) {
                    return exhausted();
                }
                long armLimit = (long) caseCount * perCaseBudget;
                if (count("select count(*) from benchmark_provider_call_claims where namespace=? and arm=?",
                        namespace, run.arm()) >= armLimit) {
                    return exhausted();
                }
                if (count("select count(*) from benchmark_provider_call_claims where namespace=?", namespace)
                        >= totalBudget) {
                    return exhausted();
                }
                try {
                    jdbc.update("insert into benchmark_provider_call_claims(namespace,run_id,arm,fingerprint_hash) "
                            + "values (?,?,?,?)", namespace, runId, run.arm(), digest);
                } catch (DataIntegrityViolationException duplicateInsert) {
                    return new LlmCallBudget.Reservation(false, "duplicate_call_blocked");
                }
                return new LlmCallBudget.Reservation(true, null);
            });
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw fail("provider budget reservation failed closed", failure);
        }
    }

    void verifyRunIdentity(String runId, String topology) {
        requireNamespaced(runId);
        transactions.executeWithoutResult(transaction -> {
            Session session = lockSession();
            requireIdentity(session);
            RunManifest run = lockRun(runId);
            if (!run.topology().equalsIgnoreCase(topology)) {
                throw fail("benchmark Run topology mismatch");
            }
        });
    }

    void markTerminal(String runId, String status) {
        requireNamespaced(runId);
        transactions.executeWithoutResult(transaction -> {
            Session session = lockSession();
            requireIdentity(session);
            int updated = jdbc.update("update benchmark_run_manifests set status=?, completed_at=current_timestamp "
                    + "where run_id=? and namespace=? and status='RUNNING'", safeTerminal(status), runId, namespace);
            if (updated != 1) throw fail("benchmark Run was not RUNNING at terminal transition");
        });
    }

    void abortRun(String runId, String reason) {
        requireNamespaced(runId);
        transactions.executeWithoutResult(transaction -> jdbc.update(
                "update benchmark_run_manifests set status='ABORTED', completed_at=current_timestamp "
                        + "where run_id=? and namespace=? and status='RUNNING'", runId, namespace));
    }

    void verifyComplete(int expectedRuns) {
        long total = count("select count(*) from benchmark_run_manifests where namespace=?", namespace);
        long running = count("select count(*) from benchmark_run_manifests where namespace=? and status='RUNNING'",
                namespace);
        long calls = count("select count(*) from benchmark_provider_call_claims where namespace=?", namespace);
        if (total != expectedRuns || running != 0 || calls > totalBudget) {
            throw fail("benchmark manifest is incomplete or exceeds the aggregate budget");
        }
    }

    Map<String, Object> snapshot() {
        return Map.of(
                "namespace", namespace,
                "revision", revision,
                "configHash", configHash,
                "runCount", count("select count(*) from benchmark_run_manifests where namespace=?", namespace),
                "providerCallClaims", count(
                        "select count(*) from benchmark_provider_call_claims where namespace=?", namespace),
                "totalBudget", totalBudget);
    }

    private Session lockSession() {
        return jdbc.query("select revision,config_hash,status,case_count,arm_count,per_case_budget,total_budget "
                        + "from benchmark_execution_sessions where namespace=? for update",
                rows -> rows.next() ? new Session(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getInt(4), rows.getInt(5), rows.getInt(6), rows.getInt(7)) : null, namespace);
    }

    private RunManifest lockRun(String runId) {
        RunManifest run = jdbc.query("select arm,topology,revision,config_hash,status from benchmark_run_manifests "
                        + "where run_id=? and namespace=? for update",
                rows -> rows.next() ? new RunManifest(rows.getString(1), rows.getString(2), rows.getString(3),
                        rows.getString(4), rows.getString(5)) : null, runId, namespace);
        if (run == null) throw fail("benchmark Run is not registered");
        if (!revision.equals(run.revision()) || !configHash.equals(run.configHash())) {
            throw fail("benchmark Run revision/config identity mismatch");
        }
        if (!"RUNNING".equals(run.status())) throw fail("benchmark Run is not active");
        return run;
    }

    private void requireIdentity(Session session) {
        if (session == null || !revision.equals(session.revision()) || !configHash.equals(session.configHash())
                || !"RUNNING".equals(session.status()) || session.caseCount() != caseCount
                || session.armCount() != armCount || session.perCaseBudget() != perCaseBudget
                || session.totalBudget() != totalBudget) {
            throw fail("benchmark session commit/config/budget identity mismatch");
        }
    }

    private long count(String sql, Object... arguments) {
        Long value = jdbc.queryForObject(sql, Long.class, arguments);
        return value == null ? 0L : value;
    }

    private void requireNamespaced(String runId) {
        if (runId == null || !runId.startsWith(namespace + "__")) {
            throw fail("Run ID is outside the benchmark namespace");
        }
    }

    private static String safeTerminal(String status) {
        return switch (status == null ? "" : status.toUpperCase()) {
            case "COMPLETED", "FAILED", "BLOCKED", "ABORTED" -> status.toUpperCase();
            default -> throw fail("unsupported benchmark terminal status");
        };
    }

    private static LlmCallBudget.Reservation exhausted() {
        return new LlmCallBudget.Reservation(false, "llm_budget_exhausted");
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    private static String required(Environment environment, String key) {
        String value = environment.getProperty(key);
        if (value == null || value.isBlank()) throw fail("missing " + key);
        return value;
    }

    private static int positive(Environment environment, String key) {
        try {
            int value = Integer.parseInt(required(environment, key));
            if (value > 0) return value;
        } catch (NumberFormatException ignored) { }
        throw fail(key + " must be positive");
    }

    private static IllegalStateException fail(String reason) {
        return new IllegalStateException("LIVE_BENCHMARK_LEDGER: " + reason);
    }

    private static IllegalStateException fail(String reason, Throwable cause) {
        return new IllegalStateException("LIVE_BENCHMARK_LEDGER: " + reason, cause);
    }

    private record Session(String revision, String configHash, String status, int caseCount,
                           int armCount, int perCaseBudget, int totalBudget) { }
    private record RunManifest(String arm, String topology, String revision, String configHash, String status) { }
}
