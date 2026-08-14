package com.ecommerce.aftersales.eval.live;

import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.CaseResult;
import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.LatencyPercentiles;
import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.Metrics;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Offline unit tests for Live Eval metric math (no API calls).
 */
class LiveEvalMetricsTest {

    @Test
    void percentilesUseOfflineHarnessFormula() {
        assertThat(LiveAfterSalesEvalHarness.percentile(new double[]{10, 20, 30, 40}, 50)).isEqualTo(20);
        assertThat(LiveAfterSalesEvalHarness.percentile(new double[]{10, 20, 30, 40}, 95)).isEqualTo(40);
        assertThat(LiveAfterSalesEvalHarness.percentile(new double[]{5}, 50)).isEqualTo(5);
        assertThat(LiveAfterSalesEvalHarness.percentile(new double[]{5}, 95)).isEqualTo(5);
        assertThat(LiveAfterSalesEvalHarness.percentile(new double[]{1, 2}, 50)).isEqualTo(1);
        assertThat(LiveAfterSalesEvalHarness.percentile(new double[]{1, 2}, 95)).isEqualTo(2);
        assertThat(LiveAfterSalesEvalHarness.percentile(new double[]{}, 50)).isZero();
    }

    @Test
    void latencyPercentilesComputeMean() {
        LatencyPercentiles p = LiveAfterSalesEvalHarness.latencyPercentiles(new double[]{100, 200, 300});
        assertThat(p.p50()).isEqualTo(200);
        assertThat(p.p95()).isEqualTo(300);
        assertThat(p.mean()).isEqualTo(200);
    }

    @Test
    void computeMetricsAggregatesAcrossCases() {
        // accepted：3 次；model failures：1 次（LLM_INVALID_JSON 服务内降级）；gate rejection：1 次。
        CaseResult good = caseResult("a", List.of("TRACK_SHIPMENT"), "ANSWER_ONLY",
                List.of("TRACK_SHIPMENT"), "ANSWER_ONLY", "LLM", null,
                3, 0, 0, 0, 0, 3, 40, true, 100, 200);
        CaseResult degraded = caseResult("b", List.of("TRACK_SHIPMENT", "REQUEST_REFUND"), "COMPENSATION_EVALUATION",
                List.of("TRACK_SHIPMENT"), "ANSWER_ONLY", "RULE_FALLBACK", "LLM_INVALID_JSON",
                3, 1, 1, 0, 1, 2, 30, true, 90, 180);
        CaseResult gateRejected = caseResult("c", List.of("TRACK_SHIPMENT"), "ANSWER_ONLY",
                List.of("TRACK_SHIPMENT"), "ANSWER_ONLY", "LLM", null,
                3, 0, 1, 1, 1, 2, 25, true, 80, 170);
        Metrics m = LiveAfterSalesEvalHarness.computeMetrics(List.of(good, degraded, gateRejected));

        assertThat(m.caseCount()).isEqualTo(3);
        assertThat(m.intakeIntentAccuracy()).isCloseTo(2.0 / 3.0, within(1e-9));
        // 三个 case 中两个 route 正确（b 的 COMPENSATION_EVALUATION 被误判为 ANSWER_ONLY）。
        assertThat(m.routeAccuracy()).isCloseTo(2.0 / 3.0, within(1e-9));
        // attempts=9；accepted=3+2+2=7；invalid=modelFailures(1)+gateRejections(1)=2；fallbacks=2
        assertThat(m.plannerAttempts()).isEqualTo(9);
        assertThat(m.acceptedNonFallbackPlans()).isEqualTo(7);
        assertThat(m.invalidPlans()).isEqualTo(2);
        assertThat(m.plannerModelFailures()).isEqualTo(1);
        assertThat(m.plannerGateRejections()).isEqualTo(1);
        assertThat(m.fallbackCycles()).isEqualTo(2);
        assertThat(m.plannerValidRate()).isCloseTo(7.0 / 9.0, within(1e-9));
        assertThat(m.plannerInvalidPlanRate()).isCloseTo(2.0 / 9.0, within(1e-9));
        assertThat(m.plannerFallbackRate()).isCloseTo(2.0 / 9.0, within(1e-9));
        assertThat(m.averagePlannerCalls()).isEqualTo(3.0);
        assertThat(m.averageToolCalls()).isCloseTo(7.0 / 3.0, within(1e-9));
        assertThat(m.completionRate()).isEqualTo(1.0);
    }

