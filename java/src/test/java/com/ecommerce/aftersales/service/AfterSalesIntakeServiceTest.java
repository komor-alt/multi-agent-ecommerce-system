package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AfterSalesIntakeServiceTest {

    // 与 Spring Boot 自动配置一致：支持 java.time 序列化。
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private AfterSalesIntakeService service(String mode, String apiKey, long timeoutMs) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesIntakeService(builder, objectMapper, mode, timeoutMs, apiKey);
    }

    /** 覆写 callModel 注入模型输出的测试替身：测试中不发真实网络请求。 */
    private AfterSalesIntakeService llmService(String response, long timeoutMs) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesIntakeService(builder, objectMapper, "LLM", timeoutMs, "test-key-123") {
            @Override
            protected String callModel(String customerMessage) {
                return response;
            }
        };
    }

    @Test
    void rulesRecognizeIntentsUrgencyAndDeadlineFromChineseSample() {
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);
        AfterSalesTypes.IntakeResult result = service.classify(
                "客服说预计两天内送到，但物流已经十天没有更新了，现在到底是什么情况？能不能退款？");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("RULES_MODE");
        assertThat(result.issueType()).isEqualTo("SHIPMENT_DELAY");
        assertThat(result.intents()).containsExactly("TRACK_SHIPMENT", "REQUEST_REFUND");
        assertThat(result.urgency()).isEqualTo("HIGH"); // 十天未更新 → HIGH
        assertThat(result.entities()).containsEntry("deadline", "2 days"); // 规范成可解释字符串
        assertThat(result.requiredEvidence()).containsExactly("ORDER", "SHIPMENT", "POLICY");
    }

    @Test
    void rulesRecognizeRelativeChineseDeadlines() {
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);

        assertThat(service.classify("我要求今天必须收到").entities()).containsEntry("deadline", "0 days");
        assertThat(service.classify("明天就得出国，等不了了").entities()).containsEntry("deadline", "1 day");
        assertThat(service.classify("我后天要搬家").entities()).containsEntry("deadline", "2 days");
    }

    @Test
    void rulesClassifyCustomsHeldSampleWithRelativeDeadline() {
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);
        AfterSalesTypes.IntakeResult result = service.classify(
                "物流卡海关十天了，我后天要出国，东西收不到了，能不能退款？");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.issueType()).isEqualTo("SHIPMENT_DELAY");
        assertThat(result.intents()).containsExactly("TRACK_SHIPMENT", "REQUEST_REFUND");
        assertThat(result.urgency()).isEqualTo("HIGH"); // 卡海关十天 → HIGH
        assertThat(result.entities()).containsEntry("deadline", "2 days"); // 后天 → 2 days
    }

    @Test
    void rulesKeepNumericAndChinesePromiseMatching() {
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);
        // 数字：承诺 3 天内 → deadline 3 days
        assertThat(service.classify("承诺 3 天内送到。").entities()).containsEntry("deadline", "3 days");
        // 中文数字：说好五天内送到 → deadline 5 days
        assertThat(service.classify("说好五天内送到。").entities()).containsEntry("deadline", "5 days");
    }

    @Test
    void autoModeWithoutKeySkipsNetworkAndUsesRules() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);
        AfterSalesIntakeService service =
                new AfterSalesIntakeService(builder, objectMapper, "AUTO", 4000, "your_api_key_here");

        AfterSalesTypes.IntakeResult result = service.classify("我的包裹十天没有更新了，能不能退款？");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_API_KEY_MISSING");
        assertThat(result.urgency()).isEqualTo("HIGH");
        verifyNoInteractions(chatClient); // 不发网络请求
    }

    @Test
    void validModelJsonIsWhitelistNormalized() {
        // 注意：JSON 只含六个 schema 键（unknown keys now fail，见 unknownTopLevelKeyFailsAndFallsBack）。
        AfterSalesIntakeService service = llmService("""
                {"issueType":"SHIPMENT_DELAY",
                 "intents":["track_shipment","REQUEST_REFUND","EXECUTE_REFUND"],
                 "urgency":"high",
                 "entities":{"deadline":"2 days"},
                 "missingInfo":["delivery_promise","INJECTED_NOTE"],
                 "requiredEvidence":["SHIPMENT"]}""", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("包裹没更新");

        assertThat(result.source()).isEqualTo("LLM");
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.issueType()).isEqualTo("SHIPMENT_DELAY");
        assertThat(result.intents()).containsExactly("TRACK_SHIPMENT", "REQUEST_REFUND"); // 白名单过滤 + 大写归一
        assertThat(result.urgency()).isEqualTo("HIGH");
        assertThat(result.entities()).containsEntry("deadline", "2 days");
        assertThat(result.missingInfo()).containsExactly("DELIVERY_PROMISE");
        // 服务端重建必需证据：模型只能给出 SHIPMENT，基线 ORDER/SHIPMENT/POLICY 一个不少。
        assertThat(result.requiredEvidence()).containsExactly("ORDER", "SHIPMENT", "POLICY");
    }

    @Test
    void unknownTopLevelKeyFailsAndFallsBack() {
        // 未知顶层键（如 summary）不再被忽略：整条输出作废，回退规则。
        AfterSalesIntakeService service = llmService(
                "{\"issueType\":\"SHIPMENT_DELAY\",\"intents\":[\"REQUEST_REFUND\"],\"urgency\":\"LOW\","
                        + "\"summary\":\"model-generated text\"}", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("包裹没更新");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
    }

    @Test
    void unknownKeyInsideEntitiesFails() {
        // 嵌套层级的未知键：entities 只允许 deadline。
        AfterSalesIntakeService service = llmService(
                "{\"issueType\":\"SHIPMENT_DELAY\",\"intents\":[\"TRACK_SHIPMENT\"],\"urgency\":\"LOW\","
                        + "\"entities\":{\"deadline\":\"2 days\",\"note\":\"extra\"}}", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("包裹没更新");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
    }

    @Test
    void nestedForbiddenKeyInsideArrayFails() {
        // 嵌套层级的禁止键：数组元素里塞对象/refundNow → 整条作废，不再被白名单过滤吞掉。
        AfterSalesIntakeService service = llmService(
                "{\"issueType\":\"SHIPMENT_DELAY\",\"intents\":[\"TRACK_SHIPMENT\"],\"urgency\":\"LOW\","
                        + "\"missingInfo\":[\"SHIPMENT_STATUS\",{\"refundNow\":true}]}", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("包裹没更新");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
    }

    @Test
    void modelCannotGenerateAmountOrApprovalOutputs() {
        // 模型输出含金额/批准字段 → 整条输出作废，回退规则；结果中绝无模型生成的数值。
        AfterSalesIntakeService service = llmService(
                "{\"issueType\":\"SHIPMENT_DELAY\",\"intents\":[\"REQUEST_REFUND\"],\"urgency\":\"LOW\","
                        + "\"amount\":9999,\"approved\":true}", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("包裹没更新");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
        assertThat(result.urgency()).isEqualTo("LOW"); // 规则分类的确定性结果
    }

    @Test
    void invalidJsonFallsBackWithoutThrowing() {
        AfterSalesIntakeService service = llmService("this is not json {", 4000);

        assertThatCode(() -> {
            AfterSalesTypes.IntakeResult result = service.classify("包裹没更新");
            assertThat(result.source()).isEqualTo("RULE_FALLBACK");
            assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_JSON");
        }).doesNotThrowAnyException();
    }

    @Test
    void modelExceptionFallsBackWithoutThrowing() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        AfterSalesIntakeService service = new AfterSalesIntakeService(
                builder, objectMapper, "LLM", 4000, "test-key-123") {
            @Override
            protected String callModel(String customerMessage) {
                throw new RuntimeException("upstream exploded with sensitive detail");
            }
        };

        AfterSalesTypes.IntakeResult result = service.classify("包裹没更新");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_ERROR"); // 只暴露安全错误码
        assertThat(result.issueType()).isEqualTo("SHIPMENT_DELAY");
    }

    @Test
    void modelTimeoutCancelsWorkerAndFallsBackWithoutWaiting() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        AtomicBoolean workerInterrupted = new AtomicBoolean(false);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        AfterSalesIntakeService service = new AfterSalesIntakeService(
                builder, objectMapper, "LLM", 100, "test-key-123") {
            @Override
            protected String callModel(String customerMessage) {
                workerStarted.countDown();
                try {
                    Thread.sleep(5_000); // 模拟迟迟不返回的模型调用
                } catch (InterruptedException error) {
                    workerInterrupted.set(true); // future.cancel(true) 的中断必须到达调用线程
                    Thread.currentThread().interrupt();
                } finally {
                    workerFinished.countDown();
                }
                return "{}";
            }
        };

        long started = System.nanoTime();
        AfterSalesTypes.IntakeResult result = service.classify("包裹没更新");
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // worker 只在 classify 提交任务后才启动，因此必须在 classify 之后再等它。
        assertThat(workerStarted.await(2, TimeUnit.SECONDS)).isTrue(); // 任务确实提交进池并开始执行（超时是真实的）
        assertThat(elapsedMs).isLessThan(3_000); // 受超时控制，不阻塞主循环
        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_TIMEOUT");
        // 确定性中断断言：worker 被 cancel(true) 中断后立即退出（2 秒内），而不是睡满 5 秒泄漏线程。
        assertThat(workerFinished.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(workerInterrupted).isTrue();
    }

    @Test
    void shutdownNowTerminatesLlmExecutor() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        AfterSalesIntakeService service =
                new AfterSalesIntakeService(builder, objectMapper, "LLM", 4000, "test-key-123");

        service.shutdown(); // @PreDestroy 生命周期清理

        assertThat(service.llmExecutor.isShutdown()).isTrue();
        assertThat(service.llmExecutor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
}
