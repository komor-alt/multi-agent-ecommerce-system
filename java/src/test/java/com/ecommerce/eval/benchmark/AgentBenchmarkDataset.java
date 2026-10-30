package com.ecommerce.eval.benchmark;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import java.util.Map;

/** Pinned Java-mainline cases plus explicit, non-aggregated provenance for prior evaluations. */
public final class AgentBenchmarkDataset {
    public static final String RESOURCE = "recommendation-agent-benchmark.jsonl";
    public static final String AFTER_SALES_OFFLINE_RESOURCE = "after-sales-eval.jsonl";
    private static final String TAU3_SUMMARY = "benchmarks/tau3-retail/published/retail-base-single-trial/summary.json";
    private static final String TAU3_METADATA = "benchmarks/tau3-retail/published/retail-base-single-trial/metadata.json";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private AgentBenchmarkDataset() {}

    public record Case(String id, String userId, String intentText, String expectedScene,
                       String country, String region, String locale, String currency, String platform,
                       int numItems, String category, String expectedOutcome,
                       String expectedFailureReason, List<String> expectedRequiredTools,
                       List<String> forbiddenTools, FaultPlan faultPlan, Map<String, Object> constraints) {}

    public record FaultPlan(String type, String tool, int triggerCount) {
        public boolean enabled() { return type != null && !"none".equals(type); }
    }

    public record Loaded(List<Case> cases, String sha256, String faultPlanHash,
                         List<AgentBenchmark.DataReference> references) {}

    public static Loaded load() {
        byte[] bytes = resourceBytes(RESOURCE);
        List<Case> cases = parse(new String(bytes, StandardCharsets.UTF_8));
        String hash = textSha256(bytes);
        List<AgentBenchmark.DataReference> refs = new ArrayList<>();
        refs.add(new AgentBenchmark.DataReference("java-recommendation-e2e", AgentBenchmark.ExecutionKind.LIVE_LLM,
                "java/src/test/resources/" + RESOURCE, hash, true,
                "Synthetic Java recommendation mainline dataset shared by separately labelled offline and live runs; variant executionKind governs metric provenance."));
        byte[] afterSales = resourceBytes(AFTER_SALES_OFFLINE_RESOURCE);
        refs.add(new AgentBenchmark.DataReference("java-after-sales-offline", AgentBenchmark.ExecutionKind.OFFLINE_SIMULATION,
                "java/src/test/resources/" + AFTER_SALES_OFFLINE_RESOURCE, textSha256(afterSales), false,
                "Existing injected-output/offline after-sales suite; provenance only, not included in recommendation live metrics."));
        Path root = repositoryRoot();
        String tau3Hash = textSha256(readRepositoryFile(root.resolve(TAU3_SUMMARY)));
        String tau3MetadataHash = textSha256(readRepositoryFile(root.resolve(TAU3_METADATA)));
        refs.add(new AgentBenchmark.DataReference("tau3-retail-python-published", AgentBenchmark.ExecutionKind.EXTERNAL_REFERENCE,
                TAU3_SUMMARY, tau3Hash, false,
                "Python τ³ Retail adapter published result. External reference only; never aggregated as Java performance."));
        refs.add(new AgentBenchmark.DataReference("tau3-retail-python-provenance", AgentBenchmark.ExecutionKind.EXTERNAL_REFERENCE,
                TAU3_METADATA, tau3MetadataHash, false,
                "Published Python adapter provenance metadata; external reference only."));
        return new Loaded(cases, hash, faultPlanHash(cases), List.copyOf(refs));
    }

