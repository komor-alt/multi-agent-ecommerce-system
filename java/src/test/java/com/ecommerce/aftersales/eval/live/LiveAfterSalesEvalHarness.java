package com.ecommerce.aftersales.eval.live;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;
import com.ecommerce.aftersales.model.AfterSalesTypes.IntakeResult;
import com.ecommerce.aftersales.model.AfterSalesTypes.PlanningResult;
import com.ecommerce.aftersales.model.DecisionRoute;
import com.ecommerce.aftersales.service.AfterSalesEvidencePlannerService;
import com.ecommerce.aftersales.service.AfterSalesEvidencePlannerService.PlanningInput;
import com.ecommerce.aftersales.service.AfterSalesIntakeService;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.aftersales.service.DecisionRouteResolver;
import com.ecommerce.aftersales.service.EvidencePreconditionGate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Optional Live LLM Evaluation harness — completely separate from the offline
 * {@code AfterSalesEvalHarness} (java/target/after-sales-eval/). It measures how
 * well a REAL configured model understands customers and plans evidence.
 *
 * Pipeline per case (stops before any side effect):
 *   customer message
 *   → real LLM AfterSalesIntakeService(mode=LLM)
 *   → DecisionRouteResolver (server-side, model can never emit a route)
 *   → real LLM AfterSalesEvidencePlannerService(mode=LLM) cycles
 *   → EvidencePreconditionGate
 *   → simulate accepted read-only evidence presence
 *
 * The planner loop mirrors production AfterSalesAgentLoopService semantics.
 * Every plan() attempt is exactly one of four outcomes:
 * - accepted non-fallback plan: LLM output parsed AND passed the gate;
 * - model output failure: invalid JSON/output, timeout, empty response, busy —
 *   degraded to the rules plan inside the service (counted invalid);
 * - gate rejection: LLM output parsed but failed EvidencePreconditionGate
 *   (counted invalid, replaced with deterministicPlan, no re-call of the model);
 * - invalid input: fail-closed input defect, defensive only.
 * Accepted fallback plans must pass the SAME gate; a fallback that still fails
 * is a fail-closed stop (PLANNER_NO_PROGRESS); cycles are bounded (MAX_STEPS)
 * and no-progress fails closed.
 *
 * Side-effect boundary: nothing is approved, no execution jobs are created and
 * no external commerce tool is ever called — evidence presence is simulated as
 * accepted read-only evidence. Never override callModel, never mock ChatClient,
 * never inject predefined/adaptive model outputs: the configured model decides.
 *
 * Reports (machine-readable JSON + Markdown) are written to
 * java/target/after-sales-live-eval/ and never contain customer messages, full
 * prompts, raw model outputs, chain-of-thought, API keys or auth headers.
 */
public final class LiveAfterSalesEvalHarness {

    private static final Logger log = LoggerFactory.getLogger(LiveAfterSalesEvalHarness.class);

    private static final String RESOURCE_NAME = "after-sales-live-eval.jsonl";
    public static final int MIN_CASES = 20;
    public static final int MAX_CASES = 30;
    /** Mirrors the production run budget (maxSteps=8) for the evidence loop. */
    private static final int MAX_STEPS = 8;

    public static final Path REPORT_DIR =
            Path.of(System.getProperty("user.dir"), "target", "after-sales-live-eval");

    private final AfterSalesIntakeService intakeService;
    private final AfterSalesEvidencePlannerService plannerService;
    private final DecisionRouteResolver routeResolver = new DecisionRouteResolver();
    private final EvidencePreconditionGate preconditionGate = new EvidencePreconditionGate();
    private final ObjectMapper mapper;
    private final int maxLlmCalls;

    public LiveAfterSalesEvalHarness(
            AfterSalesIntakeService intakeService,
            AfterSalesEvidencePlannerService plannerService,
            ObjectMapper mapper) {
        this(intakeService, plannerService, mapper, 0);
    }

