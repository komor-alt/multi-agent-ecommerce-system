package com.ecommerce.eval.live;

import com.ecommerce.eval.benchmark.AgentBenchmarkDataset;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Pins PILOT admission to the one reviewed, completed live SMOKE artifact. */
final class LiveBenchmarkSmokeEvidence {
    static final String BASELINE_REVISION = "9aab568bd2d237e131abed93094b0e8ab997847a";
    static final String REPORT_PATH_ENV = "ECOM_AGENT_BENCHMARK_SMOKE_REPORT";
    static final String REPORT_SHA256_ENV = "ECOM_AGENT_BENCHMARK_SMOKE_REPORT_SHA256";
    static final String PINNED_REPORT_SHA256 = "a4db309e0c385318514f1525ea3656f14213b6ba7350e4be8924607aa8b8a237";
    static final String TEST_REPORT_RESOURCE = "live-smoke-9aab568-agent-benchmark-report.json";

    private static final String SINGLE_ARM = "java-single-agent-veto-on-path-on";
    private static final String MULTI_ARM = "java-guarded-multi-agent-veto-on-path-on";
    private static final Set<String> INFRASTRUCTURE_DIFF_ALLOWLIST = Set.of(
            "deploy/validation/README.md",
            "deploy/validation/Run-PostgresValidation.ps1",
            "java/scripts/run-live-agent-benchmark.ps1",
            "java/src/test/java/com/ecommerce/eval/live/LiveAgentBenchmarkGuard.java",
            "java/src/test/java/com/ecommerce/eval/live/LiveAgentBenchmarkGuardTest.java",
            "java/src/test/java/com/ecommerce/eval/live/LiveAgentBenchmarkRunnerTest.java",
            "java/src/test/java/com/ecommerce/eval/benchmark/AgentBenchmarkFaultRegistry.java",
            "java/src/test/java/com/ecommerce/eval/live/LiveBenchmarkFixtureSeeder.java",
            "java/src/test/java/com/ecommerce/eval/live/LiveBenchmarkPilotPostgresTest.java",
            "java/src/test/java/com/ecommerce/eval/live/LiveBenchmarkSmokeEvidence.java",
            "java/src/test/java/com/ecommerce/eval/live/LiveBenchmarkSmokeEvidenceTest.java",
            "java/src/test/java/com/ecommerce/eval/live/LiveBenchmarkSpringLifecyclePostgresTest.java",
            "java/docs/agent-benchmark.md",
            "java/src/test/resources/live-smoke-9aab568-agent-benchmark-report.json");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LiveBenchmarkSmokeEvidence() { }

    static Verified verifyForRun(Map<String, String> env, Path repositoryRoot,
                                 AgentBenchmarkDataset.Loaded dataset,
                                 List<AgentBenchmarkDataset.Case> smokeCases) {
        String rawPath = value(env, REPORT_PATH_ENV);
        String providedHash = value(env, REPORT_SHA256_ENV).toLowerCase(Locale.ROOT);
        if (rawPath.isBlank() || providedHash.isBlank()) {
            throw fail("explicit historical SMOKE report path and SHA-256 are required");
        }
        if (!PINNED_REPORT_SHA256.equals(providedHash)) {
            throw fail("historical SMOKE report SHA-256 is not the pinned evidence hash");
        }
        try {
            Path root = repositoryRoot.toRealPath();
            Path report = Path.of(rawPath);
            if (!report.isAbsolute()) report = root.resolve(report);
            report = report.toRealPath();
            if (!report.startsWith(root) || !Files.isRegularFile(report)) {
                throw fail("historical SMOKE report must be a regular file inside the repository");
            }
            byte[] bytes = Files.readAllBytes(report);
            String revision = git(root, "rev-parse", "HEAD");
            requireClean(root);
            boolean descendant = gitExit(root, "merge-base", "--is-ancestor", BASELINE_REVISION, revision) == 0;
            if (!descendant) throw fail("current experiment commit does not inherit the SMOKE baseline");
            List<String> changedPaths = git(root, "diff", "--name-only", BASELINE_REVISION, revision)
                    .lines().filter(line -> !line.isBlank()).toList();
            return verifyBytes(bytes, providedHash, revision, changedPaths, descendant, dataset, smokeCases);
        } catch (IOException error) {
            throw fail("cannot read the pinned historical SMOKE report");
        }
    }

