package com.ecommerce.eval.recommendation;

import com.ecommerce.agent.InventoryAgent;
import com.ecommerce.agent.MarketingCopyAgent;
import com.ecommerce.agent.ProductRecAgent;
import com.ecommerce.agent.UserProfileAgent;
import com.ecommerce.config.RecommendationOrchestrationProperties;
import com.ecommerce.model.AgentLoopResponse;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.ToolCallRecord;
import com.ecommerce.model.ToolLoopRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.ABTestService;
import com.ecommerce.service.AutonomousAgentLoopService;
import com.ecommerce.service.RecommendationModeResolver;
import com.ecommerce.service.RecommendationPipelineExecutor;
import com.ecommerce.service.ScenePathEnforcer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Controlled latency experiment for the orchestration policy. Artificial delays
 * represent independent remote I/O; this is not a production latency claim.
 */
class RecommendationParallelPerformanceTest {
    private static final int FAST_IO_DELAY_MS = 20;
    private static final int PARALLEL_STAGE_DELAY_MS = 120;
    private static final int SINGLE_RUN_SAMPLES = 7;
    private static final int CONCURRENT_REQUESTS = 12;
    private static final int CLIENT_CONCURRENCY = 6;

    @Test
    void parallelPolicyReducesCriticalPathWithoutChangingResults() throws Exception {
        try (Harness serial = new Harness(false); Harness parallel = new Harness(true)) {
            for (int i = 0; i < 2; i++) {
                serial.measure("serial-warmup-" + i);
                parallel.measure("parallel-warmup-" + i);
            }

            List<Sample> serialSingle = new ArrayList<>();
            List<Sample> parallelSingle = new ArrayList<>();
            for (int i = 0; i < SINGLE_RUN_SAMPLES; i++) {
                serialSingle.add(serial.measure("serial-single-" + i));
                parallelSingle.add(parallel.measure("parallel-single-" + i));
            }

            BatchResult serialBatch = runBatch(serial, "serial-batch");
            BatchResult parallelBatch = runBatch(parallel, "parallel-batch");
            verifyEquivalent(serialSingle, parallelSingle);
            assertThat(serialBatch.samples()).allSatisfy(this::assertSuccessful);
            assertThat(parallelBatch.samples()).allSatisfy(this::assertSuccessful);

            LatencyStats serialStats = stats(serialSingle);
            LatencyStats parallelStats = stats(parallelSingle);
            double singleP50Reduction = reduction(serialStats.p50Ms(), parallelStats.p50Ms());
            double throughputIncrease = increase(serialBatch.throughputRps(), parallelBatch.throughputRps());

            // Delays are deliberately much larger than scheduling jitter, so these
            // assertions catch accidental serialization without encoding exact timings.
            assertThat(parallelStats.p50Ms()).isLessThan(serialStats.p50Ms() * 0.80);
            assertThat(parallelBatch.throughputRps()).isGreaterThan(serialBatch.throughputRps() * 1.15);

            writeReport(serialStats, parallelStats, serialBatch, parallelBatch,
                    singleP50Reduction, throughputIncrease);
        }
    }

