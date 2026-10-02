package com.ecommerce.service;

import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.config.FeatureStoreProperties;
import com.ecommerce.data.RecommendationDataService;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class RedisFeatureStoreService {

    private static final DefaultRedisScript<Long> APPEND_RECENT_EVENT = new DefaultRedisScript<>("""
            redis.call('LPUSH', KEYS[1], ARGV[1])
            redis.call('LTRIM', KEYS[1], 0, tonumber(ARGV[2]) - 1)
            redis.call('PEXPIRE', KEYS[1], ARGV[3])
            return redis.call('LLEN', KEYS[1])
            """, Long.class);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final RecommendationDataService recommendationDataService;
    private final Cache<String, List<String>> memoryEvents;
    private final FeatureStoreProperties properties;

    public RedisFeatureStoreService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                    ObjectProvider<RecommendationDataService> dataServiceProvider,
                                    ObjectMapper objectMapper) {
        this(redisTemplateProvider, dataServiceProvider, objectMapper, new FeatureStoreProperties());
    }

    @Autowired
    public RedisFeatureStoreService(ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                                    ObjectProvider<RecommendationDataService> dataServiceProvider,
                                    ObjectMapper objectMapper,
                                    FeatureStoreProperties properties) {
        this(redisTemplateProvider.getIfAvailable(), dataServiceProvider.getIfAvailable(),
                objectMapper, properties, Ticker.systemTicker());
    }

    RedisFeatureStoreService(StringRedisTemplate redisTemplate, RecommendationDataService recommendationDataService,
                             ObjectMapper objectMapper, FeatureStoreProperties properties, Ticker ticker) {
        properties.validate();
        this.redisTemplate = redisTemplate;
        this.recommendationDataService = recommendationDataService;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.memoryEvents = Caffeine.newBuilder().maximumSize(properties.getMaxCachedUsers())
                .expireAfterWrite(properties.getMemoryTtl()).ticker(ticker).build();
    }

    public Map<String, Object> recordBehavior(String userId, String behaviorType, String productId, Map<String, Object> metadata) {
        String safeUserId = safeUserId(userId);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("user_id", safeUserId);
        event.put("behavior_type", blankToDefault(behaviorType, "view"));
        event.put("product_id", blankToDefault(productId, "unknown"));
        event.put("metadata", metadata == null ? Map.of() : metadata);
        event.put("timestamp", Instant.now().toString());

        String serializedEvent = serializeEvent(event);
        // Per-key compute is atomic; readers see immutable snapshots. Strings also isolate nested metadata
        // from caller mutations without retaining caller-owned Maps or returning cache-owned objects.
        memoryEvents.asMap().compute(safeUserId, (ignored, previous) -> {
            List<String> next = new ArrayList<>();
            next.add(serializedEvent);
            if (previous != null) next.addAll(previous.subList(0,
                    Math.min(previous.size(), properties.getMaxRecentEvents() - 1)));
            return List.copyOf(next);
        });
        if (recommendationDataService != null) {
            recommendationDataService.recordUserEvent(safeUserId, String.valueOf(event.get("behavior_type")),
                    String.valueOf(event.get("product_id")), metadata == null ? Map.of() : metadata);
        }

        if (redisTemplate != null) {
            try {
                String key = behaviorKey(safeUserId);
                // List append, trimming and TTL must succeed atomically even if the process exits immediately.
                redisTemplate.execute(APPEND_RECENT_EVENT, List.of(key), serializedEvent,
                        String.valueOf(properties.getMaxRecentEvents()), String.valueOf(properties.getRedisTtl().toMillis()));
            } catch (Exception ignored) {
                event.put("redis_status", "fallback_memory");
            }
        }
        return event;
    }

    public Map<String, Object> getUserFeatures(String userId, RecommendationRequest request) {
        String safeUserId = safeUserId(userId);
        FeatureRead featureRead = readEvents(safeUserId);
        List<Map<String, Object>> events = new ArrayList<>(featureRead.events());
        if (recommendationDataService != null) {
            List<Map<String, Object>> durable = recommendationDataService.recentUserEvents(safeUserId, properties.getMaxRecentEvents());
            for (Map<String, Object> event : durable) {
                if (events.size() >= properties.getMaxRecentEvents()) break;
                if (!events.contains(event)) events.add(event);
            }
        }
        boolean requestFallback = events.isEmpty();
        if (requestFallback) {
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
        features.put("recent_events", events.stream().limit(properties.getRecentEventPreviewSize()).collect(Collectors.toList()));
        String source = featureRead.source();
        if (recommendationDataService != null) {
            source = "redis".equals(source) ? "redis+postgresql" : "postgresql+" + source;
        }
        if (requestFallback) source = source + "+request_context";
        features.put("source", source);
        features.put("redis_available", redisTemplate != null && !source.contains("redis_unavailable"));
        return features;
    }

    private FeatureRead readEvents(String userId) {
        if (redisTemplate != null) {
            try {
                List<String> values = redisTemplate.opsForList().range(behaviorKey(userId), 0, properties.getMaxRecentEvents() - 1);
                if (values != null && !values.isEmpty()) {
                    List<Map<String, Object>> parsed = new ArrayList<>();
                    for (String value : values) {
                        parsed.add(objectMapper.readValue(value, new TypeReference<>() {}));
                    }
                    return new FeatureRead(parsed, "redis");
                }
                return new FeatureRead(memorySnapshot(userId), "redis_empty");
            } catch (Exception ignored) {
                return new FeatureRead(memorySnapshot(userId), "memory+redis_unavailable");
            }
        }
        return new FeatureRead(memorySnapshot(userId), "memory");
    }

    private record FeatureRead(List<Map<String, Object>> events, String source) {}

    private List<Map<String, Object>> fallbackEvents(String userId, RecommendationRequest request) {
        Map<String, Object> context = request.getContext() == null ? Map.of() : request.getContext();
        List<Map<String, Object>> events = new ArrayList<>();
        Object recentViews = context.getOrDefault("recent_views", List.of("phone", "earbuds", "charger"));
        if (recentViews instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (events.size() >= properties.getMaxRecentEvents()) break;
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

    private List<Map<String, Object>> memorySnapshot(String userId) {
        List<String> snapshot = memoryEvents.getIfPresent(userId);
        if (snapshot == null) return List.of();
        try {
            List<Map<String, Object>> events = new ArrayList<>(snapshot.size());
            for (String value : snapshot) events.add(objectMapper.readValue(value, new TypeReference<>() {}));
            return events;
        } catch (Exception error) {
            throw new IllegalStateException("Invalid local feature cache entry", error);
        }
    }

    private String serializeEvent(Map<String, Object> event) {
        try {
            byte[] bytes = objectMapper.writeValueAsBytes(event);
            if (bytes.length > properties.getMaxEventBytes()) {
                throw new IllegalArgumentException("Behavior event exceeds agent.feature-store.max-event-bytes");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalArgumentException("Behavior event must contain JSON-serializable metadata", error);
        }
    }

    long cachedUserCount() {
        memoryEvents.cleanUp();
        return memoryEvents.estimatedSize();
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

