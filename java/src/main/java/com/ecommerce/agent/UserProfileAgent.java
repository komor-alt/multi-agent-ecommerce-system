package com.ecommerce.agent;

import com.ecommerce.model.AgentResult;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.RedisFeatureStoreService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * User profile Agent: Redis/request features + RFM-style profile + LLM parsing fallback.
 */
@Component
public class UserProfileAgent extends BaseAgent {

    private final ChatClient chatClient;
    private final RedisFeatureStoreService featureStoreService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String SYSTEM_PROMPT = """
            You are an ecommerce user profiling agent for Southeast Asia cross-border commerce.
            Analyze behavior features and return JSON only:
            {"segments":["active"],"preferred_categories":["phone"],"price_range":[0,2000],
             "rfm_score":{"recency":0.8,"frequency":0.5,"monetary":0.6},
             "real_time_tags":{"shopping_window":"evening"}}
            Do not invent platform, country, locale, or currency. Use the provided features.
            """;

    public UserProfileAgent(ChatClient.Builder chatClientBuilder, RedisFeatureStoreService featureStoreService) {
        super("user_profile", 5.0, 2);
        this.chatClient = chatClientBuilder.build();
        this.featureStoreService = featureStoreService;
    }

    @Override
    protected AgentResult execute(Map<String, Object> params) throws Exception {
        RecommendationRequest request = requestFrom(params);
        String userId = safeUserId(request);
        Map<String, Object> behavior = featureStoreService.getUserFeatures(userId, request);

        String response;
        UserProfile profile;
        try {
            response = chatClient.prompt()
                    .system(SYSTEM_PROMPT)
                    .user("User ID: " + userId + "\nBehavior features: " + objectMapper.writeValueAsString(behavior))
                    .call()
                    .content();
            profile = parseProfile(userId, response);
        } catch (Exception e) {
            response = "fallback_profile:" + e.getMessage();
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
        data.put("raw_analysis", response);
        data.put("profile", profile);
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
    private UserProfile parseProfile(String userId, String raw) {
        try {
            String cleaned = raw.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
                cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
            }
            Map<String, Object> data = objectMapper.readValue(cleaned, Map.class);

            List<String> segments = (List<String>) data.getOrDefault("segments", List.of("active"));
            List<String> categories = (List<String>) data.getOrDefault("preferred_categories", List.of("phone", "earbuds"));
            List<?> priceRaw = (List<?>) data.getOrDefault("price_range", List.of(0, 2000));
            Map<String, Double> rfm = (Map<String, Double>) data.getOrDefault("rfm_score", Map.of());
            Map<String, Object> tags = (Map<String, Object>) data.getOrDefault("real_time_tags", Map.of());

            return UserProfile.builder()
                    .userId(userId)
                    .segments(segments)
                    .preferredCategories(categories)
                    .priceRange(new double[]{
                            ((Number) priceRaw.get(0)).doubleValue(),
                            priceRaw.size() > 1 ? ((Number) priceRaw.get(1)).doubleValue() : 2000
                    })
                    .rfmScore(rfm)
                    .realTimeTags(tags)
                    .build();
        } catch (Exception e) {
            log.warn("Failed to parse profile for {}: {}", userId, e.getMessage());
            return fallbackProfile(userId, Map.of());
        }
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


