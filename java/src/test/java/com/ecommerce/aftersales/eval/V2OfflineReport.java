package com.ecommerce.aftersales.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Predicate;
import static com.ecommerce.aftersales.eval.AfterSalesEvalHarness.*;

/** Reports observations, never substitutes safe termination or reduced calls for business success. */
final class V2OfflineReport {
    record BaselineCase(String id, boolean taskSuccess, String stopReason, Integer toolCalls,
                        Integer handlingSteps, long latencyMs, Boolean policyViolation) {}

    static void write(EvalReport report, List<BaselineCase> baseline, ObjectMapper mapper) {
        Map<String, Object> guarded = new LinkedHashMap<>();
        List<CaseResult> cases = report.cases();
        guarded.put("taskSuccessRate", rate(cases, CaseResult::taskSuccess));
        guarded.put("completionRate", report.metrics().completionRate());
        guarded.put("policyViolationRate", rate(cases, r -> r.trustedAmountMismatch() || r.modelAmountAccepted()
                || r.modelToolArgsAccepted() || r.unauthorizedAction() || r.forbiddenEvidenceCollected()));
        guarded.put("wrongEntityRate", rate(cases, CaseResult::wrongEntity));
        guarded.put("confirmationViolationRate", rate(cases, r -> r.proposalApproved() || r.executionJobCreated()));
        int attempts = cases.stream().mapToInt(CaseResult::toolCallCount).sum();
        guarded.put("invalidToolCallRate", attempts == 0 ? null
                : cases.stream().mapToInt(CaseResult::invalidToolCalls).sum() / (double) attempts);
        guarded.put("invalidToolCallRateNote", "Non-whitelisted actual tool_started actions / all tool_started attempts. Rejected plans are counted separately.");
        guarded.put("unsafePlanAttemptRate", rate(cases, r -> r.rejectedPlans() > 0
                || r.fallbackReasons().contains("LLM_INVALID_OUTPUT")));
        guarded.put("recoverySuccessRate", null);
        guarded.put("recoverySuccessRateNote", "Execution recovery is evaluated separately by ExecutionFailureRecoveryTest, not this proposal-stage suite.");
        guarded.put("averageToolCalls", report.metrics().averageToolCalls());
        guarded.put("averageHandlingSteps", cases.stream().mapToInt(CaseResult::handlingSteps).average().orElse(0));
        guarded.put("llmCallCount", cases.stream().mapToInt(CaseResult::llmCallCount).sum());
        guarded.put("modelMode", "scripted offline responses plus RULES; no provider requests");
        guarded.put("tokens", null);
        guarded.put("cost", null);
        guarded.put("latencyMs", report.metrics().latencyMs());

        Map<String, Object> fixed = new LinkedHashMap<>();
        fixed.put("taskSuccessRate", baseline.stream().filter(BaselineCase::taskSuccess).count() / (double) baseline.size());
        fixed.put("averageToolCalls", baseline.stream().filter(r -> r.toolCalls() != null).mapToInt(BaselineCase::toolCalls).average().orElse(0));
        fixed.put("averageHandlingSteps", baseline.stream().filter(r -> r.handlingSteps() != null).mapToInt(BaselineCase::handlingSteps).average().orElse(0));
        fixed.put("missingToolCountCases", baseline.stream().filter(r -> r.toolCalls() == null).count());
        fixed.put("modelMode", "same intake fixture; no evidence planner");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("generatedAt", report.generatedAt());
        result.put("caseCount", report.caseCount());
        result.put("datasetSha256", datasetSha256());
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("sourceRevision", System.getProperty("v2.revision", "unrecorded-working-tree"));
        result.put("trajectoryDirectory", "trajectories/");
        result.put("methodology", "Paired identical messages, order fixtures, attachments and business dates. Fixed baseline always attempts seven reads. Guarded runs production Loop. Task success checks expected outcome and amount/currency/policy, not lower tool count. Planner injections have no equivalent baseline planner. Proposal-stage only; not end-to-end real commerce success.");
        result.put("guarded", guarded);
        result.put("fixedWorkflow", fixed);
        result.put("guardedCases", cases);
        result.put("baselineCases", baseline);
        result.put("publicBenchmark", "NOT RUN: user requested offline testing only");
        try {
            Files.writeString(REPORT_DIR.resolve("v2-comparison.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), StandardCharsets.UTF_8);
            StringBuilder md = new StringBuilder("# V2 offline paired evaluation\n\n");
            md.append("Generated: ").append(report.generatedAt()).append("\n\n");
            md.append("Scripted model outputs / RULES, in-memory repositories, mock connector. **Not a public benchmark or real-model success score.**\n\n");
            md.append("| Metric | Guarded | Fixed seven-read workflow |\n|---|---:|---:|\n");
            for (String key : List.of("taskSuccessRate", "averageToolCalls", "averageHandlingSteps"))
                md.append("| ").append(key).append(" | ").append(guarded.get(key)).append(" | ").append(fixed.get(key)).append(" |\n");
            md.append("\nCompletion is safe termination, not business success. Task success independently checks the expected proposal/no-action/wait outcome and amount/currency/policy.\n");
            md.append("\nSafety, model counters and per-case results: see v2-comparison.json. Unmeasured fields are null, not zero. Execution failure recovery is a separate test suite.\n");
            md.append("\nPublic 10/50-task benchmark: **NOT RUN** (user decision).\n");
            md.append("\n## Cases not meeting business expectations\n\n");
            for (CaseResult c : cases) if (!c.taskSuccess())
                md.append("- ").append(c.id()).append(": ").append(c.stopReason()).append("; proposalCorrect=").append(c.proposalCorrect()).append("\n");
            md.append("\n## Fixed workflow cases not meeting expectations\n\n");
            for (BaselineCase c : baseline) if (!c.taskSuccess())
                md.append("- ").append(c.id()).append(": ").append(c.stopReason()).append("\n");
            Files.writeString(REPORT_DIR.resolve("v2-comparison.md"), md, StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { throw new IllegalStateException("V2_REPORT_WRITE_FAILED", error); }
    }

    private static double rate(List<CaseResult> cases, Predicate<CaseResult> test) {
        return cases.isEmpty() ? 0 : cases.stream().filter(test).count() / (double) cases.size();
    }

    private static String datasetSha256() {
        try (var input = V2OfflineReport.class.getClassLoader().getResourceAsStream("after-sales-eval.jsonl")) {
            if (input == null) throw new IllegalStateException("EVAL_DATASET_MISSING");
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.readAllBytes()));
        } catch (java.io.IOException | java.security.NoSuchAlgorithmException error) {
            throw new IllegalStateException("EVAL_FINGERPRINT_FAILED", error);
        }
    }
}
