package com.ecommerce.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 售后副作用执行器与持久化任务调度参数。
 *
 * 所有容量、超时和重试参数都由配置提供，启动时失败关闭，避免非法配置在运行期
 * 静默退化。leaseDurationMs 必须大于外部连接器的最大调用超时。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.aftersales.execution")
public class AfterSalesExecutionProperties {
    private int maxAttempts = 3;
    private long retryDelayMs = 10_000L;
    private long retryScanMs = 5_000L;
    private int retryBatchSize = 10;
    private long leaseDurationMs = 120_000L;
    private String workerId = "local-worker";
    private final Executor executor = new Executor();

    @PostConstruct
    void validate() {
        requirePositive(maxAttempts, "max-attempts");
        requireNonNegative(retryDelayMs, "retry-delay-ms");
        requirePositive(retryScanMs, "retry-scan-ms");
        requirePositive(retryBatchSize, "retry-batch-size");
        requirePositive(leaseDurationMs, "lease-duration-ms");
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalStateException("agent.aftersales.execution.worker-id must not be blank");
        }
        executor.validate();
    }

    private static void requirePositive(long value, String property) {
        if (value <= 0) {
            throw new IllegalStateException("agent.aftersales.execution." + property + " must be positive");
        }
    }

    private static void requireNonNegative(long value, String property) {
        if (value < 0) {
            throw new IllegalStateException("agent.aftersales.execution." + property + " must not be negative");
        }
    }

    @Getter
    @Setter
    public static class Executor {
        private int coreSize = 2;
        private int maxSize = 8;
        private int queueCapacity = 64;
        private int keepAliveSeconds = 60;
        private int shutdownAwaitSeconds = 10;
        private String threadNamePrefix = "aftersales-exec-";
        private String rejectionPolicy = "ABORT";

        private void validate() {
            requirePositive(coreSize, "executor.core-size");
            requirePositive(maxSize, "executor.max-size");
            if (maxSize < coreSize) {
                throw new IllegalStateException(
                        "agent.aftersales.execution.executor.max-size must be >= core-size");
            }
            requireNonNegative(queueCapacity, "executor.queue-capacity");
            requireNonNegative(keepAliveSeconds, "executor.keep-alive-seconds");
            requireNonNegative(shutdownAwaitSeconds, "executor.shutdown-await-seconds");
            if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
                throw new IllegalStateException(
                        "agent.aftersales.execution.executor.thread-name-prefix must not be blank");
            }
            if (!"ABORT".equalsIgnoreCase(rejectionPolicy)
                    && !"CALLER_RUNS".equalsIgnoreCase(rejectionPolicy)) {
                throw new IllegalStateException(
                        "agent.aftersales.execution.executor.rejection-policy must be ABORT or CALLER_RUNS");
            }
        }
    }
}