    static List<Case> parse(String jsonl) {
        List<Case> result = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (String line : jsonl.split("\\R")) {
            if (line.isBlank()) continue;
            try {
                JsonNode node = MAPPER.readTree(line);
                Case item = new Case(required(node, "id"), required(node, "userId"), required(node, "intentText"),
                        required(node, "expectedScene"), required(node, "country"), required(node, "region"),
                        required(node, "locale"), required(node, "currency"), required(node, "platform"),
                        node.path("numItems").asInt(2), required(node, "category"),
                        required(node, "expectedOutcome"), optional(node, "expectedFailureReason"),
                        stringList(node, "expectedRequiredTools"), stringList(node, "forbiddenTools"),
                        faultPlan(node.path("faultPlan")), objectMap(node.path("constraints")));
                if (!ids.add(item.id())) throw new IllegalArgumentException("BENCHMARK_DUPLICATE_CASE_ID: " + item.id());
                if (!Set.of("homepage", "campaign", "retention").contains(item.expectedScene())) {
                    throw new IllegalArgumentException("BENCHMARK_INVALID_EXPECTED_SCENE: " + item.id());
                }
                if (item.numItems() < 1 || item.numItems() > 10) {
                    throw new IllegalArgumentException("BENCHMARK_INVALID_NUM_ITEMS: " + item.id());
                }
                if (!Set.of("normal", "constraint", "inventory", "failure").contains(item.category())) {
                    throw new IllegalArgumentException("BENCHMARK_INVALID_CATEGORY: " + item.id());
                }
                if (!Set.of("completed", "completed_empty", "failed", "blocked", "timeout")
                        .contains(item.expectedOutcome())) {
                    throw new IllegalArgumentException("BENCHMARK_INVALID_EXPECTED_OUTCOME: " + item.id());
                }
                if (!ScenePathTools.ALL.containsAll(item.expectedRequiredTools())
                        || !ScenePathTools.ALL.containsAll(item.forbiddenTools())) {
                    throw new IllegalArgumentException("BENCHMARK_UNKNOWN_EXPECTED_TOOL: " + item.id());
                }
                if (item.faultPlan().enabled()
                        && (!Set.of("tool_failure", "tool_timeout", "empty_result", "evidence_conflict",
                                "inventory_shortage").contains(item.faultPlan().type())
                        || !ScenePathTools.ALL.contains(item.faultPlan().tool()))) {
                    throw new IllegalArgumentException("BENCHMARK_UNSUPPORTED_FAULT_PLAN: " + item.id());
                }
                result.add(item);
            } catch (IOException error) {
                throw new IllegalArgumentException("BENCHMARK_CASE_PARSE_FAILED", error);
            }
        }
        if (result.size() < 50 || result.size() > 100) {
            throw new IllegalArgumentException("BENCHMARK_CASE_COUNT_OUT_OF_RANGE: " + result.size());
        }
        if (result.stream().map(Case::expectedScene).distinct().count() != 3) {
            throw new IllegalArgumentException("BENCHMARK_DATASET_MUST_COVER_ALL_RECOMMENDATION_SCENES");
        }
        return List.copyOf(result);
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    static String textSha256(byte[] bytes) {
        String normalized = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n');
        return sha256(normalized.getBytes(StandardCharsets.UTF_8));
    }

    private static String required(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        if (value.isEmpty()) throw new IllegalArgumentException("BENCHMARK_CASE_FIELD_REQUIRED: " + field);
        return value;
    }

    private static String optional(JsonNode node, String field) {
        String value = node.path(field).asText("").trim();
        return value.isEmpty() ? null : value;
    }

    private static List<String> stringList(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        value.forEach(item -> {
            String text = item.asText("").trim();
            if (!text.isEmpty()) result.add(text);
        });
        return List.copyOf(result);
    }

    private static FaultPlan faultPlan(JsonNode node) {
        if (!node.isObject()) return new FaultPlan("none", "", 0);
        return new FaultPlan(node.path("type").asText("none").trim(),
                node.path("tool").asText("").trim(), Math.max(1, node.path("triggerCount").asInt(1)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> objectMap(JsonNode node) {
        return node.isObject() ? Map.copyOf(MAPPER.convertValue(node, Map.class)) : Map.of();
    }

    private static String faultPlanHash(List<Case> cases) {
        try {
            List<Map<String, Object>> plans = cases.stream().sorted(Comparator.comparing(Case::id)).map(item -> {
                Map<String, Object> plan = new LinkedHashMap<>();
                plan.put("caseId", item.id());
                plan.put("faultType", item.faultPlan().type());
                plan.put("faultTool", item.faultPlan().tool());
                plan.put("triggerCount", item.faultPlan().triggerCount());
                plan.put("expectedOutcome", item.expectedOutcome());
                plan.put("expectedFailureReason",
                        item.expectedFailureReason() == null ? "" : item.expectedFailureReason());
                plan.put("expectedRequiredTools", item.expectedRequiredTools());
                return plan;
            }).toList();
            return sha256(MAPPER.writeValueAsBytes(plans));
        } catch (IOException error) {
            throw new IllegalStateException("BENCHMARK_FAULT_PLAN_HASH_FAILED", error);
        }
    }

    private static final class ScenePathTools {
        private static final Set<String> ALL = Set.of(
                "get_user_profile", "load_campaign_constraints", "get_recent_orders", "search_products",
                "check_fulfillment", "check_inventory", "rerank", "filter_products",
                "generate_localized_copy", "generate_retention_copy", "final_answer");
    }

    private static byte[] resourceBytes(String name) {
        try (InputStream stream = AgentBenchmarkDataset.class.getClassLoader().getResourceAsStream(name)) {
            if (stream == null) throw new IllegalStateException("BENCHMARK_RESOURCE_MISSING: " + name);
            return stream.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("BENCHMARK_RESOURCE_READ_FAILED: " + name, error);
        }
    }

    private static Path repositoryRoot() {
        Path current = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        if (Files.exists(current.resolve("benchmarks/tau3-retail"))) return current;
        if (Files.exists(current.resolve("../benchmarks/tau3-retail"))) return current.resolve("..").normalize();
        throw new IllegalStateException("BENCHMARK_REPOSITORY_ROOT_NOT_FOUND");
    }

    private static byte[] readRepositoryFile(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw new IllegalStateException("BENCHMARK_PROVENANCE_FILE_MISSING: " + path.getFileName(), error);
        }
    }
}