    public LiveAfterSalesEvalHarness(
            AfterSalesIntakeService intakeService,
            AfterSalesEvidencePlannerService plannerService,
            ObjectMapper mapper,
            int maxLlmCalls) {
        this.intakeService = intakeService;
        this.plannerService = plannerService;
        this.mapper = mapper;
        this.maxLlmCalls = Math.max(0, maxLlmCalls);
    }
    // ------------------------------------------------------------------
    // Case schema (live eval: no injected model outputs — the real model decides)
    // ------------------------------------------------------------------

    public record LiveEvalCase(
            String id,
            String category,
            String message,
            List<String> expectedIntents,
            String expectedRoute
    ) {

        public static LiveEvalCase from(JsonNode node) {
            return new LiveEvalCase(
                    text(node, "id"),
                    text(node, "category"),
                    text(node, "message"),
                    strings(node, "expectedIntents"),
                    textOrNull(node, "expectedRoute"));
        }
    }

    // ------------------------------------------------------------------
    // Per-case result (redacted: no message, no prompt, no raw model output)
    // ------------------------------------------------------------------

    public record CaseResult(
            String id,
            String category,
            List<String> expectedIntents,
            String expectedRoute,
            List<String> intents,
            String route,
            String intakeSource,
            String intakeFallbackReason,
            long intakeLatencyMs,
            int plannerCalls,
            int plannerModelFailures,
            int plannerFallbacks,
            int plannerGateRejections,
            int plannerInvalidPlans,
            int simulatedToolCalls,
            long plannerLatencyCumulativeMs,
            boolean completed,
            String outcome,
            long endToEndLatencyMs
    ) {

        public boolean intakeCorrect() {
            return expectedIntents() != null && expectedIntents().equals(intents());
        }

        public boolean routeCorrect() {
            return expectedRoute() != null && expectedRoute().equals(route());
        }
    }

    // ------------------------------------------------------------------
    // Aggregate metrics
    // ------------------------------------------------------------------

    public record LatencyPercentiles(double p50, double p95, double mean) {
    }

    /**
     * Planner 指标（诚实分母约定）：每个 case 的每次 plan() 调用计为一次 raw planner attempt；
     * 每次 attempt 恰好是四类之一 —— accepted non-fallback plan（LLM 输出解析成功且过 Gate，
     * 含 READY 终结）、model output failure（解析失败/超时/空响应/繁忙等，服务内降级）、
     * gate rejection（解析成功但违反业务前置）、invalid input（防御性输入缺陷，fail-closed）。
     * 三个 Rate 的分母统一为 plannerAttempts（总 attempts），分子彼此无重叠且都 ≤ 分母，
     * 因此任何 Rate 都不可能 > 1；attempts=0（无数据）时 Rate 一律 0.0，100% invalid 场景
     * 正确报告 Invalid=1.0 而非 0。fallbackCycles 与 invalidPlans 语义不同（前者是使用了
     * 兜底计划的轮次，后者是不可用/被拒的计划），在本 harness 中一一对应。
     */
    public record Metrics(
            int caseCount,
            double intakeIntentAccuracy,
            double routeAccuracy,
            long plannerAttempts,
            long acceptedNonFallbackPlans,
            long invalidPlans,
            long plannerModelFailures,
            long plannerGateRejections,
            long fallbackCycles,
            double plannerValidRate,
            double plannerInvalidPlanRate,
            double plannerFallbackRate,
            double averagePlannerCalls,
            double averageToolCalls,
            double completionRate,
            LatencyPercentiles intakeLatencyMs,
            LatencyPercentiles plannerLatencyCumulativeMs,
            LatencyPercentiles endToEndLatencyMs
    ) {
    }

