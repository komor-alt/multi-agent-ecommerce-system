package com.ecommerce.aftersales.eval.live;

import com.ecommerce.aftersales.eval.live.LiveAfterSalesEvalHarness.EvalReport;
import com.ecommerce.aftersales.service.AfterSalesEvidencePlannerService;
import com.ecommerce.aftersales.service.AfterSalesIntakeService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional Live LLM Eval runner (integration test, ONLY selected by the Maven
 * profile {@code -Plive-eval}; excluded from normal {@code mvn test}).
 *
 * Guards:
 * - ECOM_RUN_LIVE_EVAL must be "true" (case-insensitive, TRUE/True accepted,
 *   matching LiveEvalConfig.runEnabled); otherwise the class is skipped at the
 *   JUnit condition stage — no Spring context is booted, zero network/model
 *   calls.
 * - When enabled, ECOM_LLM_API_KEY must be a real key: a missing or placeholder
 *   value FAILS the test with a clear message (never a silent rules fallback).
 *
 * When it runs, it uses the REAL Spring-configured ChatClient (from
 * application.yml / ECOM_LLM_* env) and the REAL production services in LLM
 * mode: AfterSalesIntakeService(mode=LLM) and
 * AfterSalesEvidencePlannerService(mode=LLM). callModel is never overridden,
 * ChatClient is never mocked, no predefined/adaptive outputs are used.
 *
 * Base URL and model contain no hardcoded defaults here: they are read from the
 * existing Spring properties spring.ai.openai.base-url and
 * spring.ai.openai.chat.options.model, whose defaults and ECOM_LLM_BASE_URL /
 * ECOM_LLM_MODEL overrides live in application.yml.
 *
 * The pipeline stops before any side effect (no approval, no execution job, no
 * external commerce tool; evidence presence is simulated as accepted read-only
 * evidence). Only structural safety / configuration requirements may fail the
 * test — model accuracy is a manual-report metric, never a gate.
 */
@SpringBootTest
@EnabledIfEnvironmentVariable(named = LiveEvalConfig.RUN_ENV, matches = "(?i)true")
class LiveAfterSalesEvalRunnerTest {

    /** 每个 LLM 调用的超时（毫秒）：Live Eval 面对真实模型，比生产默认更宽松。 */
    private static final long LLM_CALL_TIMEOUT_MS = 30_000;

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    @Autowired
    private ObjectMapper objectMapper;

    /** 无硬编码默认值：来自 application.yml（${ECOM_LLM_BASE_URL:...} / ${ECOM_LLM_MODEL:...}）。 */
    @Value("${spring.ai.openai.base-url}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model}")
    private String model;

    @Test
    void liveEvalRunsRealModelWithoutSideEffects() {
        // 配置硬校验：开启后 key 缺失/占位 → 清晰失败（绝不静默回退规则）。
        String apiKey = LiveEvalConfig.requireApiKey();

        // 真实生产服务 + 真实 Spring 配置的 ChatClient，mode=LLM，不做任何覆写。
        AfterSalesIntakeService intakeService = new AfterSalesIntakeService(
                chatClientBuilder, objectMapper, "LLM", LLM_CALL_TIMEOUT_MS, apiKey);
        AfterSalesEvidencePlannerService plannerService = new AfterSalesEvidencePlannerService(
                chatClientBuilder, objectMapper, "LLM", LLM_CALL_TIMEOUT_MS, apiKey);

        EvalReport report = new LiveAfterSalesEvalHarness(intakeService, plannerService, objectMapper)
                .run(baseUrl, model);

        // 用例数必须在 [20, 30]。
        assertThat(report.caseCount()).isBetween(20, 30);

        // 结构性安全（唯一允许硬性失败的业务指标）：未装配任何副作用能力（by construction）。
        // 副作用不可能发生是结构性事实，不是观测到的零 —— 因此没有 sideEffectsObserved 断言。
        assertThat(report.structuralSafety().sideEffectCapabilitiesPresent()).isFalse();

        // 报告必须单独输出到 java/target/after-sales-live-eval/。
        assertThat(LiveAfterSalesEvalHarness.REPORT_DIR.resolve("live-eval-report.json")).exists();
        assertThat(LiveAfterSalesEvalHarness.REPORT_DIR.resolve("live-eval-report.md")).exists();

        // 模型准确性（intent/route/planner rates）是人工阅读的报告指标，不作门禁断言。
    }
}