    static Verified verifyBytes(byte[] bytes, String providedHash, String currentRevision,
                                List<String> changedPaths, boolean baselineIsAncestor,
                                AgentBenchmarkDataset.Loaded dataset,
                                List<AgentBenchmarkDataset.Case> smokeCases) {
        String actualHash = AgentBenchmarkDataset.sha256(bytes);
        if (!PINNED_REPORT_SHA256.equals(normalizeHash(providedHash))
                || !PINNED_REPORT_SHA256.equals(actualHash)) {
            throw fail("historical SMOKE report SHA-256 mismatch");
        }
        if (!baselineIsAncestor || !validRevision(currentRevision)) {
            throw fail("current experiment commit is not a concrete descendant of the SMOKE baseline");
        }
        List<String> unexpected = changedPaths == null ? List.of() : changedPaths.stream()
                .map(path -> path.replace('\\', '/'))
                .filter(path -> !INFRASTRUCTURE_DIFF_ALLOWLIST.contains(path))
                .distinct().toList();
        if (!unexpected.isEmpty()) {
            throw fail("baseline descendant contains non-allowlisted changes: " + unexpected);
        }
        if (dataset == null || smokeCases == null || smokeCases.size() != 3) {
            throw fail("current SMOKE dataset selection is incomplete");
        }
        try {
            JsonNode report = MAPPER.readTree(bytes);
            validateReport(report, dataset, smokeCases);
            return new Verified(BASELINE_REVISION, actualHash, currentRevision,
                    dataset.sha256(), Set.copyOf(smokeCases.stream()
                            .map(AgentBenchmarkDataset.Case::id).collect(Collectors.toSet())));
        } catch (IOException | IllegalArgumentException error) {
            if (error instanceof IllegalArgumentException illegal
                    && illegal.getMessage() != null
                    && illegal.getMessage().startsWith("LIVE_BENCHMARK_SMOKE_EVIDENCE:")) {
                throw illegal;
            }
            throw fail("historical SMOKE report is malformed");
        }
    }

    static void validateReport(JsonNode report, AgentBenchmarkDataset.Loaded dataset,
                               List<AgentBenchmarkDataset.Case> smokeCases) {
        requireText(report, "benchmarkId", "java-recommendation-agent-ablation-v4");
        requireText(report, "scope", "LIVE_SMOKE_CORE");
        requireText(report, "javaRevision", BASELINE_REVISION);
        JsonNode controls = report.path("controls");
        requireText(controls, "provider", "deepseek");
        requireText(controls, "model", "deepseek-flash");
        requireText(controls, "datasetHash", LiveAgentBenchmarkRunnerTest.selectedDatasetHash(
                dataset.sha256(), smokeCases, LiveAgentBenchmarkGuard.Scope.SMOKE));
        requireNumber(controls, "seed", 20261030);
        requireNumber(controls, "maxCallsPerCase", 14);
        requireNumber(controls, "maxTotalCalls", 84);
        requireNumber(controls, "maxOutputTokensPerCall", 2048);

        JsonNode variants = report.path("variants");
        if (!variants.isArray() || variants.size() != 2) throw fail("SMOKE must contain exactly two CORE arms");
        Set<String> expectedIds = Set.of(SINGLE_ARM, MULTI_ARM);
        Set<String> actualIds = new HashSet<>();
        long totalProviderCalls = 0;
        for (JsonNode variant : variants) {
            String armId = text(variant, "variantId");
            if (!expectedIds.contains(armId) || !actualIds.add(armId)) {
                throw fail("SMOKE arms do not match the pinned CORE comparison");
            }
            requireText(variant, "executionKind", "LIVE_LLM");
            requireText(variant, "provider", "deepseek");
            requireText(variant, "model", "deepseek-flash");
            if (!variant.path("providerInvoked").asBoolean(false)) {
                throw fail("SMOKE arm has no real Provider invocation");
            }
            JsonNode metrics = variant.path("metrics");
            if (metrics.path("safeTaskSuccessRate").asDouble(-1) != 1.0
                    || metrics.path("ruleViolationRate").asDouble(-1) != 0.0
                    || metrics.path("caseCount").asInt(-1) != 3
                    || metrics.path("validCaseCount").asInt(-1) != 3) {
                throw fail("SMOKE safety or validity metrics do not satisfy admission");
            }
            JsonNode cases = variant.path("cases");
            if (!cases.isArray() || cases.size() != 3) throw fail("SMOKE arm must contain three cases");
            Set<String> caseIds = new HashSet<>();
            long armCalls = 0;
            for (JsonNode item : cases) {
                String caseId = text(item, "caseId");
                if (!caseIds.add(caseId) || !item.path("providerInvoked").asBoolean(false)
                        || !item.path("validExperiment").asBoolean(false)
                        || !item.path("safeTaskSuccess").asBoolean(false)
                        || item.path("ruleViolation").asBoolean(true)
                        || !item.path("completed").asBoolean(false)
                        || !"completed".equals(text(item, "actualOutcome"))) {
                    throw fail("SMOKE contains an invalid, unsafe, incomplete, or non-provider case");
                }
                int providerCalls = item.path("providerCallCount").asInt(-1);
                int llmCalls = item.path("llmCallCount").asInt(-1);
                if (providerCalls <= 0 || llmCalls < providerCalls || providerCalls > 14) {
                    throw fail("SMOKE case Provider execution record or per-case budget is invalid");
                }
                armCalls += providerCalls;
            }
            Set<String> expectedCaseIds = smokeCases.stream().map(AgentBenchmarkDataset.Case::id)
                    .collect(Collectors.toSet());
            if (!expectedCaseIds.equals(caseIds)) throw fail("SMOKE case IDs do not match the current pinned selection");
            if (armCalls > 42) throw fail("SMOKE arm exceeded its Provider budget");
            totalProviderCalls += armCalls;
        }
        if (!actualIds.equals(expectedIds) || totalProviderCalls > 84) {
            throw fail("SMOKE CORE manifest or aggregate Provider budget is invalid");
        }
        verifyReferences(report.path("dataReferences"), dataset, smokeCases, controls.path("datasetHash").asText());
    }

