package com.ecommerce.eval.benchmark;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Shared benchmark contract. Offline observations are never interchangeable with live LLM runs. */
public final class AgentBenchmark {
    public enum ExecutionKind { OFFLINE_SIMULATION, LIVE_LLM, EXTERNAL_REFERENCE, NOT_EXECUTED }
    public enum VariantStatus { SUPPORTED, UNSUPPORTED }

    public static final String RUNNABLE_VARIANT = "java-guarded-multi-agent-veto-on-path-on";

    private AgentBenchmark() {}

    public static List<Variant> variants() {
        return List.of(
                new Variant("java-single-agent-live", "Single Agent", "single", false, false, false,
                        VariantStatus.UNSUPPORTED, "No dynamic Java Single Agent executor; ConstrainedToolLoopService is a fixed sequence."),
                new Variant("java-multi-agent-unconstrained-live", "Multi-Agent without guard constraints", "multi", false, false, false,
                        VariantStatus.UNSUPPORTED, "No unguarded dynamic Java Multi-Agent executor; SupervisorOrchestrator is a fixed workflow baseline."),
                new Variant(RUNNABLE_VARIANT, "Existing Java Guarded Multi-Agent", "multi", true, true, true,
                        VariantStatus.SUPPORTED, null),
                new Variant("java-guarded-multi-agent-veto-off", "Guarded Multi-Agent, VETO off", "multi", true, false, true,
                        VariantStatus.UNSUPPORTED, "VETO cannot currently be disabled independently in the production runtime."),
                new Variant("java-guarded-multi-agent-path-off", "Guarded Multi-Agent, path constraint off", "multi", true, true, false,
                        VariantStatus.UNSUPPORTED, "Scene path enforcement cannot currently be disabled independently in the production runtime."),
                new Variant("java-guarded-multi-agent-veto-off-path-off", "Guarded Multi-Agent, VETO/path off", "multi", true, false, false,
                        VariantStatus.UNSUPPORTED, "Neither VETO nor scene path enforcement has an isolated production toggle."));
    }

    public static void requireRunnable(Variant variant) {
        if (variant == null || variant.status() != VariantStatus.SUPPORTED) {
            throw new IllegalArgumentException("BENCHMARK_VARIANT_UNSUPPORTED: "
                    + (variant == null ? "unknown" : variant.id()) + ": "
                    + (variant == null ? "variant_not_registered" : variant.unsupportedReason()));
        }
    }

    /** Controls held constant across paired arms; guard toggles live on Variant. */
    public record Controls(String provider, String model, String datasetHash, String toolsetHash,
                           int maxCallsPerCase, int maxTotalCalls, long seed) {
        public Controls {
            Objects.requireNonNull(provider);
            Objects.requireNonNull(model);
            Objects.requireNonNull(datasetHash);
            Objects.requireNonNull(toolsetHash);
            if (maxCallsPerCase <= 0 || maxTotalCalls < maxCallsPerCase) {
                throw new IllegalArgumentException("BENCHMARK_BUDGET_INVALID");
            }
        }
    }

    public record Variant(String id, String label, String topology, boolean guarded,
                          boolean vetoEnabled, boolean pathConstraint, VariantStatus status,
                          String unsupportedReason) {}

    public record CaseObservation(String caseId, boolean completed, boolean taskSuccess,
                                  boolean ruleViolation, int toolCalls, double latencyMs,
                                  boolean providerInvoked, String usageStatus,
                                  Long promptTokens, Long completionTokens, Long totalTokens,
                                  Double costUsd, String failureReason) {}

    public record Metrics(int caseCount, Double taskSuccessRate, Double ruleViolationRate,
                          Double averageToolCalls, Double p50LatencyMs, Double p95LatencyMs,
                          Long totalTokens, Double averageTokensPerTask, Double totalCostUsd,
                          Double costPerTaskUsd, String usageStatus, String costStatus) {}

    public record VariantRun(String variantId, String variantLabel, VariantStatus status,
                             ExecutionKind executionKind, boolean providerInvoked, String provider,
                             String model, String datasetHash, String toolsetHash,
                             int maxCallsPerCase, int maxTotalCalls, long seed,
                             boolean pathConstraint, boolean vetoEnabled, String unsupportedReason,
                             Metrics metrics, List<CaseObservation> cases) {}

    public record DataReference(String id, ExecutionKind executionKind, String path,
                                String sha256, boolean includedInJavaMetrics, String note) {}

    public record Report(String benchmarkId, String generatedAt, String javaRevision,
                         Controls controls, List<DataReference> dataReferences,
                         List<VariantRun> variants, boolean crossVariantComparisonAvailable,
                         String comparisonLimitation) {}