    /**
     * 结构性安全声明（结构性能力声明，不是观测指标）：
     * - sideEffectCapabilitiesPresent=false —— 本 harness 没有实例化任何审批 / 执行任务 /
     *   外部业务工具组件，副作用「不可能发生」是 by construction 的结构事实，不是观测到的零，
     *   绝不暗示真实调用了副作用 API；
     * - simulatedEvidenceLookups —— 取证在场模拟次数（只读模拟，非真实工具执行）；
     * 刻意不设 sideEffectsObserved 字段：副作用是否发生不是可观测指标，能力缺失
     * （by construction）是唯一可硬性校验的结构声明。
     */
    public record StructuralSafety(
            boolean sideEffectCapabilitiesPresent,
            long simulatedEvidenceLookups
    ) {
    }

    /** Token/cost metrics: unavailable from the current service interfaces, never estimated. */
    public record UsageUnavailable(
            boolean unavailable,
            String reason,
            int llmCallCount,
            int maxLlmCalls,
            Integer promptTokens,
            Integer completionTokens,
            Double estimatedCost) {
        public UsageUnavailable(boolean unavailable, String reason) {
            this(unavailable, reason, 0, 0, null, null, null);
        }
    }
    public record EvalReport(
            String generatedAt,
            String baseUrl,
            String model,
            String mode,
            int caseCount,
            UsageUnavailable usage,
            Metrics metrics,
            StructuralSafety structuralSafety,
            List<CaseResult> cases
    ) {
    }

    // ------------------------------------------------------------------
    // Entry point
    // ------------------------------------------------------------------

    /** Runs every case against the REAL configured services and writes the reports. */
    public EvalReport run(String baseUrl, String model) {
        List<LiveEvalCase> cases = loadCases();
        LlmCallBudget budget = new LlmCallBudget(maxLlmCalls);
        List<CaseResult> results;
        try (LlmCallBudget.Scope ignored = LlmCallBudget.bind(budget)) {
            results = cases.stream().map(this::runCase).toList();
        }
        Metrics metrics = computeMetrics(results);
        StructuralSafety safety = computeStructuralSafety(results);
        EvalReport report = new EvalReport(
                Instant.now().toString(),
                sanitizeBaseUrl(baseUrl),
                model,
                "LLM",
                cases.size(),
                new UsageUnavailable(true, USAGE_UNAVAILABLE_REASON, budget.getCallCount(), budget.getMaxCalls(), null, null, null),
                metrics,
                safety,
                results);
        writeReports(report);
        printSummary(report);
        return report;
    }

    static final String USAGE_UNAVAILABLE_REASON =
            "Token/cost metrics are unavailable: AfterSalesIntakeService and "
                    + "AfterSalesEvidencePlannerService expose only structured results "
                    + "(IntakeResult/PlanningResult) and never raw ChatResponse usage metadata. "
                    + "Reported as unavailable, never estimated.";

    // ------------------------------------------------------------------
    // Case execution: real LLM Intake -> route -> real LLM Planner -> gate,
    // simulating accepted read-only evidence presence. No side effects.
    // ------------------------------------------------------------------