    @Test
    void allInvalidRunReportsRatesHonestly() {
        // 100% invalid：模型输出全部不可用（或被 Gate 全部拒绝）→ Invalid/Fallback 必须报 1.0、
        // Valid 报 0.0，而不是因「无效计划数=0」误报 0。
        CaseResult a = caseResult("a", List.of("TRACK_SHIPMENT"), "ANSWER_ONLY",
                List.of("TRACK_SHIPMENT"), "ANSWER_ONLY", "RULE_FALLBACK", "LLM_INVALID_JSON",
                3, 3, 3, 0, 3, 3, 30, true, 90, 150);
        CaseResult b = caseResult("b", List.of("TRACK_SHIPMENT"), "ANSWER_ONLY",
                List.of("TRACK_SHIPMENT"), "ANSWER_ONLY", "LLM", null,
                2, 1, 2, 1, 2, 1, 20, true, 80, 140);
        Metrics m = LiveAfterSalesEvalHarness.computeMetrics(List.of(a, b));

        assertThat(m.plannerAttempts()).isEqualTo(5);
        assertThat(m.acceptedNonFallbackPlans()).isZero();
        assertThat(m.invalidPlans()).isEqualTo(5);
        assertThat(m.plannerValidRate()).isZero();
        assertThat(m.plannerInvalidPlanRate()).isEqualTo(1.0);
        assertThat(m.plannerFallbackRate()).isEqualTo(1.0);
        // 兜底计划仍可推进循环（可过 Gate），Completion 照常报告。
        assertThat(m.completionRate()).isEqualTo(1.0);
    }

    @Test
    void computeMetricsHandlesEmptyInput() {
        Metrics m = LiveAfterSalesEvalHarness.computeMetrics(List.of());
        assertThat(m.caseCount()).isZero();
        assertThat(m.completionRate()).isZero();
        assertThat(m.intakeIntentAccuracy()).isEqualTo(1.0);
        assertThat(m.routeAccuracy()).isEqualTo(1.0);
        assertThat(m.plannerAttempts()).isZero();
        assertThat(m.acceptedNonFallbackPlans()).isZero();
        // 无 attempt（无数据）时 Rate 一律 0.0，不假装 100%。
        assertThat(m.plannerValidRate()).isZero();
        assertThat(m.plannerInvalidPlanRate()).isZero();
        assertThat(m.plannerFallbackRate()).isZero();
        assertThat(m.averagePlannerCalls()).isZero();
        assertThat(m.intakeLatencyMs().p50()).isZero();
    }

    @Test
    void structuralSafetyDeclaresNoSideEffectCapabilitiesByConstruction() {
        CaseResult r = caseResult("a", List.of("TRACK_SHIPMENT"), "ANSWER_ONLY",
                List.of("TRACK_SHIPMENT"), "ANSWER_ONLY", "LLM", null,
                2, 0, 0, 0, 0, 2, 20, true, 50, 100);
        var safety = LiveAfterSalesEvalHarness.computeStructuralSafety(List.of(r));
        // 结构性能力声明，不是观测零：harness 未装配任何审批/执行/外部工具组件，
        // 副作用不可能发生是 by construction 的结构事实 —— 不存在 sideEffectsObserved 观测字段。
        assertThat(safety.sideEffectCapabilitiesPresent()).isFalse();
        assertThat(safety.simulatedEvidenceLookups()).isEqualTo(2);
    }

    private static CaseResult caseResult(
            String id,
            List<String> expectedIntents,
            String expectedRoute,
            List<String> intents,
            String route,
            String intakeSource,
            String intakeFallbackReason,
            int plannerCalls,
            int plannerModelFailures,
            int plannerFallbacks,
            int plannerGateRejections,
            int plannerInvalidPlans,
            int simulatedToolCalls,
            long plannerLatencyCumulativeMs,
            boolean completed,
            long intakeLatencyMs,
            long endToEndLatencyMs) {
        return new CaseResult(
                id, "tracking", expectedIntents, expectedRoute, intents, route,
                intakeSource, intakeFallbackReason, intakeLatencyMs,
                plannerCalls, plannerModelFailures, plannerFallbacks, plannerGateRejections,
                plannerInvalidPlans, simulatedToolCalls,
                plannerLatencyCumulativeMs, completed,
                completed ? "COMPLETED" : "PLANNER_NO_PROGRESS",
                endToEndLatencyMs);
    }
}
