package com.ecommerce.service;

import com.ecommerce.config.FeatureStoreProperties;
import com.ecommerce.model.RecommendationRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisFeatureStoreServiceTest {
    @Test
    void concurrentWritesAndReadsDoNotLoseEventsOrExposeMutableLists() throws Exception {
        FeatureStoreProperties properties = new FeatureStoreProperties();
        properties.setMaxRecentEvents(2000);
        RedisFeatureStoreService service = service(properties, new AtomicLong());
        var executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int thread = 0; thread < 8; thread++) {
                int writer = thread;
                futures.add(executor.submit(() -> {
                    start.await();
                    for (int event = 0; event < 100; event++) {
                        service.recordBehavior("shared-user", "view", writer + ":" + event, Map.of());
                        assertThat((Integer) service.getUserFeatures("shared-user", request()).get("event_count"))
                                .isBetween(1, 800);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(20, TimeUnit.SECONDS);
            assertThat(service.getUserFeatures("shared-user", request()))
                    .containsEntry("event_count", 800).containsEntry("behavior_counts", Map.of("view", 800L));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void enforcesBothGlobalUserCapacityAndPerUserRecentEventBound() {
        FeatureStoreProperties properties = new FeatureStoreProperties();
        properties.setMaxCachedUsers(5);
        properties.setMaxRecentEvents(3);
        properties.setRecentEventPreviewSize(3);
        RedisFeatureStoreService service = service(properties, new AtomicLong());
        for (int index = 0; index < 100; index++) service.recordBehavior("user-" + index, "view", "item", Map.of());
        assertThat(service.cachedUserCount()).isLessThanOrEqualTo(5);
        for (int index = 0; index < 10; index++) service.recordBehavior("recent-user", "view", "item-" + index, Map.of());
        assertThat(service.getUserFeatures("recent-user", request())).containsEntry("event_count", 3);
        assertThat(recentEvents(service, "recent-user")).extracting(event -> event.get("product_id"))
                .containsExactly("item-9", "item-8", "item-7");
    }

    @Test
    void cacheExpiresAfterLastWriteAndReadsDoNotExtendRetention() {
        FeatureStoreProperties properties = new FeatureStoreProperties();
        properties.setMemoryTtl(Duration.ofSeconds(10));
        AtomicLong ticks = new AtomicLong();
        RedisFeatureStoreService service = service(properties, ticks);
        service.recordBehavior("user", "view", "item", Map.of());
        ticks.set(Duration.ofSeconds(9).toNanos());
        assertThat(service.getUserFeatures("user", request())).containsEntry("event_count", 1);
        ticks.set(Duration.ofSeconds(11).toNanos());
        assertThat(service.getUserFeatures("user", request())).containsEntry("event_count", 0);
        assertThat(service.cachedUserCount()).isZero();
    }

    @Test
    @SuppressWarnings("unchecked")
    void callerMutationCannotChangeCachedNestedMetadata() {
        RedisFeatureStoreService service = service(new FeatureStoreProperties(), new AtomicLong());
        Map<String, Object> nested = new HashMap<>(Map.of("preference", "original"));
        Map<String, Object> metadata = new HashMap<>(Map.of("nested", nested));
        service.recordBehavior("user", "view", "item", metadata);
        nested.put("preference", "changed-input");
        Map<String, Object> firstRead = (Map<String, Object>) recentEvents(service, "user").get(0).get("metadata");
        assertThat((Map<String, Object>) firstRead.get("nested")).containsEntry("preference", "original");
        ((Map<String, Object>) firstRead.get("nested")).put("preference", "changed-output");
        Map<String, Object> secondRead = (Map<String, Object>) recentEvents(service, "user").get(0).get("metadata");
        assertThat((Map<String, Object>) secondRead.get("nested")).containsEntry("preference", "original");
    }

    @Test
    void redisFailurePreservesLocalFallbackAndReportsAvailability() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        when(redis.execute(any(RedisScript.class), anyList(), anyString(), anyString(), anyString()))
                .thenThrow(new RedisConnectionFailureException("offline"));
        when(redis.opsForList()).thenThrow(new RedisConnectionFailureException("offline"));
        RedisFeatureStoreService service = new RedisFeatureStoreService(redis, null, new ObjectMapper(),
                new FeatureStoreProperties(), () -> 0L);
        assertThat(service.recordBehavior("user", "view", "item", Map.of()))
                .containsEntry("redis_status", "fallback_memory");
        assertThat(service.getUserFeatures("user", request()))
                .containsEntry("event_count", 1).containsEntry("source", "memory+redis_unavailable")
                .containsEntry("redis_available", false);
    }

    @Test
    void redisAppendTrimAndExpiryUseOneAtomicScriptWithConfiguredBounds() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        FeatureStoreProperties properties = new FeatureStoreProperties();
        properties.setMaxRecentEvents(7);
        properties.setRedisTtl(Duration.ofMillis(1500));
        RedisFeatureStoreService service = new RedisFeatureStoreService(redis, null, new ObjectMapper(), properties, () -> 0L);
        service.recordBehavior("user", "view", "item", Map.of());
        verify(redis).execute(any(RedisScript.class), eq(List.of("feature:user:user:behaviors")),
                anyString(), eq("7"), eq("1500"));
    }

    @Test
    void rejectsOversizedEventBeforeRetainingAnything() {
        FeatureStoreProperties properties = new FeatureStoreProperties();
        properties.setMaxEventBytes(256);
        RedisFeatureStoreService service = service(properties, new AtomicLong());
        assertThatThrownBy(() -> service.recordBehavior("user", "view", "item", Map.of("payload", "x".repeat(1024))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("max-event-bytes");
        assertThat(service.cachedUserCount()).isZero();
    }

    @Test
    void invalidBoundsFailAtConstruction() {
        FeatureStoreProperties properties = new FeatureStoreProperties();
        properties.setRedisTtl(Duration.ZERO);
        assertThatThrownBy(() -> service(properties, new AtomicLong())).isInstanceOf(IllegalStateException.class);
        properties.setRedisTtl(Duration.ofHours(1));
        properties.setMaxCachedUsers(0);
        assertThatThrownBy(() -> service(properties, new AtomicLong())).isInstanceOf(IllegalStateException.class);
    }

    private static RedisFeatureStoreService service(FeatureStoreProperties properties, AtomicLong ticks) {
        return new RedisFeatureStoreService(null, null, new ObjectMapper(), properties, ticks::get);
    }

    private static RecommendationRequest request() {
        return RecommendationRequest.builder().userId("user").context(Map.of("recent_views", List.of())).build();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> recentEvents(RedisFeatureStoreService service, String userId) {
        return (List<Map<String, Object>>) service.getUserFeatures(userId, request()).get("recent_events");
    }
}