    private CaseResult runCase(LiveEvalCase c) {
        long startedNanos = System.nanoTime();

        // Real LLM Intake (mode=LLM; the service falls back to rules on model
        // failure — that fallback is part of what the live eval measures).
        IntakeResult intake = intakeService.classify(c.message());

        // Decision route: deterministic, server-side; model can never emit a route.
        DecisionRouteResolver.RouteDecision routeDecision = routeResolver.resolve(intake);
        intake = intake.withRequiredEvidence(routeDecision.requiredEvidence());

        // Evidence planning loop, mirroring production AfterSalesAgentLoopService.
        // 每轮 plan() 恰好产生四类结果之一（见 Metrics 文档）：
        //   accepted non-fallback  — LLM 输出解析成功且过 Gate（含 READY 终结）；
        //   model output failure   — 解析失败/超时/空响应/繁忙等，服务内已降级为规则兜底；
        //   gate rejection         — LLM 输出解析成功但违反业务前置，替换为 deterministicPlan；
        //   invalid input          — 防御性输入缺陷，fail-closed（Live Eval 实际不会触发）。
        Map<String, Boolean> presence = freshPresence();
        int plannerCalls = 0;
        int plannerModelFailures = 0;
        int plannerGateRejections = 0;
        int plannerFallbacks = 0;
        int simulatedToolCalls = 0;
        long plannerLatencyCumulativeMs = 0;
        String outcome = "COMPLETED";
        int step = 0;
        while (true) {
            if (step >= MAX_STEPS) {
                outcome = "MAX_STEPS_EXCEEDED";
                break;
            }
            PlanningInput input = new PlanningInput(intake, presence);
            PlanningResult plan = plannerService.plan(input);
            plannerCalls++;
            // per-case cumulative：同一 case 多轮 planner 调用的耗时总和（兜底计划 latencyMs=0）。
            plannerLatencyCumulativeMs += plan.latencyMs();
            if (plan.invalidInput()) {
                // Fail-closed input defect（防御性；Live Eval 的 requiredEvidence 由
                // DecisionRouteResolver 重建、始终合法）：既不是有效计划，也不是模型失败。
                outcome = "PLANNER_INVALID_REQUIRED_EVIDENCE";
                break;
            }
            boolean modelFailed = isDegradedPlan(plan);
            boolean gateRejected = !modelFailed && !passesGate(plan, intake, presence);
            if (gateRejected) {
                // LLM plan is formally valid but violates business preconditions:
                // count invalid and fall back to the deterministic plan (no re-call).
                plannerGateRejections++;
                plannerFallbacks++;
                plan = plannerService.deterministicPlan(input, "LLM_INVALID_PLAN");
            } else if (modelFailed) {
                // LLM attempt produced no usable plan inside the service
                // (invalid JSON/output, timeout, empty response, busy, error).
                plannerModelFailures++;
                plannerFallbacks++;
            }
            if (!passesGate(plan, intake, presence)) {
                // Fallback (or rule) plan still cannot advance: fail closed.
                outcome = "PLANNER_NO_PROGRESS";
                break;
            }
            if (plan.nextEvidence() == EvidenceType.READY_FOR_DECISION) {
                break;
            }
            // Simulate accepted read-only evidence presence: no tool is executed.
            presence.put(plan.nextEvidence().name(), true);
            simulatedToolCalls++;
            step++;
        }

        long endToEndMs = (System.nanoTime() - startedNanos) / 1_000_000;
        boolean completed = "COMPLETED".equals(outcome);
        return new CaseResult(
                c.id(),
                c.category(),
                c.expectedIntents(),
                c.expectedRoute(),
                intake.intents(),
                routeDecision.route().name(),
                intake.source(),
                intake.fallbackReason(),
                intake.latencyMs(),
                plannerCalls,
                plannerModelFailures,
                plannerFallbacks,
                plannerGateRejections,
                // 真实 invalid 计划数：模型输出无效的降级 + Gate 拒绝（二者互斥，逐轮至多其一）。
                plannerModelFailures + plannerGateRejections,
                simulatedToolCalls,
                plannerLatencyCumulativeMs,
                completed,
                outcome,
                endToEndMs);
    }

    /** 业务前置校验（EvidencePreconditionGate）：与生产 Agent 循环同一组件、同一语义。 */
    private boolean passesGate(PlanningResult plan, IntakeResult intake, Map<String, Boolean> presence) {
        return preconditionGate.validate(
                plan.nextEvidence(), intake.requiredEvidence(), presence).passed();
    }

    /** 降级判定：与生产 AfterSalesAgentLoopService.isDegradedPlan 一致。 */
    private static boolean isDegradedPlan(PlanningResult plan) {
        return "RULE_FALLBACK".equals(plan.source())
                && plan.fallbackReason() != null
                && !"RULES_MODE".equals(plan.fallbackReason());
    }

    private static Map<String, Boolean> freshPresence() {
        Map<String, Boolean> presence = new LinkedHashMap<>();
        presence.put(EvidenceType.ORDER.name(), false);
        presence.put(EvidenceType.SHIPMENT.name(), false);
        presence.put(EvidenceType.CARRIER_CASE.name(), false);
        presence.put(EvidenceType.DELIVERY.name(), false);
        presence.put(EvidenceType.DAMAGE_PHOTO.name(), false);
        presence.put(EvidenceType.PRODUCT.name(), false);
        presence.put(EvidenceType.POLICY.name(), false);
        return presence;
    }

