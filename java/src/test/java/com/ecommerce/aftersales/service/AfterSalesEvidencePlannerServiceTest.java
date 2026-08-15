package com.ecommerce.aftersales.service;

import com.ecommerce.aftersales.model.AfterSalesTypes;
import com.ecommerce.aftersales.model.AfterSalesTypes.EvidenceType;
import com.ecommerce.aftersales.service.AfterSalesEvidencePlannerService.PlanningInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AfterSalesEvidencePlannerServiceTest {

    // 与 Spring Boot 自动配置一致：支持 java.time 序列化。
    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    private static final List<String> FULL_REQUIRED = List.of("ORDER", "SHIPMENT", "POLICY");

    private AfterSalesEvidencePlannerService service(String mode, String apiKey, long timeoutMs) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesEvidencePlannerService(builder, objectMapper, mode, timeoutMs, apiKey, 32);
    }

    /** 覆写 callModel 注入模型输出的测试替身：测试中不发真实网络请求。 */
    private AfterSalesEvidencePlannerService llmService(String response, long timeoutMs) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesEvidencePlannerService(builder, objectMapper, "LLM", timeoutMs, "test-key-123", 32) {
            @Override
            protected String callModel(String prompt) {
                return response;
            }
        };
    }

    private PlanningInput input(Map<String, Boolean> presence) {
        return input(presence, FULL_REQUIRED);
    }

    private PlanningInput input(Map<String, Boolean> presence, List<String> requiredEvidence) {
        return new PlanningInput(new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", List.of("TRACK_SHIPMENT"), "LOW", Map.of("deadline", "2 days"),
                List.of("DELIVERY_PROMISE"), requiredEvidence, "RULE_FALLBACK", "RULES_MODE", 0L), presence);
    }

    @Test
    void deterministicPlannerTransitionsThroughFourStatesInServerOrder() {
        AfterSalesEvidencePlannerService service = service("RULES", "your_api_key_here", 4000);

        // 空在场快照 → 第一步请求 ORDER。
        AfterSalesTypes.PlanningResult first = service.plan(input(Map.of()));
        assertThat(first.nextEvidence()).isEqualTo(EvidenceType.ORDER);
        assertThat(first.reasonCode()).isEqualTo("ORDER_CONTEXT_REQUIRED");
        assertThat(first.source()).isEqualTo("RULE_FALLBACK");
        assertThat(first.fallbackReason()).isEqualTo("RULES_MODE");
        assertThat(first.invalidInput()).isFalse();

        // 只缺 SHIPMENT → 第二步请求 SHIPMENT。
        AfterSalesTypes.PlanningResult second =
                service.plan(input(Map.of("ORDER", true)));
        assertThat(second.nextEvidence()).isEqualTo(EvidenceType.SHIPMENT);
        assertThat(second.reasonCode()).isEqualTo("SHIPMENT_STATUS_REQUIRED");

        // 只缺 POLICY → 第三步请求 POLICY。
        AfterSalesTypes.PlanningResult third =
                service.plan(input(Map.of("ORDER", true, "SHIPMENT", true)));
        assertThat(third.nextEvidence()).isEqualTo(EvidenceType.POLICY);
        assertThat(third.reasonCode()).isEqualTo("POLICY_REQUIRED");

        // 全部齐备 → READY_FOR_DECISION。
        AfterSalesTypes.PlanningResult fourth =
                service.plan(input(Map.of("ORDER", true, "SHIPMENT", true, "POLICY", true)));
        assertThat(fourth.nextEvidence()).isEqualTo(EvidenceType.READY_FOR_DECISION);
        assertThat(fourth.reasonCode()).isEqualTo("EVIDENCE_COMPLETE");
    }

    @Test
    void deterministicPlannerHonorsRequiredEvidenceSubsetSkippingPolicy() {
        AfterSalesEvidencePlannerService service = service("RULES", "your_api_key_here", 4000);
        List<String> subset = List.of("ORDER", "SHIPMENT");

        assertThat(service.plan(input(Map.of(), subset)).nextEvidence()).isEqualTo(EvidenceType.ORDER);
        assertThat(service.plan(input(Map.of("ORDER", true), subset)).nextEvidence()).isEqualTo(EvidenceType.SHIPMENT);
        // 子集齐备 → READY_FOR_DECISION，POLICY 永远不会被请求。
        assertThat(service.plan(input(Map.of("ORDER", true, "SHIPMENT", true), subset)).nextEvidence())
                .isEqualTo(EvidenceType.READY_FOR_DECISION);
        assertThat(service.plan(input(Map.of(), subset)).reasonCode()).isEqualTo("ORDER_CONTEXT_REQUIRED");
    }

    @Test
    void deterministicPlannerFollowsServerRebuiltGraphOrderAndDeduplicates() {
        AfterSalesEvidencePlannerService service = service("RULES", "your_api_key_here", 4000);
        // requiredEvidence 是服务端按问题类型证据图重建的清单：顺序即图顺序，重复按首次出现去重
        // （LOST_IN_TRANSIT 图含重复项也不影响推进顺序）。
        List<String> graph = List.of("ORDER", "ORDER", "SHIPMENT", "CARRIER_CASE", "CARRIER_CASE", "POLICY");

        AfterSalesTypes.PlanningResult first = service.plan(input(Map.of(), graph));
        assertThat(first.nextEvidence()).isEqualTo(EvidenceType.ORDER);
        assertThat(first.reasonCode()).isEqualTo("ORDER_CONTEXT_REQUIRED");
        assertThat(first.invalidInput()).isFalse();

        assertThat(service.plan(input(Map.of("ORDER", true), graph)).nextEvidence()).isEqualTo(EvidenceType.SHIPMENT);
        assertThat(service.plan(input(Map.of("ORDER", true, "SHIPMENT", true), graph)).nextEvidence())
                .isEqualTo(EvidenceType.CARRIER_CASE);
        assertThat(service.plan(input(Map.of("ORDER", true, "SHIPMENT", true, "CARRIER_CASE", true), graph)).nextEvidence())
                .isEqualTo(EvidenceType.POLICY);
        assertThat(service.plan(input(Map.of(
                "ORDER", true, "SHIPMENT", true, "CARRIER_CASE", true, "POLICY", true), graph)).nextEvidence())
                .isEqualTo(EvidenceType.READY_FOR_DECISION);
    }

    @Test
    void threeEvidenceGraphsProduceExactDeterministicOrderings() {
        AfterSalesEvidencePlannerService service = service("RULES", "your_api_key_here", 4000);

        // SHIPMENT_DELAY 图：ORDER → SHIPMENT → POLICY → READY。
        List<String> delay = List.of("ORDER", "SHIPMENT", "POLICY");
        assertThat(service.plan(input(Map.of(), delay)).nextEvidence()).isEqualTo(EvidenceType.ORDER);
        assertThat(service.plan(input(Map.of("ORDER", true), delay)).nextEvidence()).isEqualTo(EvidenceType.SHIPMENT);
        assertThat(service.plan(input(Map.of("ORDER", true, "SHIPMENT", true), delay)).nextEvidence())
                .isEqualTo(EvidenceType.POLICY);
        assertThat(service.plan(input(Map.of("ORDER", true, "SHIPMENT", true, "POLICY", true), delay)).nextEvidence())
                .isEqualTo(EvidenceType.READY_FOR_DECISION);

        // LOST_IN_TRANSIT 图：ORDER → SHIPMENT → CARRIER_CASE → POLICY → READY。
        List<String> lost = List.of("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY");
        assertThat(service.plan(input(Map.of(), lost)).nextEvidence()).isEqualTo(EvidenceType.ORDER);
        assertThat(service.plan(input(Map.of("ORDER", true), lost)).nextEvidence()).isEqualTo(EvidenceType.SHIPMENT);
        AfterSalesTypes.PlanningResult carrier = service.plan(input(Map.of("ORDER", true, "SHIPMENT", true), lost));
        assertThat(carrier.nextEvidence()).isEqualTo(EvidenceType.CARRIER_CASE);
        assertThat(carrier.reasonCode()).isEqualTo("CARRIER_CASE_REQUIRED");
        assertThat(service.plan(input(Map.of("ORDER", true, "SHIPMENT", true, "CARRIER_CASE", true), lost)).nextEvidence())
                .isEqualTo(EvidenceType.POLICY);
        assertThat(service.plan(input(Map.of(
                "ORDER", true, "SHIPMENT", true, "CARRIER_CASE", true, "POLICY", true), lost)).nextEvidence())
                .isEqualTo(EvidenceType.READY_FOR_DECISION);

        // DAMAGED_ITEM 图：ORDER → DELIVERY → DAMAGE_PHOTO → PRODUCT → POLICY → READY。
        List<String> damaged = List.of("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY");
        assertThat(service.plan(input(Map.of(), damaged)).nextEvidence()).isEqualTo(EvidenceType.ORDER);
        AfterSalesTypes.PlanningResult delivery = service.plan(input(Map.of("ORDER", true), damaged));
        assertThat(delivery.nextEvidence()).isEqualTo(EvidenceType.DELIVERY);
        assertThat(delivery.reasonCode()).isEqualTo("DELIVERY_PROOF_REQUIRED");
        AfterSalesTypes.PlanningResult photo = service.plan(input(Map.of("ORDER", true, "DELIVERY", true), damaged));
        assertThat(photo.nextEvidence()).isEqualTo(EvidenceType.DAMAGE_PHOTO);
        assertThat(photo.reasonCode()).isEqualTo("DAMAGE_PHOTO_REQUIRED");
        AfterSalesTypes.PlanningResult product = service.plan(input(
                Map.of("ORDER", true, "DELIVERY", true, "DAMAGE_PHOTO", true), damaged));
        assertThat(product.nextEvidence()).isEqualTo(EvidenceType.PRODUCT);
        assertThat(product.reasonCode()).isEqualTo("PRODUCT_CONTEXT_REQUIRED");
        assertThat(service.plan(input(Map.of(
                "ORDER", true, "DELIVERY", true, "DAMAGE_PHOTO", true, "PRODUCT", true), damaged)).nextEvidence())
                .isEqualTo(EvidenceType.POLICY);
        assertThat(service.plan(input(Map.of(
                "ORDER", true, "DELIVERY", true, "DAMAGE_PHOTO", true, "PRODUCT", true, "POLICY", true), damaged))
                .nextEvidence()).isEqualTo(EvidenceType.READY_FOR_DECISION);
    }

    @Test
    void unknownRequiredEvidenceYieldsInvalidInputNeverReady() {
        AfterSalesEvidencePlannerService service = service("RULES", "your_api_key_here", 4000);
        // 未知证据名：规划器不得静默跳过并声称 READY，必须返回 invalidInput 结果由循环拒绝。
        AfterSalesTypes.PlanningResult unknown =
                service.plan(input(Map.of("ORDER", true, "SHIPMENT", true, "POLICY", true),
                        List.of("ORDER", "SHIPMENT", "INVOICE")));
        assertThat(unknown.invalidInput()).isTrue();
        assertThat(unknown.reasonCode()).isEqualTo("INVALID_REQUIRED_EVIDENCE");

        // 就绪标记出现在必需证据里同样是非法的服务端输入。
        AfterSalesTypes.PlanningResult readyMarker =
                service.plan(input(Map.of(), List.of("ORDER", "READY_FOR_DECISION")));
        assertThat(readyMarker.invalidInput()).isTrue();
        assertThat(readyMarker.source()).isEqualTo("RULE_FALLBACK");
    }

    @Test
    void autoModeWithoutKeySkipsNetworkAndUsesRules() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        ChatClient chatClient = mock(ChatClient.class);
        when(builder.build()).thenReturn(chatClient);
        AfterSalesEvidencePlannerService service =
                new AfterSalesEvidencePlannerService(builder, objectMapper, "AUTO", 4000, "your_api_key_here");

        AfterSalesTypes.PlanningResult result = service.plan(input(Map.of()));

        assertThat(result.nextEvidence()).isEqualTo(EvidenceType.ORDER);
        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_API_KEY_MISSING");
        verifyNoInteractions(chatClient); // 不发网络请求
    }

    @Test
    void llmModeInvalidRequiredEvidenceNeverInvokesModel() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        AfterSalesEvidencePlannerService service = trackingLlmService(modelCalled);

        // 未知证据名：即使模型本可返回合法输出，也必须在模式分支/网络提交之前被拒绝。
        AfterSalesTypes.PlanningResult unknown =
                service.plan(input(Map.of(), List.of("ORDER", "INVOICE")));
        assertInvalidInput(unknown);

        // 就绪标记出现在必需证据里同样是非法服务端输入。
        AfterSalesTypes.PlanningResult readyMarker =
                service.plan(input(Map.of(), List.of("ORDER", "READY_FOR_DECISION")));
        assertInvalidInput(readyMarker);

        assertThat(modelCalled).isFalse(); // 非法输入绝不触发模型调用
    }

    @Test
    void llmModeNullRequiredEvidenceNeverInvokesModel() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        AfterSalesEvidencePlannerService service = trackingLlmService(modelCalled);

        AfterSalesTypes.PlanningResult result = service.plan(input(Map.of(), null));

        assertInvalidInput(result);
        assertThat(modelCalled).isFalse();
    }

    @Test
    void autoModeWithConfiguredKeyInvalidRequiredEvidenceNeverInvokesModel() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        AfterSalesEvidencePlannerService service = new AfterSalesEvidencePlannerService(
                builder, objectMapper, "AUTO", 4000, "test-key-123", 32) {
            @Override
            protected String callModel(String prompt) {
                modelCalled.set(true);
                return "{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"ORDER_CONTEXT_REQUIRED\"}";
            }
        };

        AfterSalesTypes.PlanningResult result = service.plan(input(Map.of(), List.of("INVOICE")));

        assertInvalidInput(result);
        assertThat(modelCalled).isFalse();
    }

    @Test
    void nullInputNullIntakeOrNullPresenceYieldInvalidInputWithoutExceptionOrModelCall() {
        AtomicBoolean modelCalled = new AtomicBoolean(false);
        AfterSalesEvidencePlannerService llm = trackingLlmService(modelCalled);
        AfterSalesEvidencePlannerService rules = service("RULES", "your_api_key_here", 4000);
        AfterSalesTypes.IntakeResult intake = new AfterSalesTypes.IntakeResult(
                "SHIPMENT_DELAY", List.of("TRACK_SHIPMENT"), "LOW", Map.of(), List.of(),
                FULL_REQUIRED, "RULE_FALLBACK", "RULES_MODE", 0L);

        // LLM（有 key）与 RULES 两种模式下，null input / null intake / null evidencePresence
        // 都必须防御性返回 invalidInput 结果 —— 不抛异常、不调用模型。
        for (AfterSalesEvidencePlannerService planner : List.of(llm, rules)) {
            assertInvalidInput(planner.plan(null));
            assertInvalidInput(planner.plan(new PlanningInput(null, Map.of())));
            assertInvalidInput(planner.plan(new PlanningInput(intake, null)));
        }
        assertThat(modelCalled).isFalse();
    }

    @Test
    void validModelJsonIsAcceptedWithWhitelistedReasonCode() {
        AfterSalesEvidencePlannerService service = llmService(
                "{\"nextEvidence\":\"SHIPMENT\",\"reasonCode\":\"SHIPMENT_STATUS_REQUIRED\"}", 4000);

        AfterSalesTypes.PlanningResult result = service.plan(input(Map.of("ORDER", true)));

        assertThat(result.source()).isEqualTo("LLM");
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.nextEvidence()).isEqualTo(EvidenceType.SHIPMENT);
        assertThat(result.reasonCode()).isEqualTo("SHIPMENT_STATUS_REQUIRED");
    }

    @Test
    void validModelJsonIsAcceptedWithEVIDENCE_COMPLETEForReady() {
        AfterSalesEvidencePlannerService service = llmService(
                "{\"nextEvidence\":\"READY_FOR_DECISION\",\"reasonCode\":\"EVIDENCE_COMPLETE\"}", 4000);

        AfterSalesTypes.PlanningResult result =
                service.plan(input(Map.of("ORDER", true, "SHIPMENT", true, "POLICY", true)));

        assertThat(result.source()).isEqualTo("LLM");
        assertThat(result.nextEvidence()).isEqualTo(EvidenceType.READY_FOR_DECISION);
        assertThat(result.reasonCode()).isEqualTo("EVIDENCE_COMPLETE");
    }

    @Test
    void llmModeAcceptsNewEvidenceTypesWithPairedReasonCodes() {
        // 新增证据类型 + 配对理由码：模型可以规划 CARRIER_CASE / DELIVERY / DAMAGE_PHOTO / PRODUCT。
        AfterSalesEvidencePlannerService carrier = llmService(
                "{\"nextEvidence\":\"CARRIER_CASE\",\"reasonCode\":\"CARRIER_CASE_REQUIRED\"}", 4000);
        AfterSalesTypes.PlanningResult carrierResult = carrier.plan(input(
                Map.of("ORDER", true, "SHIPMENT", true), List.of("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY")));
        assertThat(carrierResult.source()).isEqualTo("LLM");
        assertThat(carrierResult.nextEvidence()).isEqualTo(EvidenceType.CARRIER_CASE);
        assertThat(carrierResult.reasonCode()).isEqualTo("CARRIER_CASE_REQUIRED");

        AfterSalesEvidencePlannerService delivery = llmService(
                "{\"nextEvidence\":\"DELIVERY\",\"reasonCode\":\"DELIVERY_PROOF_REQUIRED\"}", 4000);
        AfterSalesTypes.PlanningResult deliveryResult = delivery.plan(input(
                Map.of("ORDER", true), List.of("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY")));
        assertThat(deliveryResult.source()).isEqualTo("LLM");
        assertThat(deliveryResult.nextEvidence()).isEqualTo(EvidenceType.DELIVERY);
        assertThat(deliveryResult.reasonCode()).isEqualTo("DELIVERY_PROOF_REQUIRED");

        AfterSalesEvidencePlannerService photo = llmService(
                "{\"nextEvidence\":\"DAMAGE_PHOTO\",\"reasonCode\":\"DAMAGE_PHOTO_REQUIRED\"}", 4000);
        AfterSalesTypes.PlanningResult photoResult = photo.plan(input(
                Map.of("ORDER", true, "DELIVERY", true), List.of("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY")));
        assertThat(photoResult.source()).isEqualTo("LLM");
        assertThat(photoResult.nextEvidence()).isEqualTo(EvidenceType.DAMAGE_PHOTO);
        assertThat(photoResult.reasonCode()).isEqualTo("DAMAGE_PHOTO_REQUIRED");

        AfterSalesEvidencePlannerService product = llmService(
                "{\"nextEvidence\":\"PRODUCT\",\"reasonCode\":\"PRODUCT_CONTEXT_REQUIRED\"}", 4000);
        AfterSalesTypes.PlanningResult productResult = product.plan(input(
                Map.of("ORDER", true, "DELIVERY", true, "DAMAGE_PHOTO", true),
                List.of("ORDER", "DELIVERY", "DAMAGE_PHOTO", "PRODUCT", "POLICY")));
        assertThat(productResult.source()).isEqualTo("LLM");
        assertThat(productResult.nextEvidence()).isEqualTo(EvidenceType.PRODUCT);
        assertThat(productResult.reasonCode()).isEqualTo("PRODUCT_CONTEXT_REQUIRED");
    }

    @Test
    void llmModeRejectsNewEvidenceTypesWithWrongReasonCode() {
        // 白名单外/错配理由码对新增证据同样作废：CARRIER_CASE 配 POLICY_REQUIRED 是非法配对。
        AfterSalesEvidencePlannerService service = llmService(
                "{\"nextEvidence\":\"CARRIER_CASE\",\"reasonCode\":\"POLICY_REQUIRED\"}", 4000);
        AfterSalesTypes.PlanningResult result = service.plan(input(
                Map.of("ORDER", true, "SHIPMENT", true), List.of("ORDER", "SHIPMENT", "CARRIER_CASE", "POLICY")));

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
        assertThat(result.nextEvidence()).isEqualTo(EvidenceType.CARRIER_CASE); // 规则兜底沿图顺序
    }

    @Test
    void modelJsonWithoutReasonCodeIsInvalidAndFallsBack() {
        // 严格模式：两个标量字段都必须给出，缺失 reasonCode 是非法输出，绝不派生理由码。
        AfterSalesEvidencePlannerService service = llmService("{\"nextEvidence\":\"READY_FOR_DECISION\"}", 4000);

        AfterSalesTypes.PlanningResult result =
                service.plan(input(Map.of("ORDER", true, "SHIPMENT", true, "POLICY", true)));

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
        assertThat(result.nextEvidence()).isEqualTo(EvidenceType.READY_FOR_DECISION);
        assertThat(result.reasonCode()).isEqualTo("EVIDENCE_COMPLETE");
    }

    @Test
    void modelJsonWithBlankReasonCodeIsInvalidAndFallsBack() {
        AfterSalesEvidencePlannerService service = llmService(
                "{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"\"}", 4000);

        AfterSalesTypes.PlanningResult result = service.plan(input(Map.of()));

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
    }

    @Test
    void invalidJsonFallsBackWithoutThrowing() {
        AfterSalesEvidencePlannerService service = llmService("this is not json {", 4000);

        assertThatCode(() -> {
            AfterSalesTypes.PlanningResult result = service.plan(input(Map.of()));
            assertThat(result.source()).isEqualTo("RULE_FALLBACK");
            assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_JSON");
        }).doesNotThrowAnyException();
    }

    @Test
    void unknownTopLevelKeyFailsAndFallsBack() {
        AfterSalesEvidencePlannerService service = llmService(
                "{\"nextEvidence\":\"ORDER\",\"summary\":\"model-generated text\"}", 4000);

        AfterSalesTypes.PlanningResult result = service.plan(input(Map.of()));

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
    }

    @Test
    void nestedInjectionFailsAndFallsBack() {
        // reasonCode 必须是标量：嵌套对象 = 注入尝试。
        AfterSalesEvidencePlannerService service = llmService(
                "{\"nextEvidence\":\"ORDER\",\"reasonCode\":{\"SHIPMENT_STATUS_REQUIRED\":true}}", 4000);

        AfterSalesTypes.PlanningResult result = service.plan(input(Map.of()));

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
    }

    @Test
    void forbiddenKeysAtAnyDepthFailAndFallBack() {
        // 顶层禁止键：tool 名称。
        AfterSalesEvidencePlannerService service = llmService(
                "{\"nextEvidence\":\"ORDER\",\"tool\":\"get_order_detail\"}", 4000);
        assertThat(service.plan(input(Map.of())).fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");

        // 嵌套层级禁止键：arguments（工具参数）。
        AfterSalesEvidencePlannerService nested = llmService(
                "{\"nextEvidence\":\"ORDER\",\"extra\":{\"arguments\":{\"orderId\":\"O-1\"}}}", 4000);
        assertThat(nested.plan(input(Map.of())).fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");

        // 其余禁止键逐一验证（amount/refund/approved/approve/reject/execute/action）。
        for (String forbidden : List.of("amount", "refund", "approved", "approve", "reject", "execute", "action", "toolName")) {
            AfterSalesEvidencePlannerService each = llmService(
                    "{\"nextEvidence\":\"ORDER\",\"" + forbidden + "\":true}", 4000);
            assertThat(each.plan(input(Map.of())).fallbackReason())
                    .as("forbidden key %s", forbidden)
                    .isEqualTo("LLM_INVALID_OUTPUT");
        }
    }

    @Test
    void invalidEvidenceNameFailsAndFallsBack() {
        // 决策工具与未知值都不是合法证据名。
        AfterSalesEvidencePlannerService service = llmService(
                "{\"nextEvidence\":\"calculate_compensation\"}", 4000);
        assertThat(service.plan(input(Map.of())).fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");

        AfterSalesEvidencePlannerService unknown = llmService("{\"nextEvidence\":\"INVOICE\"}", 4000);
        assertThat(unknown.plan(input(Map.of())).fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
    }

    @Test
    void invalidReasonEvidencePairFailsAndFallsBack() {
        // 理由码与证据不匹配：ORDER 配 EVIDENCE_COMPLETE 是非法配对。
        AfterSalesEvidencePlannerService service = llmService(
                "{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"EVIDENCE_COMPLETE\"}", 4000);
        assertThat(service.plan(input(Map.of())).fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");

        // 配给错误证据的理由码也作废（ORDER 必须配 ORDER_CONTEXT_REQUIRED）。
        AfterSalesEvidencePlannerService mismatched = llmService(
                "{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"POLICY_REQUIRED\"}", 4000);
        assertThat(mismatched.plan(input(Map.of())).fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");

        // 白名单外的理由码也作废。
        AfterSalesEvidencePlannerService unknownReason = llmService(
                "{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"I_WANT_REFUND_NOW\"}", 4000);
        assertThat(unknownReason.plan(input(Map.of())).fallbackReason()).isEqualTo("LLM_INVALID_OUTPUT");
    }

    @Test
    void modelExceptionFallsBackWithoutThrowing() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        AfterSalesEvidencePlannerService service = new AfterSalesEvidencePlannerService(
                builder, objectMapper, "LLM", 4000, "test-key-123", 32) {
            @Override
            protected String callModel(String prompt) {
                throw new RuntimeException("upstream exploded with sensitive detail");
            }
        };

        AfterSalesTypes.PlanningResult result = service.plan(input(Map.of()));

        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_ERROR"); // 只暴露安全错误码
        assertThat(result.nextEvidence()).isEqualTo(EvidenceType.ORDER);
    }

    @Test
    void modelTimeoutCancelsWorkerAndFallsBackWithoutWaiting() throws Exception {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        AtomicBoolean workerInterrupted = new AtomicBoolean(false);
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch workerFinished = new CountDownLatch(1);
        AfterSalesEvidencePlannerService service = new AfterSalesEvidencePlannerService(
                builder, objectMapper, "LLM", 100, "test-key-123", 32) {
            @Override
            protected String callModel(String prompt) {
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
        AfterSalesTypes.PlanningResult result = service.plan(input(Map.of()));
        long elapsedMs = (System.nanoTime() - started) / 1_000_000;

        // worker 只在 plan 提交任务后才启动，因此必须在 plan 之后再等它。
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
        AfterSalesEvidencePlannerService service =
                new AfterSalesEvidencePlannerService(builder, objectMapper, "LLM", 4000, "test-key-123", 32);

        service.shutdown(); // @PreDestroy 生命周期清理

        assertThat(service.llmExecutor.isShutdown()).isTrue();
        assertThat(service.llmExecutor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void deterministicPlanFallbackReturnsDeterministicNextEvidence() {
        // Agent Loop 在 LLM_INVALID_PLAN 时复用的兜底入口：直接按规则计算，不重复调用模型。
        AfterSalesEvidencePlannerService service = service("LLM", "test-key-123", 4000);
        AfterSalesTypes.PlanningResult result =
                service.deterministicPlan(input(Map.of("ORDER", true)), "LLM_INVALID_PLAN");

        assertThat(result.nextEvidence()).isEqualTo(EvidenceType.SHIPMENT);
        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("LLM_INVALID_PLAN");
        assertThat(result.reasonCode()).isEqualTo("SHIPMENT_STATUS_REQUIRED");
    }

    @Test
    void deterministicPlanInvalidRequiredEvidenceYieldsInvalidInput() {
        // 兜底入口同样必须防御非法输入：未知证据 / null requiredEvidence → invalidInput，不抛异常。
        AfterSalesEvidencePlannerService service = service("LLM", "test-key-123", 4000);

        assertInvalidInput(service.deterministicPlan(input(Map.of(), List.of("INVOICE")), "LLM_INVALID_PLAN"));
        assertInvalidInput(service.deterministicPlan(input(Map.of(), null), "LLM_INVALID_PLAN"));
        assertInvalidInput(service.deterministicPlan(null, "LLM_INVALID_PLAN"));
    }

    /** LLM 模式替身：记录 callModel 是否被调用（非法输入不得触发模型调用）。 */
    private AfterSalesEvidencePlannerService trackingLlmService(AtomicBoolean modelCalled) {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));
        return new AfterSalesEvidencePlannerService(builder, objectMapper, "LLM", 4000, "test-key-123", 32) {
            @Override
            protected String callModel(String prompt) {
                modelCalled.set(true);
                return "{\"nextEvidence\":\"ORDER\",\"reasonCode\":\"ORDER_CONTEXT_REQUIRED\"}";
            }
        };
    }

    /** 无效输入结果的固定形态断言。 */
    private static void assertInvalidInput(AfterSalesTypes.PlanningResult result) {
        assertThat(result.invalidInput()).isTrue();
        assertThat(result.nextEvidence()).isEqualTo(EvidenceType.READY_FOR_DECISION);
        assertThat(result.reasonCode()).isEqualTo("INVALID_REQUIRED_EVIDENCE");
        assertThat(result.source()).isEqualTo("RULE_FALLBACK");
        assertThat(result.fallbackReason()).isEqualTo("INVALID_INPUT");
    }
}
