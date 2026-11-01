package com.ecommerce.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Per-run guard for real model calls.
 *
 * <p>Only opaque phase/fingerprint hashes are retained. Prompts, customer
 * messages, API keys, token estimates and raw model output are never stored.</p>
 */
public final class LlmCallBudget {
    private static final ThreadLocal<LlmCallBudget> CURRENT = new ThreadLocal<>();

    private final int maxCalls;
    private final CallLimiter callLimiter;
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger budgetBlocked = new AtomicInteger();
    private final AtomicInteger duplicateBlocked = new AtomicInteger();
    private final Set<String> fingerprints = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> callsByPhase = new ConcurrentHashMap<>();
    private final AtomicInteger fallbackCount = new AtomicInteger();
    private final AtomicInteger providerCallCount = new AtomicInteger();
    private final AtomicInteger usageCallCount = new AtomicInteger();
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private volatile Consumer<CallTrace> traceListener = ignored -> {};

    public LlmCallBudget(int maxCalls) {
        this(maxCalls, (phase, fingerprint) -> new Reservation(true, null));
    }

    public LlmCallBudget(int maxCalls, CallLimiter callLimiter) {
        this.maxCalls = Math.max(0, maxCalls);
        this.callLimiter = callLimiter == null
                ? (phase, fingerprint) -> new Reservation(true, null) : callLimiter;
    }

    /**
     * Atomically reserves one model call. A duplicate fingerprint or an
     * exhausted/zero budget returns false and never reserves a call.
     */
    public synchronized boolean tryAcquire(String phase, String fingerprint) {
        return reserve(phase, fingerprint).acquired();
    }

    public synchronized Reservation reserve(String phase, String fingerprint) {
        String safePhase = phase == null || phase.isBlank() ? "unknown" : phase;
        String opaqueFingerprint = digest(safePhase + ":" + (fingerprint == null ? "" : fingerprint));
        if (fingerprints.contains(opaqueFingerprint)) {
            duplicateBlocked.incrementAndGet();
            return new Reservation(false, "duplicate_call_blocked");
        }
        if (callCount.get() >= maxCalls) {
            budgetBlocked.incrementAndGet();
            return new Reservation(false, "llm_budget_exhausted");
        }
        Reservation external = callLimiter.reserve(safePhase, fingerprint);
        if (!external.acquired()) {
            if ("duplicate_call_blocked".equals(external.reason())) duplicateBlocked.incrementAndGet();
            else budgetBlocked.incrementAndGet();
            return external;
        }
        fingerprints.add(opaqueFingerprint);
        callCount.incrementAndGet();
        callsByPhase.computeIfAbsent(safePhase, ignored -> new AtomicInteger()).incrementAndGet();
        return new Reservation(true, null);
    }

    public void setTraceListener(Consumer<CallTrace> listener) {
        traceListener = listener == null ? ignored -> {} : listener;
    }

    public void recordTrace(CallTrace trace) {
        if (trace == null) return;
        if (trace.providerInvoked()) {
            providerCallCount.incrementAndGet();
            if (trace.usageStatus().equals("available")) {
                usageCallCount.incrementAndGet();
                if (trace.promptTokens() != null) promptTokens.addAndGet(trace.promptTokens());
                if (trace.completionTokens() != null) completionTokens.addAndGet(trace.completionTokens());
                if (trace.totalTokens() != null) totalTokens.addAndGet(trace.totalTokens());
            }
        }
        if (!"success".equals(trace.status())) fallbackCount.incrementAndGet();
        traceListener.accept(trace);
    }

    public int getCallCount() {
        return callCount.get();
    }

    public int getMaxCalls() {
        return maxCalls;
    }

    public static LlmCallBudget current() {
        return CURRENT.get();
    }

    public static Scope bind(LlmCallBudget budget) {
        LlmCallBudget previous = CURRENT.get();
        CURRENT.set(budget);
        return new Scope(previous);
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llmCallCount", callCount.get());
        result.put("maxLlmCalls", maxCalls);
        result.put("budgetBlockedCalls", budgetBlocked.get());
        result.put("duplicateCallsBlocked", duplicateBlocked.get());
        result.put("fallbackCount", fallbackCount.get());
        result.put("providerCallCount", providerCallCount.get());
        result.put("usageCallCount", usageCallCount.get());
        boolean hasUsage = usageCallCount.get() > 0;
        result.put("promptTokens", hasUsage ? promptTokens.get() : null);
        result.put("completionTokens", hasUsage ? completionTokens.get() : null);
        result.put("totalTokens", hasUsage ? totalTokens.get() : null);
        result.put("estimatedCost", null);
        String usageStatus = !hasUsage ? "unavailable"
                : usageCallCount.get() < providerCallCount.get() ? "partial" : "available";
        result.put("usageStatus", usageStatus);
        if (!"available".equals(usageStatus)) {
            result.put("usageUnavailableReason", "partial".equals(usageStatus)
                    ? "provider_usage_missing_for_some_calls" : "provider_usage_not_returned");
        }
        Map<String, Integer> byPhase = new LinkedHashMap<>();
        callsByPhase.keySet().stream().sorted().forEach(key -> byPhase.put(key, callsByPhase.get(key).get()));
        result.put("byPhase", byPhase);
        return result;
    }

    public record Reservation(boolean acquired, String reason) {}

    @FunctionalInterface
    public interface CallLimiter {
        Reservation reserve(String phase, String fingerprint);
    }

    public record CallTrace(String phase, String provider, String model, long latencyMs,
                            String status, String fallbackReason, Integer promptTokens,
                            Integer completionTokens, Integer totalTokens, String usageStatus,
                            boolean providerInvoked, Map<String, Object> diagnostics) {
        public CallTrace {
            diagnostics = diagnostics == null || diagnostics.isEmpty()
                    ? Map.of() : Map.copyOf(diagnostics);
        }

        public CallTrace(String phase, String provider, String model, long latencyMs,
                         String status, String fallbackReason, Integer promptTokens,
                         Integer completionTokens, Integer totalTokens, String usageStatus,
                         boolean providerInvoked) {
            this(phase, provider, model, latencyMs, status, fallbackReason, promptTokens,
                    completionTokens, totalTokens, usageStatus, providerInvoked, Map.of());
        }

        public Map<String, Object> asMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("phase", phase);
            value.put("provider", provider);
            value.put("model", model);
            value.put("latencyMs", latencyMs);
            value.put("status", status);
            value.put("fallbackReason", fallbackReason);
            value.put("promptTokens", promptTokens);
            value.put("completionTokens", completionTokens);
            value.put("totalTokens", totalTokens);
            value.put("usageStatus", usageStatus);
            value.put("providerInvoked", providerInvoked);
            if (!diagnostics.isEmpty()) value.put("diagnostics", diagnostics);
            return value;
        }
    }

    private static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte valueByte : bytes) {
                hex.append(String.format("%02x", valueByte));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public static final class Scope implements AutoCloseable {
        private final LlmCallBudget previous;

        private Scope(LlmCallBudget previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
