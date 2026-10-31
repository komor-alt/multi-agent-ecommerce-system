package com.ecommerce.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Writes independent Java benchmark artifacts; external references remain provenance-only. */
public final class AgentBenchmarkReportWriter {
    public static final Path REPORT_DIR = Path.of("target", "agent-benchmark");
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public static Path offlineReportDirectory(Path projectDirectory) {
        return Objects.requireNonNull(projectDirectory, "projectDirectory").resolve(REPORT_DIR);
    }

    public static Path liveReportDirectory(Path projectDirectory, String scope, String arms) {
        return offlineReportDirectory(projectDirectory).resolve(
                "live-" + stableSegment(scope, "scope") + "-" + stableSegment(arms, "arms"));
    }

    public void write(AgentBenchmark.Report report, Path directory) {
        try {
            Files.createDirectories(directory);
            Files.writeString(directory.resolve("agent-benchmark-report.json"),
                    mapper.writeValueAsString(report), StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("agent-benchmark-report.md"), markdown(report), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("AGENT_BENCHMARK_REPORT_WRITE_FAILED", error);
        }
    }

    static String markdown(AgentBenchmark.Report report) {
        StringBuilder md = new StringBuilder("# Java Agent Benchmark\n\n");
        md.append("- Generated: `").append(report.generatedAt()).append("`\n");
        md.append("- Java revision: `").append(nullSafe(report.javaRevision())).append("`\n");
        md.append("- Scope: `").append(report.scope()).append("`\n");
        md.append("- Execution kind is recorded per variant; only `LIVE_LLM` rows may claim provider-backed Java results.\n");
        md.append("- Provider/model: `").append(report.controls().provider()).append("` / `")
                .append(report.controls().model()).append("`\n");
        md.append("- Dataset SHA-256: `").append(report.controls().datasetHash()).append("`\n");
        report.controls().suiteHashes().forEach((suite, hash) -> md.append("  - Suite `")
                .append(suite).append("`: `").append(hash).append("`\n"));
        md.append("- Toolset SHA-256: `").append(report.controls().toolsetHash()).append("`\n");
        md.append("- Fault-plan SHA-256: `").append(report.controls().faultPlanHash()).append("`\n");
        md.append("- Budget: `").append(report.controls().maxCallsPerCase()).append("` LLM calls/case; total ceiling `")
                .append(report.controls().maxTotalCalls()).append("`; max output tokens/call `")
                .append(report.controls().maxOutputTokensPerCall()).append("`; max steps `")
                .append(report.controls().maxSteps()).append("`; seed `").append(report.controls().seed()).append("`\n");
        md.append("- The output-token cap applies to completions only; call ceiling multiplied by this value is not a total-token ceiling because prompt tokens are separate.\n");
        md.append("- Parallel policy: `").append(report.controls().parallelPolicy()).append("`\n");
        md.append("- Cross-variant comparison available: **").append(report.crossVariantComparisonAvailable())
                .append("**. ").append(nullSafe(report.comparisonLimitation())).append("\n\n");
        md.append("`businessTaskSuccessRate` counts completed outputs that satisfy business invariants, independent of execution-path or provenance compliance. `safeTaskSuccessRate` additionally requires that business success has no recorded rule violation. `expectedBehaviorMatchRate` checks the declared outcome, capability contract, and live provenance and also credits an expected safe failure or block. These rates, completion, and rule violation use only valid experiment cases. A case with an explicit `llm_budget_exhausted` or `llm_budget_unavailable` trace is reported as invalid and excluded from those denominators; provider errors and timeouts remain valid failures. Provider-call statistics are observed counts, not inferred budgets.\n\n");

        md.append("## Variant Results\n\n");
        md.append("| Variant | Topology | Status | Execution | Provider Invoked | VETO | Path Constraint | Parallel | Cases | Valid | Invalid/Rate | Business Success | Safe Success | Expected Match | Completion | Rule Violation | Avg/P95 Provider Calls | Tool Calls | P50/P95 ms | Tokens | Cost/task USD |\n");
        md.append("|---|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (AgentBenchmark.VariantRun variant : report.variants()) {
            AgentBenchmark.Metrics m = variant.metrics();
            md.append("| ").append(variant.variantLabel()).append(" | ").append(variant.topology())
                    .append(" | ").append(variant.status())
                    .append(" | ").append(variant.executionKind()).append(" | ").append(variant.providerInvoked())
                    .append(" | ").append(variant.vetoEnabled()).append(" | ").append(variant.pathConstraint())
                    .append(" | ").append(variant.parallelScheduling())
                    .append(" | ").append(m.caseCount()).append(" | ").append(m.validCaseCount())
                    .append(" | ").append(m.invalidCaseCount()).append("/")
                    .append(value(m.invalidCaseRate()))
                    .append(" | ").append(value(m.businessTaskSuccessRate())).append(" | ")
                    .append(value(m.safeTaskSuccessRate())).append(" | ")
                    .append(value(m.expectedBehaviorMatchRate())).append(" | ")
                    .append(value(m.completionRate())).append(" | ").append(value(m.ruleViolationRate()))
                    .append(" | ").append(value(m.averageProviderCalls())).append("/")
                    .append(value(m.p95ProviderCalls()))
                    .append(" | ").append(value(m.averageToolCalls())).append(" | ")
                    .append(value(m.p50LatencyMs())).append("/").append(value(m.p95LatencyMs())).append(" | ")
                    .append(value(m.totalTokens())).append(" | ").append(value(m.costPerTaskUsd())).append(" |\n");
            if (variant.unsupportedReason() != null) md.append("  - Unsupported: ").append(variant.unsupportedReason()).append("\n");
        }

        md.append("\n## Grouped Metrics\n\n");
        md.append("| Variant | Dimension | Value | Cases | Valid | Invalid/Rate | Business Success | Safe Success | Expected Match | Completion | Rule Violation | Avg/P95 Provider Calls | Tool Calls | P50/P95 ms |\n");
        md.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (AgentBenchmark.VariantRun variant : report.variants()) {
            for (AgentBenchmark.GroupMetrics group : variant.groupedMetrics()) {
                AgentBenchmark.Metrics m = group.metrics();
                md.append("| ").append(variant.variantId()).append(" | ").append(group.dimension())
                        .append(" | ").append(group.value()).append(" | ").append(m.caseCount())
                        .append(" | ").append(m.validCaseCount()).append(" | ")
                        .append(m.invalidCaseCount()).append("/").append(value(m.invalidCaseRate()))
                        .append(" | ").append(value(m.businessTaskSuccessRate()))
                        .append(" | ").append(value(m.safeTaskSuccessRate()))
                        .append(" | ").append(value(m.expectedBehaviorMatchRate()))
                        .append(" | ").append(value(m.completionRate()))
                        .append(" | ").append(value(m.ruleViolationRate()))
                        .append(" | ").append(value(m.averageProviderCalls())).append("/")
                        .append(value(m.p95ProviderCalls()))
                        .append(" | ").append(value(m.averageToolCalls()))
                        .append(" | ").append(value(m.p50LatencyMs())).append("/")
                        .append(value(m.p95LatencyMs())).append(" |\n");
            }
        }

        md.append("\n## Per-Case Results\n\n");
        md.append("| Variant | Case | Suite | Category | Complexity | Expected/Actual | Execution | Valid | Invalid reason | Provider Invoked | LLM Calls | Provider Calls | Completed | Business Success | Safe Success | Expected Match | Rule Violation | Tool Calls | Latency ms | Capabilities | Tool Trace | Tokens | Cost USD | Observed failure | Expectation mismatch |\n");
        md.append("|---|---|---|---|---|---|---|---:|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|---|---:|---:|---|---|\n");
        for (AgentBenchmark.VariantRun variant : report.variants()) {
            for (AgentBenchmark.CaseObservation item : variant.cases()) {
                Long tokens = item.totalTokens();
                md.append("| ").append(variant.variantId()).append(" | ").append(item.caseId())
                        .append(" | ").append(item.suite()).append(" | ").append(item.category())
                        .append(" | ").append(item.complexity()).append(" | ")
                        .append(item.expectedOutcome()).append("/").append(item.actualOutcome())
                        .append(" | ").append(variant.executionKind()).append(" | ").append(item.validExperiment())
                        .append(" | ").append(nullSafe(item.invalidReason()))
                        .append(" | ").append(item.providerInvoked())
                        .append(" | ").append(item.llmCallCount()).append(" | ").append(item.providerCallCount())
                        .append(" | ").append(item.completed()).append(" | ").append(item.businessTaskSuccess())
                        .append(" | ").append(item.safeTaskSuccess())
                        .append(" | ").append(item.expectedBehaviorMatch())
                        .append(" | ").append(item.ruleViolation()).append(" | ").append(item.toolCalls())
                        .append(" | ").append(item.latencyMs()).append(" | ")
                        .append(nullSafe(String.join(",", item.observedCapabilities()))).append(" | ")
                        .append(nullSafe(String.join(",", item.observedTools()))).append(" | ").append(value(tokens))
                        .append(" | ").append(value(item.costUsd())).append(" | ")
                        .append(nullSafe(item.observedFailureReason())).append(" | ")
                        .append(nullSafe(item.expectationMismatch())).append(" |\n");
            }
        }

        Map<String, Long> failureCounts = report.variants().stream().flatMap(v -> v.cases().stream())
                .filter(c -> c.observedFailureReason() != null && !c.observedFailureReason().isBlank())
                .collect(Collectors.groupingBy(AgentBenchmark.CaseObservation::observedFailureReason,
                        LinkedHashMap::new, Collectors.counting()));
        md.append("\n## Failure Analysis\n\n");
        if (failureCounts.isEmpty()) md.append("No runtime failure outcomes recorded.\n");
        else failureCounts.forEach((reason, count) -> md.append("- `").append(reason).append("`: ").append(count).append(" case(s)\n"));

        Map<String, Long> mismatchCounts = report.variants().stream().flatMap(v -> v.cases().stream())
                .filter(c -> c.expectationMismatch() != null && !c.expectationMismatch().isBlank())
                .collect(Collectors.groupingBy(AgentBenchmark.CaseObservation::expectationMismatch,
                        LinkedHashMap::new, Collectors.counting()));
        md.append("\n## Expectation Mismatches\n\n");
        if (mismatchCounts.isEmpty()) md.append("No expectation mismatches recorded.\n");
        else mismatchCounts.forEach((reason, count) -> md.append("- `").append(reason).append("`: ").append(count).append(" case(s)\n"));

        md.append("\n## Dataset Provenance\n\n");
        md.append("| Dataset | Kind | SHA-256 | Aggregated into Java metrics | Note |\n|---|---|---|---:|---|\n");
        for (AgentBenchmark.DataReference reference : report.dataReferences()) {
            md.append("| ").append(reference.id()).append(" (`").append(reference.path()).append("`) | ")
                    .append(reference.executionKind()).append(" | `").append(reference.sha256()).append("` | ")
                    .append(reference.includedInJavaMetrics()).append(" | ").append(reference.note()).append(" |\n");
        }
        md.append("\nToken usage is null unless provider usage is complete for every executed case. Cost is calculated only from actual prompt/completion usage and explicit input/output USD-per-million-token rates; otherwise it is null. No synthetic, RULES, injected-output, or tau3 result is included in Java live aggregates.\n");
        return md.toString();
    }

    private static String value(Object value) { return value == null ? "unavailable" : value.toString(); }
    private static String nullSafe(String value) { return value == null ? "-" : value.replace('|', '/').replace('\n', ' '); }

    private static String stableSegment(String value, String label) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("BENCHMARK_REPORT_"
                    + label.toUpperCase(Locale.ROOT) + "_REQUIRED");
        }
        return normalized;
    }
}
