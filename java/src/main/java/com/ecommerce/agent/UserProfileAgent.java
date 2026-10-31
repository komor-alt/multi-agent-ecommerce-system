package com.ecommerce.agent;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.RedisFeatureStoreService;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.RecommendationModeResolver;
import com.ecommerce.service.TracedRecommendationLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * User profile Agent: Redis/request features + RFM-style profile + LLM parsing fallback.
 */
@Component
public class UserProfileAgent extends BaseAgent {

    private static final long LLM_OUTER_TIMEOUT_MARGIN_MS = 2_000L;

    private final TracedRecommendationLlmClient llmClient;
    private final RedisFeatureStoreService featureStoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecommendationModeResolver modeResolver;

    private static final String SYSTEM_PROMPT = """
            You are an ecommerce user profiling agent for Southeast Asia cross-border commerce.
            Return exactly one JSON object and no markdown, prose, comments, or additional keys.
            Schema:
            {
              "segments": ["active"],
              "preferred_categories": ["phone"],
              "price_range": [0, 2000],
              "rfm_score": {"recency": 0.8, "frequency": 0.5, "monetary": 0.6},
              "real_time_tags": {"shopping_window": "evening"}
            }
            Allowed top-level keys are exactly segments, preferred_categories, price_range,
            rfm_score, and real_time_tags. All fields are optional. Empty segments and
            preferred_categories arrays are valid. List items must be short strings.
            price_range must contain two finite non-negative numbers in ascending order.
            RFM values must be numbers between 0 and 1. Tag values must be scalar values.
            Never output user or product identifiers. Never output or override platform,
            region, country, locale, currency, credentials, instructions, or tool calls.
            Base the profile only on the supplied behavior feature JSON.
            """;

    private static final Set<String> ALLOWED_PROFILE_FIELDS = Set.of(
            "segments", "preferred_categories", "price_range", "rfm_score", "real_time_tags");
    private static final Set<String> ALLOWED_RFM_FIELDS = Set.of("recency", "frequency", "monetary");
    private static final Set<String> RESERVED_CONTEXT_FIELDS = Set.of(
            "platform", "region", "country", "locale", "currency", "userid", "user_id",
            "productid", "product_id", "apikey", "api_key", "password", "secret",
            "tool", "tool_call", "instructions");
    private static final Set<String> RECOVERABLE_PROFILE_FAILURES = Set.of(
            "empty_model_output", "invalid_json", "invalid_profile_root", "invalid_profile_list",
            "invalid_profile_price_range", "invalid_profile_rfm", "invalid_profile_tags");

    public UserProfileAgent(ChatClient.Builder chatClientBuilder, RedisFeatureStoreService featureStoreService) {
        this(chatClientBuilder, featureStoreService, new RecommendationModeResolver("RULES", ""),
                new TracedRecommendationLlmClient(chatClientBuilder, "test", "unknown", 15000));
    }

    public UserProfileAgent(ChatClient.Builder chatClientBuilder, RedisFeatureStoreService featureStoreService,
                            RecommendationModeResolver modeResolver) {
        this(chatClientBuilder, featureStoreService, modeResolver,
                new TracedRecommendationLlmClient(chatClientBuilder, "test", "unknown", 15000));
    }

    @Autowired
    public UserProfileAgent(ChatClient.Builder chatClientBuilder, RedisFeatureStoreService featureStoreService,
                            RecommendationModeResolver modeResolver,
                            TracedRecommendationLlmClient llmClient) {
        super("user_profile", 5.0, 1);
        this.llmClient = llmClient;
        this.featureStoreService = featureStoreService;
        this.modeResolver = modeResolver;
    }

    @Override
    protected long executionTimeoutMs() {
        long perCallTimeout = Math.max(1L, llmClient.timeoutMs());
        long twoCallTimeout = perCallTimeout > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : perCallTimeout * 2;
        return timeoutWithMargin(twoCallTimeout, LLM_OUTER_TIMEOUT_MARGIN_MS);
    }