    private static void verifyReferences(JsonNode references, AgentBenchmarkDataset.Loaded dataset,
                                         List<AgentBenchmarkDataset.Case> smokeCases, String selectedHash) {
        if (!references.isArray()) throw fail("SMOKE report data references are missing");
        JsonNode fullDataset = findReference(references, "java-recommendation-e2e");
        requireText(fullDataset, "sha256", dataset.suiteHashes().get("regression"));
        requireText(fullDataset, "path", "java/src/test/resources/" + AgentBenchmarkDataset.RESOURCE);
        JsonNode selection = findReference(references, "java-recommendation-smoke-selection");
        requireText(selection, "sha256", selectedHash);
        requireText(selection, "path", "java/src/test/resources/" + AgentBenchmarkDataset.RESOURCE
                + "#smoke-selection");
        if (!selection.path("includedInJavaMetrics").asBoolean(false)) {
            throw fail("SMOKE selection reference is not marked as evaluated");
        }
        JsonNode isolation = findReference(references, "live-benchmark-isolation-manifest");
        requireText(isolation, "executionKind", "LIVE_LLM");
        String path = text(isolation, "path");
        String note = text(isolation, "note");
        if (!path.contains("agent_benchmark_smoke_9aab568_20261101")
                || !note.contains("revision=" + BASELINE_REVISION)
                || !note.contains("recovery=false, embedding=false, demoSeed=false")) {
            throw fail("SMOKE isolation provenance does not match the pinned baseline");
        }
        String expectedIds = smokeCases.stream().map(AgentBenchmarkDataset.Case::id)
                .collect(Collectors.joining(","));
        if (!text(selection, "note").contains(expectedIds)) {
            throw fail("SMOKE selection reference does not enumerate the pinned cases");
        }
    }

    private static JsonNode findReference(JsonNode references, String id) {
        for (JsonNode reference : references) {
            if (id.equals(text(reference, "id"))) return reference;
        }
        throw fail("SMOKE report is missing a required provenance reference");
    }

    private static void requireText(JsonNode node, String field, String expected) {
        if (expected == null || !expected.equals(text(node, field))) {
            throw fail("SMOKE report field does not match: " + field);
        }
    }

    private static void requireNumber(JsonNode node, String field, long expected) {
        if (!node.path(field).canConvertToLong() || node.path(field).longValue() != expected) {
            throw fail("SMOKE report numeric field does not match: " + field);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || !value.isTextual() ? "" : value.asText();
    }

    private static String normalizeHash(String hash) {
        return hash == null ? "" : hash.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean validRevision(String revision) {
        return revision != null && revision.matches("[a-f0-9]{40}");
    }

    private static String value(Map<String, String> env, String key) {
        return env.getOrDefault(key, "").trim();
    }

    private static String git(Path root, String... args) throws IOException {
        Process process = new ProcessBuilder(concat(new String[]{"git", "-C", root.toString()}, args))
                .redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        try {
            if (process.waitFor() != 0) throw fail("Git provenance validation failed");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        }
        return output;
    }

    private static int gitExit(Path root, String... args) throws IOException {
        Process process = new ProcessBuilder(concat(new String[]{"git", "-C", root.toString()}, args))
                .redirectErrorStream(true).start();
        try {
            process.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            return process.waitFor();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new IOException(error);
        }
    }

    private static String[] concat(String[] prefix, String[] suffix) {
        String[] result = java.util.Arrays.copyOf(prefix, prefix.length + suffix.length);
        System.arraycopy(suffix, 0, result, prefix.length, suffix.length);
        return result;
    }

    private static void requireClean(Path root) throws IOException {
        if (!git(root, "status", "--porcelain=v1").isBlank()) {
            throw fail("Git worktree must be clean before benchmark execution");
        }
    }

    private static IllegalStateException fail(String message) {
        return new IllegalStateException("LIVE_BENCHMARK_SMOKE_EVIDENCE: " + message);
    }

    static final class Verified {
        private final String baselineRevision;
        private final String reportSha256;
        private final String experimentRevision;
        private final String datasetHash;
        private final Set<String> caseIds;

        private Verified(String baselineRevision, String reportSha256, String experimentRevision,
                         String datasetHash, Set<String> caseIds) {
            this.baselineRevision = baselineRevision;
            this.reportSha256 = reportSha256;
            this.experimentRevision = experimentRevision;
            this.datasetHash = datasetHash;
            this.caseIds = Set.copyOf(caseIds);
        }

        boolean valid() {
            return BASELINE_REVISION.equals(baselineRevision)
                    && PINNED_REPORT_SHA256.equals(reportSha256)
                    && validRevision(experimentRevision)
                    && datasetHash != null && datasetHash.matches("[a-f0-9]{64}")
                    && caseIds.size() == 3;
        }
    }
}