    public record PriceConfig(BigDecimal inputUsdPerMillionTokens,
                              BigDecimal outputUsdPerMillionTokens) {
        public PriceConfig {
            if ((inputUsdPerMillionTokens == null) != (outputUsdPerMillionTokens == null)) {
                throw new IllegalArgumentException("BENCHMARK_PRICE_CONFIG_REQUIRES_BOTH_RATES");
            }
            if (inputUsdPerMillionTokens != null
                    && (inputUsdPerMillionTokens.signum() < 0 || outputUsdPerMillionTokens.signum() < 0)) {
                throw new IllegalArgumentException("BENCHMARK_PRICE_CONFIG_MUST_BE_NON_NEGATIVE");
            }
        }

        public Double costUsd(Long promptTokens, Long completionTokens, String usageStatus) {
            if (inputUsdPerMillionTokens == null || !"available".equals(usageStatus)
                    || promptTokens == null || completionTokens == null) return null;
            BigDecimal promptCost = BigDecimal.valueOf(promptTokens)
                    .multiply(inputUsdPerMillionTokens).divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
            BigDecimal completionCost = BigDecimal.valueOf(completionTokens)
                    .multiply(outputUsdPerMillionTokens).divide(BigDecimal.valueOf(1_000_000), 12, RoundingMode.HALF_UP);
            return promptCost.add(completionCost).doubleValue();
        }

        public String status() { return inputUsdPerMillionTokens == null ? "unavailable_price_not_configured" : "configured"; }
    }

    public static Metrics summarize(List<CaseObservation> cases, PriceConfig prices) {
        if (cases == null || cases.isEmpty()) {
            return new Metrics(0, null, null, null, null, null, null, null, null, null,
                    "unavailable_no_cases", "unavailable_no_cases");
        }
        int count = cases.size();
        double successes = cases.stream().filter(CaseObservation::taskSuccess).count();
        double violations = cases.stream().filter(CaseObservation::ruleViolation).count();
        double averageCalls = cases.stream().mapToInt(CaseObservation::toolCalls).average().orElse(0);
        double[] latencies = cases.stream().mapToDouble(CaseObservation::latencyMs).sorted().toArray();
        boolean completeUsage = cases.stream().allMatch(c -> c.providerInvoked()
                && "available".equals(c.usageStatus()) && c.totalTokens() != null
                && c.promptTokens() != null && c.completionTokens() != null);
        Long tokens = completeUsage ? cases.stream().mapToLong(CaseObservation::totalTokens).sum() : null;
        Double averageTokens = completeUsage ? tokens / (double) count : null;
        String usageStatus = completeUsage ? "available" : cases.stream().anyMatch(c -> c.providerInvoked())
                ? "partial_or_unavailable" : "unavailable_provider_not_invoked";
        boolean completeCosts = completeUsage && prices != null && "configured".equals(prices.status())
                && cases.stream().allMatch(c -> c.costUsd() != null);
        Double totalCost = completeCosts ? cases.stream().mapToDouble(CaseObservation::costUsd).sum() : null;
        return new Metrics(count, successes / count, violations / count, averageCalls,
                percentile(latencies, 50), percentile(latencies, 95), tokens, averageTokens,
                totalCost, totalCost == null ? null : totalCost / count, usageStatus,
                completeCosts ? "available" : prices == null || !"configured".equals(prices.status())
                        ? "unavailable_price_not_configured" : "unavailable_usage_incomplete");
    }

    public static void requireFairControls(List<Controls> controls) {
        if (controls == null || controls.isEmpty()) throw new IllegalArgumentException("BENCHMARK_CONTROLS_EMPTY");
        Controls first = controls.get(0);
        for (Controls candidate : controls) {
            if (!Objects.equals(first.provider(), candidate.provider())
                    || !Objects.equals(first.model(), candidate.model())
                    || !Objects.equals(first.datasetHash(), candidate.datasetHash())
                    || !Objects.equals(first.toolsetHash(), candidate.toolsetHash())
                    || first.maxCallsPerCase() != candidate.maxCallsPerCase()
                    || first.maxTotalCalls() != candidate.maxTotalCalls()
                    || first.seed() != candidate.seed()) {
                throw new IllegalArgumentException("BENCHMARK_UNFAIR_CONTROLS: model, dataset, toolset, budget and seed must match");
            }
        }
    }

    public static List<VariantRun> unexecutedVariants(Controls controls) {
        List<VariantRun> result = new ArrayList<>();
        for (Variant variant : variants()) {
            if (variant.status() == VariantStatus.SUPPORTED) continue;
            result.add(new VariantRun(variant.id(), variant.label(), variant.status(),
                    ExecutionKind.NOT_EXECUTED, false, controls.provider(), controls.model(),
                    controls.datasetHash(), controls.toolsetHash(), controls.maxCallsPerCase(),
                    controls.maxTotalCalls(), controls.seed(), variant.pathConstraint(), variant.vetoEnabled(),
                    variant.unsupportedReason(), summarize(List.of(), null), List.of()));
        }
        return List.copyOf(result);
    }

    public static double percentile(double[] sorted, int percentile) {
        if (sorted.length == 0) return 0;
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }
}
