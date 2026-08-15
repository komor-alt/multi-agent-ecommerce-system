package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
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
        return new AfterSalesIntakeService(builder, objectMapper, mode, timeoutMs, apiKey, 32);
    }

    /** 覆写 callModel 注入模型输出的测试替身：测试中不发真实网络请求。 */
    private AfterSalesIntakeService llmService(String response, long timeoutMs) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesIntakeService(builder, objectMapper, "LLM", timeoutMs, "test-key-123", 32) {
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
    void plainTrackingQuestionStaysSingleTrackingIntent() {
        // 纯物流查询（无退款/补偿词）→ 只有 TRACK_SHIPMENT 意图（→ ANSWER_ONLY 路线）。
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);
        AfterSalesTypes.IntakeResult result = service.classify(
                "我的包裹十天没有更新了，现在到底是什么情况？能帮我查一下物流吗？");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.intents()).containsExactly("TRACK_SHIPMENT");
        assertThat(result.urgency()).isEqualTo("HIGH"); // 十天未更新 → HIGH
    }

    @Test
    void explicitRefundAndCompensationKeywordsProduceRequestRefund() {
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);
        // 退款 / 退钱 / 退单 / 赔偿 / 补偿 / 赔付：任一显式退款补偿词 → REQUEST_REFUND
        // （→ COMPENSATION_EVALUATION 路线）。
        for (String keyword : List.of("退款", "退钱", "退单", "赔偿", "补偿", "赔付")) {
            AfterSalesTypes.IntakeResult result = service.classify("包裹卡住了，我要" + keyword + "。");
            assertThat(result.intents())
                    .as("keyword %s", keyword)
                    .containsExactly("TRACK_SHIPMENT", "REQUEST_REFUND");
        }
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
    void rulesRecognizeLostInTransitWithCarrierCaseBaselineEvidence() {
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);
        AfterSalesTypes.IntakeResult result = service.classify(
                "包裹已经丢了十天了，到底找不找得到，我要赔偿！");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.issueType()).isEqualTo("LOST_IN_TRANSIT");
        assertThat(result.intents()).containsExactly("TRACK_SHIPMENT", "REQUEST_REFUND");
        assertThat(result.requiredEvidence()).containsExactly("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY");
    }

    @Test
    void rulesRecognizeDamagedItemWithDeliveryGraphBaselineEvidence() {
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);
        AfterSalesTypes.IntakeResult result = service.classify(
                "包裹收到了，但是东西破损了，能退款吗？");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.issueType()).isEqualTo("DAMAGED_ITEM");
        assertThat(result.intents()).containsExactly("TRACK_SHIPMENT", "REQUEST_REFUND");
        assertThat(result.requiredEvidence()).containsExactly(
                "ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");
    }

    @Test
    void damagedItemWithoutRefundWordsStaysSingleTrackingIntent() {
        // 破损报告但无退款/补偿词 → 只有 TRACK_SHIPMENT（→ ANSWER_ONLY 交付证明答复）。
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);
        AfterSalesTypes.IntakeResult result = service.classify("收到的盒子碎了，能帮我看看吗？");

        assertThat(result.issueType()).isEqualTo("DAMAGED_ITEM");
        assertThat(result.intents()).containsExactly("TRACK_SHIPMENT");
    }

    @Test
    void delayMessagesStayShipmentDelayWithoutLostOrDamageKeywords() {
        // 既有 SHIPMENT_DELAY 行为兼容：无丢失/破损关键词的延迟消息保持原分类。
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);
        AfterSalesTypes.IntakeResult result = service.classify("物流卡海关十天了，能不能退款？");

        assertThat(result.issueType()).isEqualTo("SHIPMENT_DELAY");
        assertThat(result.requiredEvidence()).containsExactly("ORDER", "SHIPMENT", "POLICY");
    }

    @Test
    void cancelOrderIsClassifiedAsUnsupported() {
        // 取消订单（超范围诉求）→ UNSUPPORTED + CANCEL_ORDER 意图，空证据建议。
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("我不想要了，帮我取消订单");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.issueType()).isEqualTo(AfterSalesTypes.IntakeResult.UNSUPPORTED);
        assertThat(result.intents()).containsExactly("CANCEL_ORDER");
        assertThat(result.requiredEvidence()).isEmpty();
    }

    @Test
    void exchangeReturnIsClassifiedAsUnsupported() {
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("收到的商品要退货换货");

        assertThat(result.issueType()).isEqualTo(AfterSalesTypes.IntakeResult.UNSUPPORTED);
        assertThat(result.intents()).containsExactly("EXCHANGE_RETURN");
    }

    @Test
    void accountAbuseIsClassifiedAsUnsupported() {
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("我的账号被盗刷了，快帮我处理");

        assertThat(result.issueType()).isEqualTo(AfterSalesTypes.IntakeResult.UNSUPPORTED);
        assertThat(result.intents()).containsExactly("ACCOUNT_PAYMENT_ABUSE");
    }

    @Test
    void unsupportedTakesPrecedenceOverRefundAndLostKeywords() {
        // 超范围诉求优先于物流/退款话术：即使同时含丢失与退款词也分类为 UNSUPPORTED
        // （取消订单不能被物流话术掩盖，由人工处理）。
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("包裹丢了，我要取消订单退款");

        assertThat(result.issueType()).isEqualTo(AfterSalesTypes.IntakeResult.UNSUPPORTED);
        assertThat(result.intents()).containsExactly("CANCEL_ORDER");
    }

    @Test
    void ambiguousDeliveryTrackingMessagesStayShipmentDelay() {
        // 既有/模糊的物流查询消息不受影响：无超范围关键词保持 SHIPMENT_DELAY 默认分类。
        AfterSalesIntakeService service = service("RULES", "your_api_key_here", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("包裹怎么还没到，能帮我查一下物流吗");

        assertThat(result.issueType()).isEqualTo(AfterSalesTypes.IntakeResult.SHIPMENT_DELAY);
        assertThat(result.intents()).containsExactly("TRACK_SHIPMENT");
    }

    @Test
    void llmAcceptsUnsupportedIssueTypeAsValidClassification() {
        // UNSUPPORTED 是合法分类输出：模型输出超范围类型 + 匹配意图 → 原样接受（不回退规则）。
        AfterSalesIntakeService service = llmService("""
                {"issueType":"UNSUPPORTED",
                 "intents":["CANCEL_ORDER"],
                 "urgency":"LOW",
                 "entities":{},
                 "missingInfo":[],
                 "requiredEvidence":[]}""", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("我要取消订单");

        assertThat(result.source()).isEqualTo("LLM");
        assertThat(result.issueType()).isEqualTo(AfterSalesTypes.IntakeResult.UNSUPPORTED);
        assertThat(result.intents()).containsExactly("CANCEL_ORDER");
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
        // requiredEvidence 只是模型不可信建议：白名单过滤后原样保留（仅 SHIPMENT），
        // 路线证据由 DecisionRouteResolver 服务端重建，不在此处补全。
        assertThat(result.requiredEvidence()).containsExactly("SHIPMENT");
    }

    @Test
    void llmAcceptsLostInTransitAndDamagedItemIssueTypes() {
        AfterSalesIntakeService lost = llmService("""
                {"issueType":"LOST_IN_TRANSIT",
                 "intents":["TRACK_SHIPMENT","REQUEST_REFUND"],
                 "urgency":"HIGH",
                 "entities":{},
                 "missingInfo":[],
                 "requiredEvidence":["ORDER","SHIPMENT","CARRIER_CASE","POLICY"]}""", 4000);
        AfterSalesTypes.IntakeResult lostResult = lost.classify("包裹丢了");
        assertThat(lostResult.source()).isEqualTo("LLM");
        assertThat(lostResult.issueType()).isEqualTo("LOST_IN_TRANSIT");
        assertThat(lostResult.requiredEvidence()).containsExactly("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY");

        AfterSalesIntakeService damaged = llmService("""
                {"issueType":"DAMAGED_ITEM",
                 "intents":["TRACK_SHIPMENT","REQUEST_REFUND"],
                 "urgency":"MEDIUM",
                 "entities":{},
                 "missingInfo":[],
                 "requiredEvidence":["ORDER","DELIVERY","DAMAGE_PHOTO","PRODUCT","POLICY"]}""", 4000);
        AfterSalesTypes.IntakeResult damagedResult = damaged.classify("包裹破损");
        assertThat(damagedResult.source()).isEqualTo("LLM");
        assertThat(damagedResult.issueType()).isEqualTo("DAMAGED_ITEM");
        assertThat(damagedResult.requiredEvidence()).containsExactly(
                "ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");
    }

    @Test
    void llmRejectsUnknownIssueTypeAndFallsBackToRules() {
        // 白名单外的问题类型（如 REFUND / 模型编造值）→ 整条输出作废，回退规则分类。
        AfterSalesIntakeService service = llmService(
                "{\"issueType\":\"REFUND\",\"intents\":[\"REQUEST_REFUND\"],\"urgency\":\"LOW\"}", 4000);

        AfterSalesTypes.IntakeResult result = service.classify("包裹没更新");

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
        assertThat(result.issueType()).isEqualTo("SHIPMENT_DELAY");
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
                builder, objectMapper, "LLM", 4000, "test-key-123", 32) {
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
                builder, objectMapper, "LLM", 100, "test-key-123", 32) {
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
                new AfterSalesIntakeService(builder, objectMapper, "LLM", 4000, "test-key-123", 32);

        service.shutdown(); // @PreDestroy 生命周期清理

        assertThat(service.llmExecutor.isShutdown()).isTrue();
        assertThat(service.llmExecutor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }
}