    // ------------------------------------------------------------------
    // Metrics
    // ------------------------------------------------------------------

    public static Metrics computeMetrics(List<CaseResult> results) {
        int n = results.size();
        long intentExpected = results.stream().filter(r -> r.expectedIntents() != null).count();
        long intentCorrect = results.stream().filter(CaseResult::intakeCorrect).count();
        long routeExpected = results.stream().filter(r -> r.expectedRoute() != null).count();
        long routeCorrect = results.stream().filter(CaseResult::routeCorrect).count();
        long attempts = results.stream().mapToLong(CaseResult::plannerCalls).sum();
        long modelFailures = results.stream().mapToLong(CaseResult::plannerModelFailures).sum();
        long gateRejections = results.stream().mapToLong(CaseResult::plannerGateRejections).sum();
        long invalid = results.stream().mapToLong(CaseResult::plannerInvalidPlans).sum();
        long fallbacks = results.stream().mapToLong(CaseResult::plannerFallbacks).sum();
        long invalidInputStops = results.stream()
                .filter(r -> "PLANNER_INVALID_REQUIRED_EVIDENCE".equals(r.outcome())).count();
        // 不变式：每次 attempt 恰为 {accepted, model failure, gate rejection, invalid input} 之一；
        // per-case plannerInvalidPlans = modelFailures + gateRejections（本 harness 一一对应）。
        long accepted = attempts - invalid - invalidInputStops;
        long toolCalls = results.stream().mapToLong(CaseResult::simulatedToolCalls).sum();
        long completed = results.stream().filter(CaseResult::completed).count();

        return new Metrics(
                n,
                intentExpected == 0 ? 1.0 : intentCorrect / (double) intentExpected,
                routeExpected == 0 ? 1.0 : routeCorrect / (double) routeExpected,
                attempts,
                accepted,
                invalid,
                modelFailures,
                gateRejections,
                fallbacks,
                attempts == 0 ? 0.0 : accepted / (double) attempts,
                attempts == 0 ? 0.0 : invalid / (double) attempts,
                attempts == 0 ? 0.0 : fallbacks / (double) attempts,
                n == 0 ? 0.0 : attempts / (double) n,
                n == 0 ? 0.0 : toolCalls / (double) n,
                n == 0 ? 0.0 : completed / (double) n,
                latencyPercentiles(results.stream().mapToDouble(CaseResult::intakeLatencyMs).sorted().toArray()),
                latencyPercentiles(results.stream().mapToDouble(CaseResult::plannerLatencyCumulativeMs).sorted().toArray()),
                latencyPercentiles(results.stream().mapToDouble(CaseResult::endToEndLatencyMs).sorted().toArray()));
    }

    public static LatencyPercentiles latencyPercentiles(double[] sorted) {
        if (sorted.length == 0) {
            return new LatencyPercentiles(0, 0, 0);
        }
        double mean = java.util.Arrays.stream(sorted).average().orElse(0);
        return new LatencyPercentiles(
                percentile(sorted, 50),
                percentile(sorted, 95),
                mean);
    }

