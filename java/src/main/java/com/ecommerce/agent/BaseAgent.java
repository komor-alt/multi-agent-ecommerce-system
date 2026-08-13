package com.ecommerce.agent;

import com.ecommerce.model.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Base agent with retry, timeout fallback, and basic in-agent counters.
 */
public abstract class BaseAgent {

    protected final Logger log = LoggerFactory.getLogger(getClass());
    protected final String name;
    protected final double timeoutSeconds;
    protected final int maxRetries;

    private final AtomicInteger callCount = new AtomicInteger(0);
    private final AtomicInteger errorCount = new AtomicInteger(0);

    protected BaseAgent(String name, double timeoutSeconds, int maxRetries) {
        this.name = name;
        this.timeoutSeconds = timeoutSeconds;
        this.maxRetries = maxRetries;
    }

    protected abstract AgentResult execute(Map<String, Object> params) throws Exception;

    public CompletableFuture<AgentResult> runAsync(Map<String, Object> params) {
        return CompletableFuture.completedFuture(runWithRetry(params));
    }

    public CompletableFuture<AgentResult> runAsync(Map<String, Object> params, Executor executor) {
        long timeoutMs = Math.max(1L, Math.round(timeoutSeconds * 1000));
        try {
            return CompletableFuture.supplyAsync(() -> runWithRetry(params), executor)
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(error -> {
                        Throwable root = unwrap(error);
                        errorCount.incrementAndGet();
                        if (root instanceof TimeoutException) {
                            return fallback(timeoutMs, new TimeoutException(name + " timeout after " + timeoutMs + " ms"));
                        }
                        return fallback(timeoutMs, root instanceof Exception exception ? exception : new RuntimeException(root));
                    });
        } catch (RejectedExecutionException e) {
            errorCount.incrementAndGet();
            return CompletableFuture.completedFuture(fallback(0.0, new RejectedExecutionException(name + " rejected by bounded executor", e)));
        }
    }

    protected AgentResult runWithRetry(Map<String, Object> params) {
        callCount.incrementAndGet();
        long start = System.nanoTime();
        int attempt = 0;
        Exception lastError = null;

        while (attempt < maxRetries) {
            try {
                AgentResult result = execute(params);
                double latency = (System.nanoTime() - start) / 1_000_000.0;
                result.setLatencyMs(latency);
                log.info("[{}] success in {}ms", name, String.format("%.1f", latency));
                return result;
            } catch (Exception e) {
                lastError = e;
                attempt++;
                log.warn("[{}] attempt {} failed: {}", name, attempt, e.getMessage());
                if (attempt < maxRetries) {
                    try {
                        Thread.sleep((long) (500 * Math.pow(2, attempt - 1)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        errorCount.incrementAndGet();
        double latency = (System.nanoTime() - start) / 1_000_000.0;
        return fallback(latency, lastError);
    }

    protected AgentResult fallback(double latencyMs, Exception e) {
        return AgentResult.builder()
                .agentName(name)
                .success(false)
                .latencyMs(latencyMs)
                .error(e != null ? e.getMessage() : "unknown error")
                .confidence(0.0)
                .build();
    }

    public double getErrorRate() {
        int calls = callCount.get();
        return calls == 0 ? 0.0 : (double) errorCount.get() / calls;
    }

    private Throwable unwrap(Throwable error) {
        if (error instanceof CompletionException && error.getCause() != null) {
            return error.getCause();
        }
        return error;
    }
}