    @Override
    protected AgentResult execute(Map<String, Object> params) throws Exception {
        RecommendationRequest request = requestFrom(params);
        String userId = safeUserId(request);
        Map<String, Object> behavior = featureStoreService.getUserFeatures(userId, request);

        ProfileOutcome outcome;
        if (modeResolver.llmEnabled()) {
            LlmCallBudget budget = params.get("llmBudget") instanceof LlmCallBudget value ? value : null;
            String fingerprint = String.valueOf(params.getOrDefault("llmFingerprint", userId));
            outcome = resolveProfile(userId, behavior, budget, fingerprint);
        } else {
            outcome = new ProfileOutcome(fallbackProfile(userId, behavior), "rules_fallback", null,
                    profileDiagnostics("rules", false, null, null, null, 0, Map.of(), Map.of()));
        }
        UserProfile profile = outcome.profile();

        Map<String, Object> tags = profile.getRealTimeTags() == null ? new HashMap<>() : new HashMap<>(profile.getRealTimeTags());
        tags.put("platform", request.platformOrDefault());
        tags.put("region", request.regionOrDefault());
        tags.put("country", request.countryOrDefault());
        tags.put("locale", request.localeOrDefault());
        tags.put("currency", request.currencyOrDefault());
        profile.setRealTimeTags(tags);

        Map<String, Object> data = new HashMap<>();
        data.put("profile", profile);
        data.put("analysis_source", outcome.source());
        data.put("profile_diagnostics", outcome.diagnostics());
        if (outcome.fallbackReason() != null) data.put("llm_fallback_reason", outcome.fallbackReason());
        data.put("feature_source", behavior.get("source"));
        data.put("cross_border_context", crossBorderContext(request));
        data.put("features", behavior);

        return AgentResult.builder()
                .agentName(name)
                .success(true)
                .data(data)
                .confidence(0.85)
                .build();
    }

    private ProfileOutcome resolveProfile(String userId, Map<String, Object> behavior,
                                          LlmCallBudget budget, String fingerprint) throws Exception {
        String prompt = profilePrompt(behavior, null);
        try {
            UserProfile profile = llmClient.complete(name, SYSTEM_PROMPT, prompt, budget, fingerprint,
                    raw -> parseProfile(userId, raw));
            return new ProfileOutcome(profile, "llm", null,
                    profileDiagnostics("llm_success", false, null, null, null,
                            0, Map.of(), Map.of()));
        } catch (TracedRecommendationLlmClient.TracedLlmException firstFailure) {
            String firstReason = firstFailure.reason();
            if (recoverableProfileFailure(firstReason)) {
                String retrySkippedReason = retryUnavailableReason(budget);
                if (retrySkippedReason == null) {
                    try {
                        UserProfile profile = llmClient.complete(name, SYSTEM_PROMPT,
                                profilePrompt(behavior, firstReason), budget, fingerprint + ":retry:1",
                                raw -> parseProfile(userId, raw));
                        return new ProfileOutcome(profile, "llm_retry", null,
                                profileDiagnostics("llm_retry_success", false, firstReason, null,
                                        null, 1, firstFailure.diagnostics(), Map.of()));
                    } catch (TracedRecommendationLlmClient.TracedLlmException retryFailure) {
                        boolean retryBlocked = isBudgetReason(retryFailure.reason());
                        String blocked = retryBlocked ? retryFailure.reason() : null;
                        int retryCount = retryBlocked ? 0 : 1;
                        String finalReason = retryBlocked ? firstReason : retryFailure.reason();
                        log.warn("LLM profile retry fell back; firstReason={}, finalReason={}, retryCount={}",
                                firstReason, finalReason, retryCount);
                        return new ProfileOutcome(fallbackProfile(userId, behavior), "rules_fallback",
                                finalReason, profileDiagnostics("safe_fallback", true,
                                firstReason, finalReason, blocked, retryCount,
                                firstFailure.diagnostics(), retryFailure.diagnostics()));
                    }
                }
                llmClient.recordLocalFallback(name, budget, retrySkippedReason);
                log.warn("LLM profile retry skipped; firstReason={}, skipReason={}",
                        firstReason, retrySkippedReason);
                return new ProfileOutcome(fallbackProfile(userId, behavior), "rules_fallback",
                        firstReason, profileDiagnostics("safe_fallback", true, firstReason,
                        firstReason, retrySkippedReason, 0, firstFailure.diagnostics(), Map.of()));
            }
            log.warn("LLM profile fell back without retry; reason={}", firstReason);
            return new ProfileOutcome(fallbackProfile(userId, behavior), "rules_fallback", firstReason,
                    profileDiagnostics("safe_fallback", true, firstReason, firstReason,
                            null, 0, firstFailure.diagnostics(), Map.of()));
        }
    }