    public static double percentile(double[] sorted, int p) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
    }

    /**
     * 结构性安全（Live Eval 唯一允许硬性校验的指标）：本次运行零副作用。
     * 注意：零审批/零执行任务是结构性事实，不是观测值 —— 本 harness 从未装配
     * 审批/执行任务/外部工具组件，副作用 by construction 不可能发生；
     * 因此只声明能力缺失（sideEffectCapabilitiesPresent=false），不存在
     * sideEffectsObserved 观测字段。
     */
    public static StructuralSafety computeStructuralSafety(List<CaseResult> results) {
        long lookups = results.stream().mapToLong(CaseResult::simulatedToolCalls).sum();
        return new StructuralSafety(false, lookups);
    }

    // ------------------------------------------------------------------
    // Report sanitization
    // ------------------------------------------------------------------

    /**
     * 清洗 baseUrl：去掉 userinfo（凭证）、query、fragment，只保留 scheme+host+port+path。
     * 报告永不包含 API key 或 Authorization header。
     */
    public static String sanitizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        try {
            URI uri = URI.create(trimmed.contains("://") ? trimmed : "https://" + trimmed);
            if (uri.getHost() == null) {
                return stripSensitive(trimmed);
            }
            String scheme = uri.getScheme() == null ? "https" : uri.getScheme().toLowerCase(Locale.ROOT);
            String port = uri.getPort() > 0 ? ":" + uri.getPort() : "";
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "" : uri.getRawPath();
            return scheme + "://" + uri.getHost() + port + path;
        } catch (IllegalArgumentException error) {
            return stripSensitive(trimmed);
        }
    }

    private static String stripSensitive(String value) {
        String cleaned = value;
        int at = cleaned.lastIndexOf('@');
        if (at >= 0) {
            cleaned = cleaned.substring(at + 1);
        }
        int query = cleaned.indexOf('?');
        if (query >= 0) {
            cleaned = cleaned.substring(0, query);
        }
        int fragment = cleaned.indexOf('#');
        if (fragment >= 0) {
            cleaned = cleaned.substring(0, fragment);
        }
        return cleaned;
    }

    // ------------------------------------------------------------------
    // Reports (separate dir: java/target/after-sales-live-eval/)
    // ------------------------------------------------------------------

    private void writeReports(EvalReport report) {
        try {
            Files.createDirectories(REPORT_DIR);
            Files.writeString(REPORT_DIR.resolve("live-eval-report.json"),
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report),
                    StandardCharsets.UTF_8);
            Files.writeString(REPORT_DIR.resolve("live-eval-report.md"),
                    markdownReport(report),
                    StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("LIVE_EVAL_REPORT_WRITE_FAILED", error);
        }
    }

    static String markdownReport(EvalReport report) {
        Metrics m = report.metrics();
        StringBuilder md = new StringBuilder();
        md.append("# After-Sales Live LLM Evaluation Report\n\n");
        md.append("- Generated at: `").append(report.generatedAt()).append("`\n");
        md.append("- Mode: live (real configured model, external API, may cost money)\n");
        md.append("- Base URL: `").append(report.baseUrl()).append("` (sanitized)\n");
        md.append("- Model: `").append(report.model()).append("`\n");
        md.append("- Mode (intake/planner): `").append(report.mode()).append("`\n");
        md.append("- Cases: ").append(report.caseCount()).append("\n");
        md.append("- Token/cost metrics: unavailable (current service interfaces expose no usage; never estimated)\n");
        md.append("- Artifacts: `java/target/after-sales-live-eval/live-eval-report.json` (machine-readable)\n\n");

        md.append("## Metrics\n\n");
        md.append("| Metric | Value |\n|---|---|\n");
        md.append("| Case Count | ").append(m.caseCount()).append(" |\n");
        md.append("| Intake Intent Accuracy | ").append(pct(m.intakeIntentAccuracy())).append(" |\n");
        md.append("| Route Accuracy | ").append(pct(m.routeAccuracy())).append(" |\n");
        md.append("| Planner Attempts (raw) | ").append(m.plannerAttempts()).append(" |\n");
        md.append("| Accepted Non-Fallback Plans | ").append(m.acceptedNonFallbackPlans()).append(" |\n");
        md.append("| Invalid Plans (model output failures + gate rejections) | ").append(m.invalidPlans())
                .append(" (").append(m.plannerModelFailures()).append(" + ").append(m.plannerGateRejections()).append(") |\n");
        md.append("| Fallback Cycles | ").append(m.fallbackCycles()).append(" |\n");
        md.append("| Planner Valid Rate (accepted / attempts) | ").append(pct(m.plannerValidRate())).append(" |\n");
        md.append("| Planner Invalid Plan Rate (invalid / attempts) | ").append(pct(m.plannerInvalidPlanRate())).append(" |\n");
        md.append("| Planner Fallback Rate (fallbacks / attempts) | ").append(pct(m.plannerFallbackRate())).append(" |\n");
        md.append("| Average Planner Calls | ").append(fmt(m.averagePlannerCalls())).append(" |\n");
        md.append("| Average Tool Calls (simulated read-only evidence) | ")
                .append(fmt(m.averageToolCalls())).append(" |\n");
        md.append("| Completion Rate | ").append(pct(m.completionRate())).append(" |\n");
        md.append("| Intake Latency p50 / p95 (ms) | ")
                .append(fmt(m.intakeLatencyMs().p50())).append(" / ").append(fmt(m.intakeLatencyMs().p95())).append(" |\n");
        md.append("| Planner Latency p50 / p95 (ms, per-case cumulative across planner calls) | ")
                .append(fmt(m.plannerLatencyCumulativeMs().p50())).append(" / ").append(fmt(m.plannerLatencyCumulativeMs().p95())).append(" |\n");
        md.append("| End-to-End Latency p50 / p95 (ms) | ")
                .append(fmt(m.endToEndLatencyMs().p50())).append(" / ").append(fmt(m.endToEndLatencyMs().p95())).append(" |\n\n");

        md.append("## Structural Safety\n\n");
        md.append("- Side-effect capabilities present: **no** (by construction — the eval harness instantiates ")
                .append("no approval, execution-job or commerce-tool component; nothing in this pipeline can ")
                .append("produce a side effect, so zero is a structural fact, not an observed zero)\n");
        md.append("- Simulated evidence lookups (read-only presence simulation, not real tool execution): ")
                .append(report.structuralSafety().simulatedEvidenceLookups()).append("\n");
        md.append("- Side effects observed: not a metric — the harness cannot produce side effects by construction, ")
                .append("so no observed-zero field is reported\n\n");

        md.append("## Per-case Results\n\n");
        md.append("| Case | Category | Expected Intents | Intents | Expected Route | Route | Intake | Planner Calls | Model Failures | Fallbacks | Gate Rejections | Tool Calls | Outcome | IntakeMs | PlannerMs(cum) | E2EMs |\n");
        md.append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (CaseResult r : report.cases()) {
            md.append("| ").append(r.id())
                    .append(" | ").append(r.category())
                    .append(" | ").append(String.join(">", r.expectedIntents()))
                    .append(" | ").append(String.join(">", r.intents()))
                    .append(" | ").append(r.expectedRoute() == null ? "-" : r.expectedRoute())
                    .append(" | ").append(r.route() == null ? "-" : r.route())
                    .append(" | ").append(r.intakeSource())
                    .append(" | ").append(r.plannerCalls())
                    .append(" | ").append(r.plannerModelFailures())
                    .append(" | ").append(r.plannerFallbacks())
                    .append(" | ").append(r.plannerGateRejections())
                    .append(" | ").append(r.simulatedToolCalls())
                    .append(" | ").append(r.outcome())
                    .append(" | ").append(r.intakeLatencyMs())
                    .append(" | ").append(r.plannerLatencyCumulativeMs())
                    .append(" | ").append(r.endToEndLatencyMs())
                    .append(" |\n");
        }

        md.append("\n## Methodology\n\n");
        md.append("Each case runs the REAL configured model through the production components: intake\n")
                .append("classification (AfterSalesIntakeService mode=LLM), server-side decision route\n")
                .append("resolution (DecisionRouteResolver), evidence planning cycles (AfterSalesEvidence\n")
                .append("PlannerService mode=LLM) guarded by EvidencePreconditionGate — mirroring production\n")
                .append("planner semantics. Every plan() attempt is exactly one of: accepted non-fallback plan\n")
                .append("(LLM output parsed and passed the gate), model output failure (invalid JSON/output,\n")
                .append("timeout, empty response, busy — counted invalid), gate rejection (parsed but failed\n")
                .append("business preconditions — counted invalid, replaced with deterministicPlan, no re-call),\n")
                .append("or invalid input (fail-closed, defensive). All planner rates use raw planner attempts\n")
                .append("as denominator, so no rate can exceed 1 and a 100% invalid run reports Invalid=100%.\n")
                .append("Accepted fallback plans pass the same gate; bounded cycles and no-progress fail closed.\n")
                .append("Planner latency is per-case CUMULATIVE across all planner calls in the evidence loop.\n")
                .append("Evidence presence is SIMULATED as accepted read-only evidence: no approval, no\n")
                .append("execution job, no external commerce tool. This is a manual report, not a CI gate —\n")
                .append("accuracy is not a pass/fail condition. Token/cost metrics are unavailable from the\n")
                .append("current service interfaces and are never estimated.\n");
        return md.toString();
    }

    private static String pct(double value) {
        return String.format(Locale.ROOT, "%.2f%%", value * 100);
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private void printSummary(EvalReport report) {
        Metrics m = report.metrics();
        System.out.println();
        System.out.println("================================================================");
        System.out.println("After-Sales Live LLM Evaluation (real model: " + report.model() + ")");
        System.out.println("Cases: " + m.caseCount()
                + " | Intent accuracy: " + pct(m.intakeIntentAccuracy())
                + " | Route accuracy: " + pct(m.routeAccuracy())
                + " | Planner attempts: " + m.plannerAttempts()
                + " | Valid: " + pct(m.plannerValidRate())
                + " | Invalid: " + pct(m.plannerInvalidPlanRate())
                + " | Fallback: " + pct(m.plannerFallbackRate())
                + " | Completion: " + pct(m.completionRate()));
        System.out.println("Structural safety: no side-effect capabilities instantiated (by construction)"
                + " | simulated evidence lookups=" + report.structuralSafety().simulatedEvidenceLookups()
                + " | side effects observed: N/A (not an observed metric; by construction)");
        System.out.println("Reports: " + REPORT_DIR.toAbsolutePath());
        System.out.println("================================================================");
        System.out.println();
    }

    // ------------------------------------------------------------------
    // Case loading (20-30 cases, separate resource from the offline suite)
    // ------------------------------------------------------------------

    public static List<LiveEvalCase> loadCases() {
        try (InputStream in = LiveAfterSalesEvalHarness.class.getClassLoader().getResourceAsStream(RESOURCE_NAME)) {
            if (in == null) {
                throw new IllegalStateException("LIVE_EVAL_CASE_FILE_MISSING: " + RESOURCE_NAME);
            }
            return parseCaseLines(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException error) {
            throw new IllegalStateException("LIVE_EVAL_CASE_FILE_UNREADABLE", error);
        }
    }

    /** 解析 JSONL（跳过空行）；用例数必须在 [20, 30] 区间，越界即失败。 */
    public static List<LiveEvalCase> parseCaseLines(String content) {
        ObjectMapper parser = new ObjectMapper();
        List<LiveEvalCase> cases = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                cases.add(LiveEvalCase.from(parser.readTree(trimmed)));
            } catch (IOException error) {
                throw new IllegalStateException("LIVE_EVAL_CASE_PARSE_FAILED", error);
            }
        }
        if (cases.size() < MIN_CASES || cases.size() > MAX_CASES) {
            throw new IllegalStateException("LIVE_EVAL_CASE_COUNT_OUT_OF_RANGE: " + cases.size()
                    + " (expected " + MIN_CASES + "-" + MAX_CASES + ")");
        }
        return List.copyOf(cases);
    }

    // ------------------------------------------------------------------
    // JSONL field helpers
    // ------------------------------------------------------------------

    private static String text(JsonNode node, String field) {
        return node.path(field).asText("");
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static List<String> strings(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            return null;
        }
        List<String> result = new ArrayList<>();
        value.forEach(item -> result.add(item.asText()));
        return List.copyOf(result);
    }
}
