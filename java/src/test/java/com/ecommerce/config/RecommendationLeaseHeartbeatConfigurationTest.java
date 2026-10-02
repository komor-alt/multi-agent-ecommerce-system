package com.ecommerce.config;

import com.ecommerce.service.RecommendationLeaseHeartbeatService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecommendationLeaseHeartbeatConfigurationTest {
    @Test
    void heartbeatKeepsRunningWhenTheSeparateSharedSchedulerIsSaturated() throws Exception {
        CountDownLatch sharedStarted = new CountDownLatch(1);
        CountDownLatch releaseShared = new CountDownLatch(1);
        CountDownLatch heartbeatRan = new CountDownLatch(1);
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test-scheduler", Map.of(
                    "spring.task.scheduling.pool.size", 1,
                    "agent.recovery.heartbeat-scheduler-pool-size", 1)));
            context.register(RecommendationLeaseHeartbeatConfiguration.class);
            context.refresh();
            ThreadPoolTaskScheduler shared = context.getBean("taskScheduler", ThreadPoolTaskScheduler.class);
            ThreadPoolTaskScheduler heartbeat = context.getBean("recommendationHeartbeatScheduler", ThreadPoolTaskScheduler.class);
            assertThat(shared).isNotSameAs(heartbeat);
            assertThat(shared.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            assertThat(heartbeat.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(1);
            shared.submit(() -> {
                sharedStarted.countDown();
                try {
                    releaseShared.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
            try {
                assertThat(sharedStarted.await(2, TimeUnit.SECONDS)).isTrue();
                heartbeat.submit(heartbeatRan::countDown);
                assertThat(heartbeatRan.await(2, TimeUnit.SECONDS)).isTrue();
                assertThat(releaseShared.getCount()).isEqualTo(1);
            } finally {
                releaseShared.countDown();
            }
        }
    }

    @Test
    void heartbeatMethodSelectsItsDedicatedScheduler() throws Exception {
        Scheduled scheduled = RecommendationLeaseHeartbeatService.class.getMethod("heartbeat")
                .getAnnotation(Scheduled.class);
        assertThat(scheduled.scheduler()).isEqualTo("recommendationHeartbeatScheduler");
    }

    @Test
    void invalidSchedulerLimitsFailFast() {
        RecommendationLeaseHeartbeatConfiguration configuration = new RecommendationLeaseHeartbeatConfiguration();
        assertThatThrownBy(() -> configuration.taskScheduler(0, "test-"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.recommendationHeartbeatScheduler(0, 5))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> configuration.recommendationHeartbeatScheduler(1, -1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