    private String profilePrompt(Map<String, Object> behavior, String correctionReason) throws Exception {
        StringBuilder prompt = new StringBuilder("Behavior features JSON:\n")
                .append(objectMapper.writeValueAsString(behavior));
        if (correctionReason != null) {
            prompt.append("\nThe previous response was rejected as ")
                    .append(correctionReason)
                    .append(". Correct only the JSON structure and return one schema-compliant object.");
        }
        return prompt.toString();
    }

    private boolean recoverableProfileFailure(String reason) {
        return RECOVERABLE_PROFILE_FAILURES.contains(reason);
    }

    private String retryUnavailableReason(LlmCallBudget budget) {
        if (budget == null) return "llm_budget_unavailable";
        return budget.getCallCount() >= budget.getMaxCalls() ? "llm_budget_exhausted" : null;
    }

    private boolean isBudgetReason(String reason) {
        return "llm_budget_exhausted".equals(reason)
                || "llm_budget_unavailable".equals(reason)
                || "duplicate_call_blocked".equals(reason);
    }

    private Map<String, Object> profileDiagnostics(String status, boolean fallbackUsed,
                                                   String firstReason, String finalReason,
                                                   String retrySkippedReason, int retryCount,
                                                   Map<String, Object> firstResponse,
                                                   Map<String, Object> finalResponse) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("status", status);
        diagnostics.put("fallbackUsed", fallbackUsed);
        diagnostics.put("firstFailureReason", firstReason);
        diagnostics.put("finalFailureReason", finalReason);
        diagnostics.put("retrySkippedReason", retrySkippedReason);
        diagnostics.put("retryCount", retryCount);
        diagnostics.put("firstResponseDiagnostics", firstResponse == null ? Map.of() : Map.copyOf(firstResponse));
        diagnostics.put("finalResponseDiagnostics", finalResponse == null ? Map.of() : Map.copyOf(finalResponse));
        return Collections.unmodifiableMap(diagnostics);
    }

    private UserProfile parseProfile(String userId, String raw) throws Exception {
        JsonNode data = profileObject(stripFence(raw));
        int unexpectedFields = 0;
        var fieldNames = data.fieldNames();
        while (fieldNames.hasNext()) {
            if (!ALLOWED_PROFILE_FIELDS.contains(fieldNames.next())) unexpectedFields++;
        }
        if (unexpectedFields > 0) {
            throw validation("unexpected_profile_fields", "root", "unexpected_fields",
                    Map.of("unexpectedFieldCount", unexpectedFields));
        }

        List<String> segments = profileList(data, "segments", List.of("active"), true, 8);
        List<String> categories = profileList(data, "preferred_categories", List.of(), false, 10);
        double[] priceRange = priceRange(data.get("price_range"));
        Map<String, Double> rfm = rfmScore(data.get("rfm_score"));
        Map<String, Object> tags = realTimeTags(data.get("real_time_tags"));
        return UserProfile.builder().userId(userId).segments(segments).preferredCategories(categories)
                .priceRange(priceRange).rfmScore(rfm).realTimeTags(tags).build();
    }

    private String stripFence(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (!cleaned.startsWith("```")) return cleaned;
        int firstLine = cleaned.indexOf('\n');
        int lastFence = cleaned.lastIndexOf("```");
        if (firstLine < 0 || lastFence <= firstLine) {
            throw validation("invalid_json", "root", "invalid_code_fence", Map.of());
        }
        return cleaned.substring(firstLine + 1, lastFence).trim();
    }

    private JsonNode profileObject(String cleaned) throws Exception {
        JsonNode root = objectMapper.readTree(cleaned);
        if (root != null && root.isArray() && root.size() == 1 && root.get(0).isObject()) {
            root = root.get(0);
        }
        if (root == null || !root.isObject()) {
            throw validation("invalid_profile_root", "root", "object_required", Map.of());
        }
        return root;
    }

    private List<String> profileList(JsonNode data, String field, List<String> missingDefault,
                                     boolean emptyUsesDefault, int limit) {
        JsonNode node = data.get(field);
        if (node == null || node.isNull()) return missingDefault;
        if (!node.isArray()) {
            throw validation("invalid_profile_list", field, "array_required", Map.of());
        }
        if (node.size() > limit) {
            throw validation("invalid_profile_list", field, "too_many_items", Map.of());
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (JsonNode item : node) {
            if (item == null || item.isNull()) {
                throw validation("invalid_profile_list", field, "string_items_required", Map.of());
            }
            if (item.isTextual() && item.asText().isBlank()) continue;
            if (!item.isTextual()) {
                throw validation("invalid_profile_list", field, "string_items_required", Map.of());
            }
            normalized.add(normalizeProfileLabel(item.asText(), field));
        }
        if (normalized.isEmpty() && emptyUsesDefault) return missingDefault;
        return List.copyOf(normalized);
    }

    private String normalizeProfileLabel(String raw, String field) {
        String value = raw == null ? "" : raw.trim();
        if (value.isBlank()) return value;
        if (value.length() > 40 || value.chars().anyMatch(Character::isISOControl)
                || !value.matches("[\\p{L}\\p{N} _&./'()+-]+")) {
            throw validation("invalid_profile_content", field, "unsafe_list_content", Map.of());
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", "_");
    }

    private double[] priceRange(JsonNode node) {
        if (node == null || node.isNull() || (node.isArray() && node.isEmpty())) {
            return new double[]{0, 2000};
        }
        if (!node.isArray() || node.size() != 2) {
            throw validation("invalid_profile_price_range", "price_range", "two_values_required", Map.of());
        }
        double min = finiteNumber(node.get(0), "price_range", "numeric_values_required");
        double max = finiteNumber(node.get(1), "price_range", "numeric_values_required");
        if (min < 0 || max < min || max > 1_000_000) {
            throw validation("invalid_profile_price_range", "price_range", "range_out_of_bounds", Map.of());
        }
        return new double[]{min, max};
    }

    private Map<String, Double> rfmScore(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) {
            throw validation("invalid_profile_rfm", "rfm_score", "object_required", Map.of());
        }
        Map<String, Double> values = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            if (!ALLOWED_RFM_FIELDS.contains(entry.getKey())) {
                throw validation("invalid_profile_rfm", "rfm_score", "unexpected_metric", Map.of());
            }
            double value = finiteNumber(entry.getValue(), "rfm_score", "numeric_values_required");
            if (value < 0 || value > 1) {
                throw validation("invalid_profile_rfm", "rfm_score", "score_out_of_bounds", Map.of());
            }
            values.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(values);
    }

    private Map<String, Object> realTimeTags(JsonNode node) {
        if (node == null || node.isNull()) return Map.of();
        if (!node.isObject()) {
            throw validation("invalid_profile_tags", "real_time_tags", "object_required", Map.of());
        }
        if (node.size() > 20) {
            throw validation("invalid_profile_tags", "real_time_tags", "too_many_tags", Map.of());
        }
        Map<String, Object> tags = new LinkedHashMap<>();
        var fields = node.fields();
        while (fields.hasNext()) {
            var entry = fields.next();
            String key = normalizeTagKey(entry.getKey());
            if (RESERVED_CONTEXT_FIELDS.contains(key)) {
                throw validation("profile_reserved_context_override", "real_time_tags",
                        "reserved_context_key", Map.of());
            }
            JsonNode value = entry.getValue();
            if (value == null || value.isNull()) continue;
            if (value.isBoolean()) {
                tags.put(key, value.booleanValue());
            } else if (value.isNumber()) {
                double number = value.doubleValue();
                if (!Double.isFinite(number)) {
                    throw validation("invalid_profile_tags", "real_time_tags", "non_finite_value", Map.of());
                }
                tags.put(key, value.numberValue());
            } else if (value.isTextual()) {
                String text = value.asText().trim();
                if (text.length() > 80 || text.chars().anyMatch(Character::isISOControl)
                        || text.matches(".*[<>{}].*")) {
                    throw validation("invalid_profile_content", "real_time_tags",
                            "unsafe_tag_content", Map.of());
                }
                if (!text.isBlank()) tags.put(key, text);
            } else {
                throw validation("invalid_profile_tags", "real_time_tags", "scalar_values_required", Map.of());
            }
        }
        return Collections.unmodifiableMap(tags);
    }

    private String normalizeTagKey(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("\\s+", "_");
        if (value.isBlank() || value.length() > 40 || !value.matches("[a-z0-9_.-]+")) {
            throw validation("invalid_profile_content", "real_time_tags", "unsafe_tag_key", Map.of());
        }
        return value;
    }

    private double finiteNumber(JsonNode node, String field, String issue) {
        double value;
        if (node != null && node.isNumber()) {
            value = node.doubleValue();
        } else if (node != null && node.isTextual()) {
            try {
                value = Double.parseDouble(node.asText().trim());
            } catch (NumberFormatException error) {
                throw validation(validationReason(field), field, issue, Map.of());
            }
        } else {
            throw validation(validationReason(field), field, issue, Map.of());
        }
        if (!Double.isFinite(value)) {
            throw validation(validationReason(field), field, "non_finite_value", Map.of());
        }
        return value;
    }

    private String validationReason(String field) {
        return "rfm_score".equals(field) ? "invalid_profile_rfm" : "invalid_profile_price_range";
    }

    private TracedRecommendationLlmClient.OutputValidationException validation(
            String reason, String field, String issue, Map<String, Object> additional) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("validationField", field);
        diagnostics.put("validationIssue", issue);
        if (additional != null) diagnostics.putAll(additional);
        return new TracedRecommendationLlmClient.OutputValidationException(reason, diagnostics);
    }

    @SuppressWarnings("unchecked")
    private UserProfile fallbackProfile(String userId, Map<String, Object> behavior) {
        List<String> topProducts = behavior.get("top_products") instanceof List<?> raw
                ? raw.stream().map(String::valueOf).toList()
                : List.of();
        List<String> categories = topProducts.stream()
                .map(this::categoryFromBehaviorValue)
                .distinct()
                .limit(3)
                .toList();
        if (categories.isEmpty()) {
            categories = List.of("phone", "earbuds", "accessory");
        }
        return UserProfile.builder()
                .userId(userId)
                .segments(List.of("active"))
                .preferredCategories(categories)
                .priceRange(new double[]{0, 2000})
                .rfmScore(Map.of("recency", 0.7, "frequency", 0.5, "monetary", 0.5))
                .realTimeTags(new HashMap<>())
                .build();
    }

    private String categoryFromBehaviorValue(String value) {
        String lower = value.toLowerCase();
        if (lower.contains("earbud") || lower.contains("headphone")) return "earbuds";
        if (lower.contains("charger") || lower.contains("case") || lower.contains("accessory")) return "accessory";
        if (lower.contains("tablet") || lower.contains("pad")) return "tablet";
        return "phone";
    }

    private RecommendationRequest requestFrom(Map<String, Object> params) {
        Object request = params.get("request");
        if (request instanceof RecommendationRequest recommendationRequest) {
            return recommendationRequest;
        }
        return RecommendationRequest.builder()
                .userId(String.valueOf(params.getOrDefault("userId", "anonymous")))
                .build();
    }


    private String safeUserId(RecommendationRequest request) {
        return request.getUserId() == null || request.getUserId().isBlank() ? "anonymous" : request.getUserId();
    }
    private Map<String, Object> crossBorderContext(RecommendationRequest request) {
        return Map.of(
                "platform", request.platformOrDefault(),
                "region", request.regionOrDefault(),
                "country", request.countryOrDefault(),
                "locale", request.localeOrDefault(),
                "currency", request.currencyOrDefault()
        );
    }

    private record ProfileOutcome(UserProfile profile, String source, String fallbackReason,
                                  Map<String, Object> diagnostics) {}
}


