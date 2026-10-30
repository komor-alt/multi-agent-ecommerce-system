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
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
                       int numItems) {}

    public record Loaded(List<Case> cases, String sha256, List<AgentBenchmark.DataReference> references) {}

    public static Loaded load() {
        byte[] bytes = resourceBytes(RESOURCE);
        List<Case> cases = parse(new String(bytes, StandardCharsets.UTF_8));
        String hash = textSha256(bytes);
        List<AgentBenchmark.DataReference> refs = new ArrayList<>();
        refs.add(new AgentBenchmark.DataReference("java-recommendation-e2e", AgentBenchmark.ExecutionKind.LIVE_LLM,
                "java/src/test/resources/" + RESOURCE, hash, true,
                "Synthetic Java recommendation mainline E2E dataset; only live runs populate Java benchmark metrics."));
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
        return new Loaded(cases, hash, List.copyOf(refs));
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
                        node.path("numItems").asInt(2));
                if (!ids.add(item.id())) throw new IllegalArgumentException("BENCHMARK_DUPLICATE_CASE_ID: " + item.id());
                if (!Set.of("homepage", "campaign", "retention").contains(item.expectedScene())) {
                    throw new IllegalArgumentException("BENCHMARK_INVALID_EXPECTED_SCENE: " + item.id());
                }
                if (item.numItems() < 1 || item.numItems() > 10) {
                    throw new IllegalArgumentException("BENCHMARK_INVALID_NUM_ITEMS: " + item.id());
                }
                result.add(item);
            } catch (IOException error) {
                throw new IllegalArgumentException("BENCHMARK_CASE_PARSE_FAILED", error);
            }
        }
        if (result.size() < 3 || result.size() > 12) {
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
