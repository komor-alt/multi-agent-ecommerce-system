package com.ecommerce.service;

import com.ecommerce.model.RecommendationRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class RedisFeatureStoreService {

    private static final int MAX_RECENT_EVENTS = 20;

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final Map<String, List<Map<String, Object>>> memoryEvents = new ConcurrentHashMap<>();

    public RedisFeatureStoreService(ObjectProvider<StringRedisTemplate> redisTemplateProvider, ObjectMapper objectMapper) {
        this.redisTemplate = redisTemplateProvider.getIfAvailable();
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> recordBehavior(String userId, String behaviorType, String productId, Map<String, Object> metadata) {
        String safeUserId = safeUserId(userId);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("user_id", safeUserId);
        event.put("behavior_type", blankToDefault(behaviorType, "view"));
        event.put("product_id", blankToDefault(productId, "unknown"));
        event.put("metadata", metadata == null ? Map.of() : metadata);
        event.put("timestamp", Instant.now().toString());

        memoryEvents.computeIfAbsent(safeUserId, ignored -> new ArrayList<>()).add(event);
        trimMemory(safeUserId);

        if (redisTemplate != null) {
            try {
                String key = behaviorKey(safeUserId);
                redisTemplate.opsForList().leftPush(key, objectMapper.writeValueAsString(event));
                redisTemplate.opsForList().trim(key, 0, MAX_RECENT_EVENTS - 1);
            } catch (Exception ignored) {
                event.put("redis_status", "fallback_memory");
            }
        }
        return event;
    }

    public Map<String, Object> getUserFeatures(String userId, RecommendationRequest request) {
        String safeUserId = safeUserId(userId);
        List<Map<String, Object>> events = readEvents(safeUserId);
        if (events.isEmpty()) {
            events = fallbackEvents(safeUserId, request);
        }

        Map<String, Long> behaviorCounts = events.stream()
                .collect(Collectors.groupingBy(event -> String.valueOf(event.getOrDefault("behavior_type", "unknown")), Collectors.counting()));
        Map<String, Long> productCounts = events.stream()
                .collect(Collectors.groupingBy(event -> String.valueOf(event.getOrDefault("product_id", "unknown")), Collectors.counting()));
        List<String> topProducts = productCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
                .map(Map.Entry::getKey)
                .filter(id -> !"unknown".equals(id))
                .limit(5)
                .collect(Collectors.toList());

        Map<String, Object> features = new LinkedHashMap<>();
        features.put("user_id", safeUserId);
        features.put("platform", request.platformOrDefault());
        features.put("region", request.regionOrDefault());
        features.put("country", request.countryOrDefault());
        features.put("locale", request.localeOrDefault());
        features.put("currency", request.currencyOrDefault());
        features.put("event_count", events.size());
        features.put("behavior_counts", behaviorCounts);
        features.put("top_products", topProducts);
        features.put("recent_events", events.stream().limit(5).collect(Collectors.toList()));
        features.put("source", redisTemplate == null ? "memory_fallback" : "redis_or_memory_fallback");
        return features;
    }

    private List<Map<String, Object>> readEvents(String userId) {
        if (redisTemplate != null) {
            try {
                List<String> values = redisTemplate.opsForList().range(behaviorKey(userId), 0, MAX_RECENT_EVENTS - 1);
                if (values != null && !values.isEmpty()) {
                    List<Map<String, Object>> parsed = new ArrayList<>();
                    for (String value : values) {
                        parsed.add(objectMapper.readValue(value, new TypeReference<>() {}));
                    }
                    return parsed;
                }
            } catch (Exception ignored) {
                // Fall through to in-memory events.
            }
        }
        return memoryEvents.getOrDefault(userId, List.of());
    }

    private List<Map<String, Object>> fallbackEvents(String userId, RecommendationRequest request) {
        Map<String, Object> context = request.getContext() == null ? Map.of() : request.getContext();
        List<Map<String, Object>> events = new ArrayList<>();
        Object recentViews = context.getOrDefault("recent_views", List.of("phone", "earbuds", "charger"));
        if (recentViews instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                events.add(Map.of(
                        "user_id", userId,
                        "behavior_type", "view",
                        "product_id", String.valueOf(item),
                        "metadata", Map.of("source", "request_context")
                ));
            }
        }
        return events;
    }

    private void trimMemory(String userId) {
        List<Map<String, Object>> events = memoryEvents.get(userId);
        if (events != null && events.size() > MAX_RECENT_EVENTS) {
            memoryEvents.put(userId, new ArrayList<>(events.subList(events.size() - MAX_RECENT_EVENTS, events.size())));
        }
    }

    private String behaviorKey(String userId) {
        return "feature:user:" + userId + ":behaviors";
    }

    private String safeUserId(String userId) {
        return userId == null || userId.isBlank() ? "anonymous" : userId;
    }

    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

