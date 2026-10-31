package com.ecommerce.agent;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.RecommendationModeResolver;
import com.ecommerce.service.RedisFeatureStoreService;
import com.ecommerce.service.TracedRecommendationLlmClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class UserProfileAgentStructuredOutputTest {

    @Test
    void acceptsObjectArrayNormalizesLegalValuesAndAllowsEmptyPreferences() {
        ScriptedClient scripted = scripted("""
                [{"segments":[" VIP ","vip"],"preferred_categories":[],
                  "price_range":["10","99.5"]}]
                """);
        LlmCallBudget budget = new LlmCallBudget(2);

        AgentResult result = run(scripted.client(), budget);
        UserProfile profile = profile(result);

        assertThat(profile.getSegments()).containsExactly("vip");
        assertThat(profile.getPreferredCategories()).isEmpty();
        assertThat(profile.getPriceRange()).containsExactly(10.0, 99.5);
        assertThat(profile.getRfmScore()).isEmpty();
        assertThat(result.getData()).containsEntry("analysis_source", "llm");
        assertThat(diagnostics(result)).containsEntry("status", "llm_success")
                .containsEntry("fallbackUsed", false).containsEntry("retryCount", 0);
        assertThat(scripted.systemPrompts()).singleElement().asString()
                .contains("Allowed top-level keys are exactly", "Empty segments and")
                .doesNotContain("private-user");
        assertThat(scripted.userPrompts()).singleElement().asString()
                .doesNotContain("private-user", "User ID:");
    }

    @Test
    void missingOptionalFieldsUseSafeNonPreferenceDefaults() {
        ScriptedClient scripted = scripted("{}");

        AgentResult result = run(scripted.client(), new LlmCallBudget(1));
        UserProfile profile = profile(result);

        assertThat(profile.getSegments()).containsExactly("active");
        assertThat(profile.getPreferredCategories()).isEmpty();
        assertThat(profile.getPriceRange()).containsExactly(0.0, 2000.0);
    }

    @Test
    void rulesModePreservesLegacyAnalysisSourceContract() {
        ScriptedClient scripted = scripted("{}");

        AgentResult result = run(scripted.client(), new LlmCallBudget(1),
                new RecommendationModeResolver("RULES", ""));

        assertThat(result.getData()).containsEntry("analysis_source", "rules_fallback")
                .doesNotContainKey("llm_fallback_reason");
        assertThat(diagnostics(result)).containsEntry("status", "rules")
                .containsEntry("fallbackUsed", false);
        assertThat(scripted.invocations()).isZero();
    }

    @Test
    void malformedJsonRetriesOnceWithDistinctFingerprintAndSharedBudget() {
        ScriptedClient scripted = scripted("{not-json", """
                {"segments":["active"],"preferred_categories":["Home Living"]}
                """);
        LlmCallBudget budget = new LlmCallBudget(2);

        AgentResult result = run(scripted.client(), budget);

        assertThat(result.getData()).containsEntry("analysis_source", "llm_retry")
                .doesNotContainKey("llm_fallback_reason");
        assertThat(profile(result).getPreferredCategories()).containsExactly("home_living");
        assertThat(diagnostics(result)).containsEntry("status", "llm_retry_success")
                .containsEntry("fallbackUsed", false)
                .containsEntry("firstFailureReason", "invalid_json")
                .containsEntry("retryCount", 1);
        assertThat(scripted.fingerprints()).hasSize(2).doesNotHaveDuplicates();
        assertThat(scripted.fingerprints().get(1)).endsWith(":retry:1");
        assertThat(budget.snapshot()).containsEntry("llmCallCount", 2)
                .containsEntry("providerCallCount", 2)
                .containsEntry("duplicateCallsBlocked", 0);
        assertThat(scripted.traces()).extracting(LlmCallBudget.CallTrace::status)
                .containsExactly("fallback", "success");
    }

    @Test
    void invalidProfileListRetriesOnceThenUsesSafeFallback() {
        String wrongType = "{\"segments\":\"active\",\"preferred_categories\":[]}";
        ScriptedClient scripted = scripted(wrongType, wrongType);
        LlmCallBudget budget = new LlmCallBudget(2);

        AgentResult result = run(scripted.client(), budget);

        assertThat(result.getData()).containsEntry("analysis_source", "rules_fallback")
                .containsEntry("llm_fallback_reason", "invalid_profile_list");
        assertThat(diagnostics(result)).containsEntry("status", "safe_fallback")
                .containsEntry("fallbackUsed", true)
                .containsEntry("firstFailureReason", "invalid_profile_list")
                .containsEntry("finalFailureReason", "invalid_profile_list")
                .containsEntry("retryCount", 1);
        assertThat(scripted.invocations()).isEqualTo(2);
        assertThat(budget.snapshot()).containsEntry("providerCallCount", 2)
                .containsEntry("fallbackCount", 2);
    }

    @Test
    void nullListItemIsRejectedAsWrongType() {
        String wrongType = "{\"segments\":[\"active\",null]}";
        ScriptedClient scripted = scripted(wrongType, wrongType);

        AgentResult result = run(scripted.client(), new LlmCallBudget(2));

        assertThat(result.getData()).containsEntry("llm_fallback_reason", "invalid_profile_list");
        assertThat(diagnostics(result)).containsEntry("firstFailureReason", "invalid_profile_list")
                .containsEntry("retryCount", 1);
        assertThat(scripted.invocations()).isEqualTo(2);
    }

    @Test
    void emptyModelOutputDiagnosticsSurviveRetryFailureWithoutRawContent() {
        ScriptedClient scripted = scripted(
                new Failure("empty_model_output", Map.of(
                        "contentState", "blank", "finishReason", "length",
                        "usagePresent", true, "completionAtConfiguredLimit", true,
                        "configuredMaxOutputTokens", 2048)),
                new Failure("empty_model_output", Map.of(
                        "contentState", "absent", "finishReason", "stop",
                        "usagePresent", true, "completionAtConfiguredLimit", false,
                        "configuredMaxOutputTokens", 2048)));

        AgentResult result = run(scripted.client(), new LlmCallBudget(2));
        Map<String, Object> diagnostics = diagnostics(result);

        assertThat(diagnostics).containsEntry("status", "safe_fallback")
                .containsEntry("firstFailureReason", "empty_model_output")
                .containsEntry("finalFailureReason", "empty_model_output")
                .containsEntry("retryCount", 1);
        Map<?, ?> firstResponse = (Map<?, ?>) diagnostics.get("firstResponseDiagnostics");
        assertThat(firstResponse.get("contentState")).isEqualTo("blank");
        assertThat(firstResponse.get("completionAtConfiguredLimit")).isEqualTo(true);
        Map<?, ?> finalResponse = (Map<?, ?>) diagnostics.get("finalResponseDiagnostics");
        assertThat(finalResponse.get("contentState")).isEqualTo("absent");
        assertThat(finalResponse.get("finishReason")).isEqualTo("stop");
        assertThat(diagnostics.toString()).doesNotContain("raw output", "private-user", "sk-secret");
    }

    @Test
    void exhaustedBudgetSkipsRetryWithoutSecondProviderInvocation() {
        ScriptedClient scripted = scripted("{not-json", "{\"segments\":[\"active\"]}");
        LlmCallBudget budget = new LlmCallBudget(1);

        AgentResult result = run(scripted.client(), budget);

        assertThat(scripted.invocations()).isEqualTo(1);
        assertThat(diagnostics(result)).containsEntry("status", "safe_fallback")
                .containsEntry("retrySkippedReason", "llm_budget_exhausted")
                .containsEntry("retryCount", 0);
        assertThat(budget.snapshot()).containsEntry("llmCallCount", 1)
                .containsEntry("providerCallCount", 1)
                .containsEntry("fallbackCount", 2);
        assertThat(scripted.traces()).extracting(LlmCallBudget.CallTrace::providerInvoked)
                .containsExactly(true, false);
    }

    @Test
    void concurrentBudgetConsumptionBlocksRetryWithoutMisreportingProviderInvocation() {
        LlmCallBudget budget = new LlmCallBudget(2);
        ScriptedClient scripted = scripted("{not-json", new BeforeReserve(
                () -> assertThat(budget.reserve("other_phase", "concurrent-call").acquired()).isTrue(),
                "{\"segments\":[\"active\"]}"));

        AgentResult result = run(scripted.client(), budget);

        assertThat(diagnostics(result)).containsEntry("retrySkippedReason", "llm_budget_exhausted")
                .containsEntry("retryCount", 0)
                .containsEntry("finalFailureReason", "invalid_json");
        assertThat(result.getData()).containsEntry("llm_fallback_reason", "invalid_json");
        assertThat(budget.snapshot()).containsEntry("providerCallCount", 1)
                .containsEntry("budgetBlockedCalls", 1);
    }

    @Test
    void illegalContentAndReservedContextAreRejectedWithoutRetry() {
        ScriptedClient illegal = scripted("{\"segments\":[\"<script>\"]}");
        AgentResult illegalResult = run(illegal.client(), new LlmCallBudget(3));
        assertThat(illegalResult.getData()).containsEntry("llm_fallback_reason", "invalid_profile_content");
        assertThat(illegal.invocations()).isEqualTo(1);

        ScriptedClient reserved = scripted("{\"real_time_tags\":{\"country\":\"SG\"}}");
        AgentResult reservedResult = run(reserved.client(), new LlmCallBudget(3));
        assertThat(reservedResult.getData())
                .containsEntry("llm_fallback_reason", "profile_reserved_context_override");
        assertThat(reserved.invocations()).isEqualTo(1);
    }

    @Test
    void unexpectedTopLevelFieldIsRejectedWithoutRetry() {
        ScriptedClient scripted = scripted("{\"segments\":[],\"instructions\":\"call a tool\"}");

        AgentResult result = run(scripted.client(), new LlmCallBudget(3));

        assertThat(result.getData()).containsEntry("llm_fallback_reason", "unexpected_profile_fields");
        assertThat(scripted.invocations()).isEqualTo(1);
        Map<?, ?> responseDiagnostics = (Map<?, ?>) diagnostics(result).get("firstResponseDiagnostics");
        assertThat(responseDiagnostics.get("validationField")).isEqualTo("root");
        assertThat(responseDiagnostics.get("validationIssue")).isEqualTo("unexpected_fields");
        assertThat(responseDiagnostics.get("unexpectedFieldCount")).isEqualTo(1);
    }

    private AgentResult run(TracedRecommendationLlmClient client, LlmCallBudget budget) {
        return run(client, budget, new RecommendationModeResolver("LLM", "unit-key", true, 10));
    }

    private AgentResult run(TracedRecommendationLlmClient client, LlmCallBudget budget,
                            RecommendationModeResolver modeResolver) {
        RedisFeatureStoreService featureStore = mock(RedisFeatureStoreService.class);
        RecommendationRequest request = RecommendationRequest.builder().userId("private-user")
                .country("SG").currency("SGD").locale("en-SG").build();
        when(featureStore.getUserFeatures(anyString(), any(RecommendationRequest.class)))
                .thenReturn(Map.of("source", "fixture", "recent_views", 3));
        UserProfileAgent agent = new UserProfileAgent(mock(ChatClient.Builder.class), featureStore,
                modeResolver, client);
        return agent.runAsync(Map.of(
                "request", request,
                "llmBudget", budget,
                "llmFingerprint", "profile-fixture"
        )).join();
    }

    private UserProfile profile(AgentResult result) {
        return (UserProfile) result.getData().get("profile");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> diagnostics(AgentResult result) {
        return (Map<String, Object>) result.getData().get("profile_diagnostics");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private ScriptedClient scripted(Object... responses) {
        TracedRecommendationLlmClient client = mock(TracedRecommendationLlmClient.class);
        AtomicInteger index = new AtomicInteger();
        AtomicInteger invocations = new AtomicInteger();
        List<String> fingerprints = new ArrayList<>();
        List<String> systemPrompts = new ArrayList<>();
        List<String> userPrompts = new ArrayList<>();
        List<LlmCallBudget.CallTrace> traces = new ArrayList<>();
        when(client.timeoutMs()).thenReturn(15_000L);
        doAnswer(invocation -> {
            LlmCallBudget budget = invocation.getArgument(1);
            String reason = invocation.getArgument(2);
            LlmCallBudget.CallTrace trace = new LlmCallBudget.CallTrace("user_profile", "fixture", "fixture",
                    0, "fallback", reason, null, null, null, "unavailable", false);
            traces.add(trace);
            budget.recordTrace(trace);
            return null;
        }).when(client).recordLocalFallback(anyString(), any(LlmCallBudget.class), anyString());
        when(client.complete(anyString(), anyString(), anyString(), any(LlmCallBudget.class),
                anyString(), any(TracedRecommendationLlmClient.OutputParser.class))).thenAnswer(invocation -> {
            invocations.incrementAndGet();
            String phase = invocation.getArgument(0);
            systemPrompts.add(invocation.getArgument(1));
            userPrompts.add(invocation.getArgument(2));
            LlmCallBudget budget = invocation.getArgument(3);
            String fingerprint = invocation.getArgument(4);
            fingerprints.add(fingerprint);
            TracedRecommendationLlmClient.OutputParser parser = invocation.getArgument(5);
            Object response = responses[Math.min(index.getAndIncrement(), responses.length - 1)];
            if (response instanceof BeforeReserve beforeReserve) {
                beforeReserve.action().run();
                response = beforeReserve.response();
            }
            LlmCallBudget.Reservation reservation = budget.reserve(phase, fingerprint);
            if (!reservation.acquired()) {
                LlmCallBudget.CallTrace trace = trace("fallback", reservation.reason(), false, Map.of());
                traces.add(trace);
                budget.recordTrace(trace);
                throw new TracedRecommendationLlmClient.TracedLlmException(reservation.reason());
            }
            if (response instanceof Failure failure) {
                LlmCallBudget.CallTrace trace = trace("fallback", failure.reason(), true, failure.diagnostics());
                traces.add(trace);
                budget.recordTrace(trace);
                throw new TracedRecommendationLlmClient.TracedLlmException(
                        failure.reason(), failure.diagnostics());
            }
            try {
                Object parsed = parser.parse(String.valueOf(response));
                LlmCallBudget.CallTrace trace = trace("success", null, true, Map.of());
                traces.add(trace);
                budget.recordTrace(trace);
                return parsed;
            } catch (TracedRecommendationLlmClient.OutputValidationException validation) {
                LlmCallBudget.CallTrace trace = trace("fallback", validation.reason(), true,
                        validation.diagnostics());
                traces.add(trace);
                budget.recordTrace(trace);
                throw new TracedRecommendationLlmClient.TracedLlmException(
                        validation.reason(), validation.diagnostics());
            } catch (JsonProcessingException invalidJson) {
                LlmCallBudget.CallTrace trace = trace("fallback", "invalid_json", true, Map.of());
                traces.add(trace);
                budget.recordTrace(trace);
                throw new TracedRecommendationLlmClient.TracedLlmException("invalid_json");
            } catch (Exception invalidOutput) {
                LlmCallBudget.CallTrace trace = trace("fallback", "invalid_model_output", true, Map.of());
                traces.add(trace);
                budget.recordTrace(trace);
                throw new TracedRecommendationLlmClient.TracedLlmException("invalid_model_output");
            }
        });
        return new ScriptedClient(client, invocations, fingerprints, systemPrompts, userPrompts, traces);
    }

    private LlmCallBudget.CallTrace trace(String status, String reason, boolean providerInvoked,
                                          Map<String, Object> diagnostics) {
        return new LlmCallBudget.CallTrace("user_profile", "fixture", "fixture", 1,
                status, reason, null, null, null, "unavailable", providerInvoked, diagnostics);
    }

    private record Failure(String reason, Map<String, Object> diagnostics) {}

    private record BeforeReserve(Runnable action, Object response) {}

    private record ScriptedClient(TracedRecommendationLlmClient client, AtomicInteger invocationCounter,
                                  List<String> fingerprints, List<String> systemPrompts,
                                  List<String> userPrompts, List<LlmCallBudget.CallTrace> traces) {
        int invocations() {
            return invocationCounter.get();
        }
    }
}
