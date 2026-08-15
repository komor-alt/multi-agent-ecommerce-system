package com.ecommerce.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-run guard for real model calls.
 *
 * <p>Only opaque phase/fingerprint hashes are retained. Prompts, customer
 * messages, API keys, token estimates and raw model output are never stored.</p>
 */
public final class LlmCallBudget {
    private static final ThreadLocal<LlmCallBudget> CURRENT = new ThreadLocal<>();

    private final int maxCalls;
    private final AtomicInteger callCount = new AtomicInteger();
    private final AtomicInteger budgetBlocked = new AtomicInteger();
    private final AtomicInteger duplicateBlocked = new AtomicInteger();
    private final Set<String> fingerprints = ConcurrentHashMap.newKeySet();
    private final Map<String, AtomicInteger> callsByPhase = new ConcurrentHashMap<>();

    public LlmCallBudget(int maxCalls) {
        this.maxCalls = Math.max(0, maxCalls);
    }

    /**
     * Atomically reserves one model call. A duplicate fingerprint or an
     * exhausted/zero budget returns false and never reserves a call.
     */
    public synchronized boolean tryAcquire(String phase, String fingerprint) {
        String safePhase = phase == null || phase.isBlank() ? "unknown" : phase;
        String opaqueFingerprint = digest(safePhase + ":" + (fingerprint == null ? "" : fingerprint));
        if (fingerprints.contains(opaqueFingerprint)) {
            duplicateBlocked.incrementAndGet();
            return false;
        }
        if (callCount.get() >= maxCalls) {
            budgetBlocked.incrementAndGet();
            return false;
        }
        fingerprints.add(opaqueFingerprint);
        callCount.incrementAndGet();
        callsByPhase.computeIfAbsent(safePhase, ignored -> new AtomicInteger()).incrementAndGet();
        return true;
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
        result.put("promptTokens", null);
        result.put("completionTokens", null);
        result.put("estimatedCost", null);
        result.put("usageStatus", "unavailable");
        result.put("usageUnavailableReason", "provider_usage_not_returned");
        Map<String, Integer> byPhase = new LinkedHashMap<>();
        callsByPhase.keySet().stream().sorted().forEach(key -> byPhase.put(key, callsByPhase.get(key).get()));
        result.put("byPhase", byPhase);
        return result;
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