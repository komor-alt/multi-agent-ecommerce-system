package com.ecommerce.agent;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.RedisFeatureStoreService;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.RecommendationModeResolver;
import com.ecommerce.service.TracedRecommendationLlmClient;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User profile Agent: Redis/request features + RFM-style profile + LLM parsing fallback.
 */
@Component
public class UserProfileAgent extends BaseAgent {

    private final TracedRecommendationLlmClient llmClient;
    private final RedisFeatureStoreService featureStoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecommendationModeResolver modeResolver;

    private static final String SYSTEM_PROMPT = """
            You are an ecommerce user profiling agent for Southeast Asia cross-border commerce.
            Analyze behavior features and return JSON only:
            {"segments":["active"],"preferred_categories":["phone"],"price_range":[0,2000],
             "rfm_score":{"recency":0.8,"frequency":0.5,"monetary":0.6},
             "real_time_tags":{"shopping_window":"evening"}}
            Do not invent platform, country, locale, or currency. Use the provided features.
            """;

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
    protected AgentResult execute(Map<String, Object> params) throws Exception {
        RecommendationRequest request = requestFrom(params);
        String userId = safeUserId(request);
        Map<String, Object> behavior = featureStoreService.getUserFeatures(userId, request);

        UserProfile profile;
        String fallbackReason = null;
        if (modeResolver.llmEnabled()) {
            try {
                LlmCallBudget budget = params.get("llmBudget") instanceof LlmCallBudget value ? value : null;
                profile = llmClient.complete(name, SYSTEM_PROMPT,
                        "User ID: " + userId + "\nBehavior features: " + objectMapper.writeValueAsString(behavior),
                        budget, String.valueOf(params.getOrDefault("llmFingerprint", userId)),
                        raw -> parseProfile(userId, raw));
            } catch (TracedRecommendationLlmClient.TracedLlmException failure) {
                fallbackReason = failure.reason();
                profile = fallbackProfile(userId, behavior);
            }
        } else {
            profile = fallbackProfile(userId, behavior);
        }

        Map<String, Object> tags = profile.getRealTimeTags() == null ? new HashMap<>() : new HashMap<>(profile.getRealTimeTags());
        tags.put("platform", request.platformOrDefault());
        tags.put("region", request.regionOrDefault());
        tags.put("country", request.countryOrDefault());
        tags.put("locale", request.localeOrDefault());
        tags.put("currency", request.currencyOrDefault());
        profile.setRealTimeTags(tags);

        Map<String, Object> data = new HashMap<>();
        data.put("profile", profile);
        data.put("analysis_source", fallbackReason == null && modeResolver.llmEnabled()
                ? "llm" : "rules_fallback");
        if (fallbackReason != null) data.put("llm_fallback_reason", fallbackReason);
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

    @SuppressWarnings("unchecked")
    private UserProfile parseProfile(String userId, String raw) throws Exception {
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstLine < 0 || lastFence <= firstLine) {
                throw new TracedRecommendationLlmClient.OutputValidationException("invalid_json");
            }
            cleaned = cleaned.substring(firstLine + 1, lastFence).trim();
        }
        Map<String, Object> data = objectMapper.readValue(cleaned, new TypeReference<>() {});
        List<String> segments = safeStrings(data.get("segments"), List.of("active"), 8, 40);
        List<String> categories = safeStrings(data.get("preferred_categories"),
                List.of("phone", "earbuds"), 10, 40);
        List<?> priceRaw = data.get("price_range") instanceof List<?> values ? values : List.of(0, 2000);
        if (priceRaw.isEmpty() || priceRaw.size() > 2
                || priceRaw.stream().anyMatch(value -> !(value instanceof Number))) {
            throw new TracedRecommendationLlmClient.OutputValidationException("invalid_profile_price_range");
        }
        double min = ((Number) priceRaw.get(0)).doubleValue();
        double max = priceRaw.size() > 1 ? ((Number) priceRaw.get(1)).doubleValue() : 2000;
        if (!Double.isFinite(min) || !Double.isFinite(max) || min < 0 || max < min || max > 1_000_000) {
            throw new TracedRecommendationLlmClient.OutputValidationException("invalid_profile_price_range");
        }
        Map<String, Double> rfm = new HashMap<>();
        if (data.get("rfm_score") instanceof Map<?, ?> values) {
            values.forEach((key, value) -> {
                if (key instanceof String name && value instanceof Number number
                        && Double.isFinite(number.doubleValue())) {
                    rfm.put(name.substring(0, Math.min(name.length(), 40)),
                            Math.max(0, Math.min(1, number.doubleValue())));
                }
            });
        }
        Map<String, Object> tags = new HashMap<>();
        if (data.get("real_time_tags") instanceof Map<?, ?> values) {
            values.entrySet().stream().limit(20).forEach(entry -> {
                if (entry.getKey() instanceof String key && entry.getValue() != null
                        && (entry.getValue() instanceof String || entry.getValue() instanceof Number
                        || entry.getValue() instanceof Boolean)) {
                    String value = String.valueOf(entry.getValue());
                    tags.put(key.substring(0, Math.min(key.length(), 40)),
                            value.substring(0, Math.min(value.length(), 80)));
                }
            });
        }
        return UserProfile.builder().userId(userId).segments(segments).preferredCategories(categories)
                .priceRange(new double[]{min, max}).rfmScore(rfm).realTimeTags(tags).build();
    }

    private List<String> safeStrings(Object raw, List<String> fallback, int limit, int maxLength) {
        if (!(raw instanceof List<?> values)) return fallback;
        List<String> safe = values.stream().filter(String.class::isInstance).map(String.class::cast)
                .filter(value -> !value.isBlank()).limit(limit)
                .map(value -> value.substring(0, Math.min(value.length(), maxLength))).toList();
        if (safe.size() != values.size() || safe.isEmpty()) {
            throw new TracedRecommendationLlmClient.OutputValidationException("invalid_profile_list");
        }
        return safe;
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
}


