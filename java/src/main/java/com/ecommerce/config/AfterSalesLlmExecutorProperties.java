package com.ecommerce.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 售后 Intake 与 Evidence Planner 的隔离 LLM 执行器参数。
 *
 * 两个阶段使用独立线程池，避免慢模型调用相互挤占；容量由环境配置提供，
 * 并在应用启动时校验，防止非法参数直到首次请求才暴露。
 */
@Getter
@Component
@ConfigurationProperties(prefix = "agent.aftersales.llm-executors")
public class AfterSalesLlmExecutorProperties {
    private final Pool intake = Pool.defaults("aftersales-intake-llm-");
    private final Pool planner = Pool.defaults("aftersales-planner-llm-");

    @PostConstruct
    void validate() {
        intake.validate("intake");
        planner.validate("planner");
    }

    public static Pool intakeDefaults() {
        return Pool.defaults("aftersales-intake-llm-");
    }

    public static Pool plannerDefaults() {
        return Pool.defaults("aftersales-planner-llm-");
    }

    @Getter
    @Setter
    public static class Pool {
        private int coreSize;
        private int maxSize;
        private int queueCapacity;
        private long keepAliveSeconds;
        private String threadNamePrefix;

        private static Pool defaults(String threadNamePrefix) {
            Pool pool = new Pool();
            pool.coreSize = 1;
            pool.maxSize = 2;
            pool.queueCapacity = 4;
            pool.keepAliveSeconds = 30L;
            pool.threadNamePrefix = threadNamePrefix;
            return pool;
        }

        private void validate(String stage) {
            String prefix = "agent.aftersales.llm-executors." + stage + ".";
            if (coreSize <= 0) {
                throw new IllegalStateException(prefix + "core-size must be positive");
            }
            if (maxSize < coreSize) {
                throw new IllegalStateException(prefix + "max-size must be >= core-size");
            }
            if (queueCapacity <= 0) {
                throw new IllegalStateException(prefix + "queue-capacity must be positive");
            }
            if (keepAliveSeconds < 0) {
                throw new IllegalStateException(prefix + "keep-alive-seconds must not be negative");
            }
            if (threadNamePrefix == null || threadNamePrefix.isBlank()) {
                throw new IllegalStateException(prefix + "thread-name-prefix must not be blank");
            }
        }
    }
}
