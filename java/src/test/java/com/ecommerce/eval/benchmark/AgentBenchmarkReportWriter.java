package com.ecommerce.eval.benchmark;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Writes independent Java benchmark artifacts; external references remain provenance-only. */
public final class AgentBenchmarkReportWriter {
    public static final Path REPORT_DIR = Path.of("target", "agent-benchmark");
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

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
        md.append("- Execution kind is recorded per variant; only `LIVE_LLM` rows may claim provider-backed Java results.\n");
        md.append("- Provider/model: `").append(report.controls().provider()).append("` / `")
                .append(report.controls().model()).append("`\n");
        md.append("- Dataset SHA-256: `").append(report.controls().datasetHash()).append("`\n");
        md.append("- Toolset SHA-256: `").append(report.controls().toolsetHash()).append("`\n");
        md.append("- Budget: `").append(report.controls().maxCallsPerCase()).append("` LLM calls/case; total ceiling `")
                .append(report.controls().maxTotalCalls()).append("`; seed `").append(report.controls().seed()).append("`\n");
        md.append("- Cross-variant comparison available: **").append(report.crossVariantComparisonAvailable())
                .append("**. ").append(nullSafe(report.comparisonLimitation())).append("\n\n");

        md.append("## Variant Results\n\n");
        md.append("| Variant | Status | Execution | Provider Invoked | VETO | Path Constraint | Success | Rule Violation | Tool Calls | P50/P95 ms | Tokens | Cost/task USD |\n");
        md.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|\n");
        for (AgentBenchmark.VariantRun variant : report.variants()) {
            AgentBenchmark.Metrics m = variant.metrics();
            md.append("| ").append(variant.variantLabel()).append(" | ").append(variant.status())
                    .append(" | ").append(variant.executionKind()).append(" | ").append(variant.providerInvoked())
                    .append(" | ").append(variant.vetoEnabled()).append(" | ").append(variant.pathConstraint())
                    .append(" | ").append(value(m.taskSuccessRate())).append(" | ").append(value(m.ruleViolationRate()))
                    .append(" | ").append(value(m.averageToolCalls())).append(" | ")
                    .append(value(m.p50LatencyMs())).append("/").append(value(m.p95LatencyMs())).append(" | ")
                    .append(value(m.totalTokens())).append(" | ").append(value(m.costPerTaskUsd())).append(" |\n");
            if (variant.unsupportedReason() != null) md.append("  - Unsupported: ").append(variant.unsupportedReason()).append("\n");
        }

        md.append("\n## Per-Case Results\n\n");
        md.append("| Variant | Case | Execution | Provider Invoked | Completed | Success | Rule Violation | Tool Calls | Latency ms | Tokens | Cost USD | Failure |\n");
        md.append("|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for (AgentBenchmark.VariantRun variant : report.variants()) {
            for (AgentBenchmark.CaseObservation item : variant.cases()) {
                Long tokens = item.totalTokens();
                md.append("| ").append(variant.variantId()).append(" | ").append(item.caseId())
                        .append(" | ").append(variant.executionKind()).append(" | ").append(item.providerInvoked())
                        .append(" | ").append(item.completed()).append(" | ").append(item.taskSuccess())
                        .append(" | ").append(item.ruleViolation()).append(" | ").append(item.toolCalls())
                        .append(" | ").append(item.latencyMs()).append(" | ").append(value(tokens))
                        .append(" | ").append(value(item.costUsd())).append(" | ")
                        .append(nullSafe(item.failureReason())).append(" |\n");
            }
        }

        Map<String, Long> failureCounts = report.variants().stream().flatMap(v -> v.cases().stream())
                .filter(c -> c.failureReason() != null && !c.failureReason().isBlank())
                .collect(Collectors.groupingBy(AgentBenchmark.CaseObservation::failureReason, LinkedHashMap::new, Collectors.counting()));
        md.append("\n## Failure Analysis\n\n");
        if (failureCounts.isEmpty()) md.append("No case failures recorded. Unsupported variants are not execution failures and are listed above.\n");
        else failureCounts.forEach((reason, count) -> md.append("- `").append(reason).append("`: ").append(count).append(" case(s)\n"));

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
}