    private BatchResult runBatch(Harness harness, String prefix) {
        ExecutorService clients = Executors.newFixedThreadPool(CLIENT_CONCURRENCY);
        try {
            long started = System.nanoTime();
            List<CompletableFuture<Sample>> futures = new ArrayList<>();
            for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
                int requestIndex = i;
                futures.add(CompletableFuture.supplyAsync(
                        () -> harness.measure(prefix + "-" + requestIndex), clients));
            }
            List<Sample> samples = futures.stream().map(CompletableFuture::join).toList();
            double elapsedMs = elapsedMs(started);
            return new BatchResult(samples, elapsedMs, CONCURRENT_REQUESTS / (elapsedMs / 1_000.0));
        } finally {
            clients.shutdownNow();
        }
    }

    private void verifyEquivalent(List<Sample> serial, List<Sample> parallel) {
        assertThat(serial).hasSameSizeAs(parallel);
        for (int i = 0; i < serial.size(); i++) {
            assertSuccessful(serial.get(i));
            assertSuccessful(parallel.get(i));
            assertThat(parallel.get(i).toolPath()).isEqualTo(serial.get(i).toolPath());
            assertThat(parallel.get(i).productIds()).isEqualTo(serial.get(i).productIds());
        }
    }

    private void assertSuccessful(Sample sample) {
        assertThat(sample.status()).isEqualTo("completed");
        assertThat(sample.productIds()).containsExactly("P001");
        assertThat(sample.toolPath()).containsExactly(
                "get_user_profile", "search_products", "check_inventory", "rerank", "final_answer");
    }

    private LatencyStats stats(List<Sample> samples) {
        double[] sorted = samples.stream().mapToDouble(Sample::latencyMs).sorted().toArray();
        return new LatencyStats(
                Arrays.stream(sorted).average().orElse(0),
                percentile(sorted, 50),
                percentile(sorted, 95),
                samples.stream().filter(sample -> "completed".equals(sample.status())).count()
                        / (double) samples.size());
    }

    private double percentile(double[] sorted, int percentile) {
        if (sorted.length == 0) return 0;
        int index = Math.max(0, (int) Math.ceil(percentile / 100.0 * sorted.length) - 1);
        return sorted[Math.min(index, sorted.length - 1)];
    }

    private double reduction(double baseline, double candidate) {
        return baseline == 0 ? 0 : (baseline - candidate) / baseline;
    }

    private double increase(double baseline, double candidate) {
        return baseline == 0 ? 0 : (candidate - baseline) / baseline;
    }

    private void writeReport(
            LatencyStats serial,
            LatencyStats parallel,
            BatchResult serialBatch,
            BatchResult parallelBatch,
            double singleP50Reduction,
            double throughputIncrease) throws Exception {
        Path directory = reportDirectory();
        Files.createDirectories(directory);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("label", "Synthetic dependency-aware orchestration performance test; not production traffic");
        report.put("generatedAt", Instant.now().toString());
        report.put("configuration", Map.of(
                "scene", "homepage",
                "fastIoDelayMs", FAST_IO_DELAY_MS,
                "inventoryAndRerankDelayMs", PARALLEL_STAGE_DELAY_MS,
                "singleRunSamples", SINGLE_RUN_SAMPLES,
                "concurrentRequests", CONCURRENT_REQUESTS,
                "clientConcurrency", CLIENT_CONCURRENCY,
                "coordinatorThreads", Harness.COORDINATOR_THREADS,
                "businessThreads", Harness.BUSINESS_THREADS));
        report.put("serial", Map.of(
                "singleRequestLatency", serial,
                "batchElapsedMs", serialBatch.elapsedMs(),
                "throughputRps", serialBatch.throughputRps(),
                "successRate", successRate(serialBatch.samples())));
        report.put("parallel", Map.of(
                "singleRequestLatency", parallel,
                "batchElapsedMs", parallelBatch.elapsedMs(),
                "throughputRps", parallelBatch.throughputRps(),
                "successRate", successRate(parallelBatch.samples())));
        report.put("comparison", Map.of(
                "singleRequestP50LatencyReduction", singleP50Reduction,
                "concurrentThroughputIncrease", throughputIncrease,
                "outputEquivalent", true));

        ObjectMapper mapper = new ObjectMapper();
        mapper.writerWithDefaultPrettyPrinter()
                .writeValue(directory.resolve("recommendation-parallel-report.json").toFile(), report);
        Files.writeString(directory.resolve("recommendation-parallel-report.md"), markdown(
                serial, parallel, serialBatch, parallelBatch, singleP50Reduction, throughputIncrease));
    }

    private double successRate(List<Sample> samples) {
        return samples.stream().filter(sample -> "completed".equals(sample.status())).count()
                / (double) samples.size();
    }

    private String markdown(
            LatencyStats serial,
            LatencyStats parallel,
            BatchResult serialBatch,
            BatchResult parallelBatch,
            double p50Reduction,
            double throughputIncrease) {
        return """
                # Recommendation Parallel Orchestration Test

                This is a controlled synthetic-I/O test of orchestration policy, not a production latency claim.
                Both variants execute the same homepage task and return the same tool path and product IDs.

                ## Configuration

                - Fast profile/recall I/O delay: %d ms
                - Independent inventory/rerank I/O delay: %d ms each
                - Single-request measured samples: %d per variant
                - Concurrent batch: %d requests, client concurrency %d
                - Runtime capacity: %d coordinator threads, %d business threads

                ## Results

                | Policy | Single mean (ms) | Single p50 (ms) | Single p95 (ms) | Batch elapsed (ms) | Throughput (req/s) | Success rate |
                |---|---:|---:|---:|---:|---:|---:|
                | Serial | %s | %s | %s | %s | %s | %s |
                | Dependency-aware parallel | %s | %s | %s | %s | %s | %s |

                - Single-request p50 latency reduction: %s
                - Concurrent throughput increase: %s
                - Output equivalence: PASS

                The result isolates the benefit of overlapping independent inventory and rerank calls. Real gains depend on connector latency, thread-pool sizing, downstream rate limits and traffic shape.
                """.formatted(
                FAST_IO_DELAY_MS, PARALLEL_STAGE_DELAY_MS, SINGLE_RUN_SAMPLES,
                CONCURRENT_REQUESTS, CLIENT_CONCURRENCY, Harness.COORDINATOR_THREADS, Harness.BUSINESS_THREADS,
                fmt(serial.meanMs()), fmt(serial.p50Ms()), fmt(serial.p95Ms()),
                fmt(serialBatch.elapsedMs()), fmt(serialBatch.throughputRps()), pct(successRate(serialBatch.samples())),
                fmt(parallel.meanMs()), fmt(parallel.p50Ms()), fmt(parallel.p95Ms()),
                fmt(parallelBatch.elapsedMs()), fmt(parallelBatch.throughputRps()), pct(successRate(parallelBatch.samples())),
                pct(p50Reduction), pct(throughputIncrease));
    }

    private String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private String pct(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }

    private Path reportDirectory() {
        Path workingDirectory = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        Path javaRoot = "java".equalsIgnoreCase(String.valueOf(workingDirectory.getFileName()))
                ? workingDirectory : workingDirectory.resolve("java");
        return javaRoot.resolve(Path.of("target", "recommendation-eval"));
    }

    private static double elapsedMs(long started) {
        return (System.nanoTime() - started) / 1_000_000.0;
    }

    private record Sample(
            double latencyMs,
            String status,
            List<String> toolPath,
            List<String> productIds) {}

    private record LatencyStats(double meanMs, double p50Ms, double p95Ms, double successRate) {}

    private record BatchResult(List<Sample> samples, double elapsedMs, double throughputRps) {}

    private static final class Harness implements AutoCloseable {
        private static final int COORDINATOR_THREADS = 16;
        private static final int BUSINESS_THREADS = 32;

        private final ExecutorService coordinator = Executors.newFixedThreadPool(COORDINATOR_THREADS);
        private final ExecutorService business = Executors.newFixedThreadPool(BUSINESS_THREADS);
        private final AutonomousAgentLoopService service;

        private Harness(boolean parallelEnabled) {
            UserProfileAgent profileAgent = mock(UserProfileAgent.class);
            ProductRecAgent productAgent = mock(ProductRecAgent.class);
            InventoryAgent inventoryAgent = mock(InventoryAgent.class);
            MarketingCopyAgent copyAgent = mock(MarketingCopyAgent.class);
            ChatClient.Builder chatBuilder = mock(ChatClient.Builder.class);
            when(chatBuilder.build()).thenReturn(mock(ChatClient.class));

            UserProfile profile = UserProfile.builder()
                    .userId("benchmark-user")
                    .segments(List.of("active"))
                    .preferredCategories(List.of("phone"))
                    .build();
            Product product = Product.builder()
                    .productId("P001").name("Phone").category("phone")
                    .price(1000).stock(10).platform("shopify").currency("SGD")
                    .warehouseRegion("SG").deliveryDays(2).supportedRegions(List.of("SG"))
                    .crossBorderEligible(true).build();

            when(profileAgent.runAsync(anyMap(), any(Executor.class))).thenAnswer(invocation ->
                    delayed(invocation.getArgument(1), FAST_IO_DELAY_MS,
                            () -> result("user_profile", Map.of("profile", profile))));
            when(productAgent.runAsync(anyMap(), any(Executor.class))).thenAnswer(invocation -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> arguments = invocation.getArgument(0);
                int delay = arguments.containsKey("candidateProducts")
                        ? PARALLEL_STAGE_DELAY_MS : FAST_IO_DELAY_MS;
                return delayed(invocation.getArgument(1), delay,
                        () -> result("product_rec", Map.of("products", List.of(product))));
            });
            when(inventoryAgent.runAsync(anyMap(), any(Executor.class))).thenAnswer(invocation ->
                    delayed(invocation.getArgument(1), PARALLEL_STAGE_DELAY_MS,
                            () -> result("inventory", Map.of("available_products", List.of("P001")))));

            RecommendationPipelineExecutor pipeline = new RecommendationPipelineExecutor(
                    profileAgent, productAgent, inventoryAgent, copyAgent, business);
            RecommendationOrchestrationProperties properties = new RecommendationOrchestrationProperties();
            properties.setParallelEnabled(parallelEnabled);
            service = new AutonomousAgentLoopService(
                    pipeline, new ABTestService(), chatBuilder, new ScenePathEnforcer(),
                    new RecommendationModeResolver("RULES", ""), 0, null, properties, coordinator);
        }

        private Sample measure(String runId) {
            long started = System.nanoTime();
            AgentLoopResponse response = service.run(ToolLoopRequest.builder()
                    .runId(runId)
                    .request(RecommendationRequest.builder()
                            .userId(runId).scene("homepage").numItems(1)
                            .platform("shopify").region("SEA").country("SG")
                            .locale("en-SG").currency("SGD").build())
                    .build());
            List<String> toolPath = response.getToolCalls().stream()
                    .map(ToolCallRecord::getToolName).toList();
            List<String> productIds = response.getPlan() == null ? List.of()
                    : response.getPlan().getProducts().stream().map(Product::getProductId).toList();
            return new Sample(elapsedMs(started), response.getStatus(), toolPath, productIds);
        }

        @Override
        public void close() {
            coordinator.shutdownNow();
            business.shutdownNow();
        }

        private static AgentResult result(String agent, Map<String, Object> data) {
            return AgentResult.builder().agentName(agent).success(true).data(data).build();
        }

        private static <T> CompletableFuture<T> delayed(
                Executor executor,
                int delayMs,
                Supplier<T> supplier) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("synthetic I/O interrupted", interrupted);
                }
                return supplier.get();
            }, executor);
        }
    }
}
