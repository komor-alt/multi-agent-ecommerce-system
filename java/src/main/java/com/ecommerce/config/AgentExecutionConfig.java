package com.ecommerce.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AgentExecutionConfig {

    @Bean(name = "agentExecutor")
    public Executor agentExecutor(
            @Value("${agent.executor.core-size:8}") int coreSize,
            @Value("${agent.executor.max-size:16}") int maxSize,
            @Value("${agent.executor.queue-capacity:32}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-run-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    @Bean(name = "sseExecutor")
    public Executor sseExecutor(
            @Value("${agent.sse.core-size:2}") int coreSize,
            @Value("${agent.sse.max-size:6}") int maxSize,
            @Value("${agent.sse.queue-capacity:16}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("agent-sse-");
        executor.setCorePoolSize(coreSize);
        executor.setMaxPoolSize(maxSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setWaitForTasksToCompleteOnShutdown(false);
        executor.initialize();
        return executor;
    }

    /**
     * Runs specialist orchestration tasks, not the connector/model calls owned by
     * agentExecutor. Keeping the pools separate prevents nested future joins from
     * starving the worker pool. CallerRuns provides bounded backpressure.
     */
    @Bean(name = "supervisorOrchestrationExecutor")
    public Executor supervisorOrchestrationExecutor(RecommendationOrchestrationProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(properties.getThreadNamePrefix());
        executor.setCorePoolSize(properties.getCoreSize());
        executor.setMaxPoolSize(properties.getMaxSize());
        executor.setQueueCapacity(properties.getQueueCapacity());
        executor.setKeepAliveSeconds(properties.getKeepAliveSeconds());
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.initialize();
        return executor;
    }

    /**
     * 售后副作用任务使用独立线程池，避免推荐 Agent 或 SSE 高峰挤占发券/退款执行容量。
     * ABORT 会由 ExecutionService 捕获并把已抢占任务重新放回持久化重试队列；
     * CALLER_RUNS 可用于需要调用方反压的部署。
     */
    @Bean(name = "afterSalesExecutionExecutor")
    public Executor afterSalesExecutionExecutor(AfterSalesExecutionProperties properties) {
        AfterSalesExecutionProperties.Executor settings = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix(settings.getThreadNamePrefix());
        executor.setCorePoolSize(settings.getCoreSize());
        executor.setMaxPoolSize(settings.getMaxSize());
        executor.setQueueCapacity(settings.getQueueCapacity());
        executor.setKeepAliveSeconds(settings.getKeepAliveSeconds());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(settings.getShutdownAwaitSeconds());
        executor.setRejectedExecutionHandler("CALLER_RUNS".equalsIgnoreCase(settings.getRejectionPolicy())
                ? new ThreadPoolExecutor.CallerRunsPolicy()
                : new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
