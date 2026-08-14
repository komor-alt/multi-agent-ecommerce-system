package com.ecommerce.aftersales.eval.live;

import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.CaseResult;
import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.EvalReport;
import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.LiveEvalCase;
import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.Metrics;
import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.StructuralSafety;
import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.UsageUnavailable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Offline unit tests for Live Eval report sanitization/redaction (no API calls).
 */
class LiveEvalReportTest {

    @Test
    void sanitizeBaseUrlStripsCredentialsQueryAndFragment() {
        assertThat(LiveAfterSalesEvalHarness.sanitizeBaseUrl("https://api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(LiveAfterSalesEvalHarness.sanitizeBaseUrl("https://user:pass@api.deepseek.com/v1?key=abc#frag"))
                .isEqualTo("https://api.deepseek.com/v1");
        assertThat(LiveAfterSalesEvalHarness.sanitizeBaseUrl("api.deepseek.com"))
                .isEqualTo("https://api.deepseek.com");
        assertThat(LiveAfterSalesEvalHarness.sanitizeBaseUrl("https://api.deepseek.com:8443"))
                .isEqualTo("https://api.deepseek.com:8443");
        assertThat(LiveAfterSalesEvalHarness.sanitizeBaseUrl("  https://api.deepseek.com  "))
                .isEqualTo("https://api.deepseek.com");
        assertThat(LiveAfterSalesEvalHarness.sanitizeBaseUrl(null)).isNull();
        assertThat(LiveAfterSalesEvalHarness.sanitizeBaseUrl("   ")).isNull();
    }

    @Test
    void reportJsonNeverContainsCustomerMessagesPromptsOrSecrets() throws Exception {
        List<LiveEvalCase> cases = LiveAfterSalesEvalHarness.loadCases();
        List<CaseResult> results = cases.stream()
                .map(c -> new CaseResult(
                        c.id(), c.category(), c.expectedIntents(), c.expectedRoute(),
                        c.expectedIntents(), c.expectedRoute(), "LLM", null,
                        10, 10, 2, 2, 0, 2, 8, 20, true, "COMPLETED", 30))
                .toList();
        EvalReport report = new EvalReport(
                "2026-08-14T00:00:00Z",
                LiveAfterSalesEvalHarness.sanitizeBaseUrl("https://user:pass@api.deepseek.com"),
                "deepseek-v4-flash",
                "LLM",
                results.size(),
                new UsageUnavailable(true, LiveAfterSalesEvalHarness.USAGE_UNAVAILABLE_REASON),
                LiveAfterSalesEvalHarness.computeMetrics(results),
                new StructuralSafety(false, 0),
                results);

        String json = new ObjectMapper().writeValueAsString(report);

        // 报告结构完整。
        assertThat(json).contains("generatedAt").contains("caseCount").contains("metrics");
        // 不泄露任何客户消息原文。
        for (LiveEvalCase c : cases) {
            assertThat(json).as("case %s message must never appear in the report", c.id())
                    .doesNotContain(c.message());
        }
        // 不包含完整 prompt / 模型输出 / CoT / key / 认证头。
        assertThat(json)
                .doesNotContain("INTAKE RESULT")
                .doesNotContain("SERVER EVIDENCE PRESENCE")
                .doesNotContain("Respond with exactly one JSON object")
                .doesNotContain("apiKey")
                .doesNotContain("api-key")
                .doesNotContain("Authorization")
                .doesNotContain("user:pass")
                // 结构性安全是能力声明（sideEffectCapabilitiesPresent），不再出现伪造的观测零字段。
                .doesNotContain("\"approvals\"")
                .doesNotContain("\"executionJobs\"")
                .doesNotContain("\"unauthorizedActionRate\"");
    }

    @Test
    void markdownReportDocumentsTokenMetricsAsUnavailable() {
        List<LiveEvalCase> cases = LiveAfterSalesEvalHarness.loadCases();
        List<CaseResult> results = cases.stream()
                .map(c -> new CaseResult(
                        c.id(), c.category(), c.expectedIntents(), c.expectedRoute(),
                        c.expectedIntents(), c.expectedRoute(), "LLM", null,
                        1, 1, 0, 0, 0, 0, 1, 10, true, "COMPLETED", 15))
                .toList();
        Metrics metrics = LiveAfterSalesEvalHarness.computeMetrics(results);
        EvalReport report = new EvalReport(
                "2026-08-14T00:00:00Z", "https://api.deepseek.com", "deepseek-v4-flash", "LLM",
                results.size(),
                new UsageUnavailable(true, LiveAfterSalesEvalHarness.USAGE_UNAVAILABLE_REASON),
                metrics, new StructuralSafety(false, 0), results);

        String md = LiveAfterSalesEvalHarness.markdownReport(report);
        assertThat(md).contains("Live LLM Evaluation")
                .contains("Intake Intent Accuracy")
                // Planner 延迟明确标注 per-case cumulative（多轮调用累计，避免误导）。
                .contains("per-case cumulative")
                // 结构性安全是 by construction 的能力声明，不是观测零。
                .contains("by construction")
                .contains("never estimated")
                .contains("sanitized")
                .doesNotContain("apiKey")
                .doesNotContain("Authorization")
                .doesNotContain("unauthorizedActionRate");
    }
}
