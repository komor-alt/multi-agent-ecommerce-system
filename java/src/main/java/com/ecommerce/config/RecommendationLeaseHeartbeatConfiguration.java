package com.ecommerce.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/** Isolates lease renewal from outbox delivery, database scans and SSE polling. */
@Configuration
public class RecommendationLeaseHeartbeatConfiguration {
    private static final Logger log = LoggerFactory.getLogger(RecommendationLeaseHeartbeatConfiguration.class);

    /**
     * A custom heartbeat scheduler makes Boot's scheduler auto-configuration
     * back off. Preserve an explicit default for every unqualified @Scheduled.
     */
    @Bean(name = "taskScheduler")
    @Primary
    public ThreadPoolTaskScheduler taskScheduler(
            @Value("${spring.task.scheduling.pool.size:4}") int poolSize,
            @Value("${spring.task.scheduling.thread-name-prefix:scheduling-}") String threadNamePrefix) {
        if (poolSize < 1) throw new IllegalArgumentException("Invalid shared scheduling pool size");
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        return scheduler;
    }

    @Bean(name = "recommendationHeartbeatScheduler")
    public ThreadPoolTaskScheduler recommendationHeartbeatScheduler(
            @Value("${agent.recovery.heartbeat-scheduler-pool-size:1}") int poolSize,
            @Value("${agent.recovery.heartbeat-shutdown-await-seconds:5}") int shutdownAwaitSeconds) {
        if (poolSize < 1 || shutdownAwaitSeconds < 0) {
            throw new IllegalArgumentException("Invalid recommendation heartbeat scheduler limits");
        }
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix("recommendation-heartbeat-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(false);
        scheduler.setAwaitTerminationSeconds(shutdownAwaitSeconds);
        scheduler.setErrorHandler(error -> log.warn("Recommendation heartbeat scheduling failed: {}",
                error.getClass().getSimpleName()));
        return scheduler;
    }
}
