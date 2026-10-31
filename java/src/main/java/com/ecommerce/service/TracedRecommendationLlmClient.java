package com.ecommerce.service;

import jakarta.annotation.PreDestroy;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.DistributionSummary;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Shared bounded ChatClient adapter for recommendation LLM calls. */
@Service
public class TracedRecommendationLlmClient {
    private final ChatClient chatClient;
    private final String provider;
    private final String configuredModel;
    private final long timeoutMs;
    private final ThreadPoolExecutor executor;
    private final MeterRegistry meters;

    @Autowired
    public TracedRecommendationLlmClient(
            ChatClient.Builder chatClientBuilder,
            @Value("${agent.recommend.provider:openai-compatible}") String provider,
            @Value("${spring.ai.openai.chat.options.model:unknown}") String model,
            @Value("${agent.recommend.llm-timeout-ms:15000}") long timeoutMs,
            MeterRegistry meters) {
        this(chatClientBuilder.build(), provider, model, timeoutMs, createExecutor(), meters);
    }

    public TracedRecommendationLlmClient(ChatClient.Builder chatClientBuilder, String provider,
                                         String model, long timeoutMs) {
        this(chatClientBuilder.build(), provider, model, timeoutMs, createExecutor(), null);
    }

    TracedRecommendationLlmClient(ChatClient chatClient, String provider, String model,
                                  long timeoutMs, ThreadPoolExecutor executor) {
        this(chatClient, provider, model, timeoutMs, executor, null);
    }

    TracedRecommendationLlmClient(ChatClient chatClient, String provider, String model,
                                  long timeoutMs, ThreadPoolExecutor executor, MeterRegistry meters) {
        this.chatClient = chatClient;
        this.provider = safeLabel(provider, "openai-compatible");
        this.configuredModel = safeLabel(model, "unknown");
        this.timeoutMs = Math.max(1, timeoutMs);
        this.executor = executor;
        this.meters = meters;
    }

    /** Effective provider wait timeout after constructor normalization. */
    public long timeoutMs() {
        return timeoutMs;
    }

    public <T> T complete(String phase, String systemPrompt, String userPrompt,
                          LlmCallBudget budget, String fingerprint, OutputParser<T> parser) {
        return completeResponse(phase, budget, fingerprint, parser, () -> chatClient.prompt()
                .system(systemPrompt == null ? "" : systemPrompt)
                .user(userPrompt == null ? "" : userPrompt)
                .call().chatResponse());
    }

    <T> T completeResponse(String phase, LlmCallBudget budget, String fingerprint,
                           OutputParser<T> parser, java.util.concurrent.Callable<ChatResponse> invocation) {
        return execute(phase, budget, fingerprint, parser, invocation);
    }

    void recordLocalFallback(String phase, LlmCallBudget budget, String reason) {
        if (budget == null) return;
        recordTrace(budget, new LlmCallBudget.CallTrace(metricPhase(phase), provider,
                configuredModel, 0, "fallback", safeLabel(reason, "local_processing_failed"),
                null, null, null, "unavailable", false));
    }

    /** Test seam for injected deterministic planner clients; production uses complete(). */
    public <T> T completeInjected(String phase, LlmCallBudget budget, String fingerprint,
                                  java.util.function.Supplier<String> response,
                                  OutputParser<T> parser) {
        return execute(phase, budget, fingerprint, parser, () -> {
            String text = response.get();
            return new ChatResponse(java.util.List.of(new org.springframework.ai.chat.model.Generation(
                    new org.springframework.ai.chat.messages.AssistantMessage(text == null ? "" : text))));
        });
    }

    private <T> T execute(String phase, LlmCallBudget budget, String fingerprint,
                          OutputParser<T> parser, java.util.concurrent.Callable<ChatResponse> invocation) {
        String safePhase = safeLabel(phase, "unknown");
        if (budget == null) throw new TracedLlmException("llm_budget_unavailable");
        LlmCallBudget.Reservation reservation = budget.reserve(safePhase, fingerprint);
        if (!reservation.acquired()) {
            recordTrace(budget, new LlmCallBudget.CallTrace(metricPhase(safePhase), provider, configuredModel, 0,
                    "fallback", reservation.reason(), null, null, null, "unavailable", false));
            throw new TracedLlmException(reservation.reason());
        }

        long start = System.nanoTime();
        Future<ChatResponse> future = null;
        ChatResponse response = null;
        Usage usage = null;
        String model = configuredModel;
        boolean providerInvoked = false;
        try {
            future = executor.submit(invocation);
            providerInvoked = true;
            response = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (response != null && response.getMetadata() != null) {
                if (response.getMetadata().getModel() != null && !response.getMetadata().getModel().isBlank()) {
                    model = safeLabel(response.getMetadata().getModel(), configuredModel);
                }
                usage = response.getMetadata().getUsage();
            }
            String text = response == null || response.getResult() == null
                    || response.getResult().getOutput() == null
                    ? null : response.getResult().getOutput().getText();
            if (text == null || text.isBlank()) {
                throw new TracedLlmException("empty_model_output");
            }
            T parsed;
            try {
                parsed = parser.parse(text);
            } catch (OutputValidationException validation) {
                throw new TracedLlmException(validation.reason());
            } catch (Exception parseFailure) {
                throw new TracedLlmException(parseFailure instanceof com.fasterxml.jackson.core.JsonProcessingException
                        ? "invalid_json" : "invalid_model_output");
            }
            recordTrace(budget, trace(safePhase, model, start, "success", null, usage, true));
            return parsed;
        } catch (TimeoutException timeout) {
            if (future != null) future.cancel(true);
            recordFailure(budget, safePhase, model, start, "provider_timeout", usage, providerInvoked);
            throw new TracedLlmException("provider_timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            if (future != null) future.cancel(true);
            recordFailure(budget, safePhase, model, start, "call_interrupted", usage, providerInvoked);
            throw new TracedLlmException("call_interrupted");
        } catch (ExecutionException execution) {
            String reason = causedByTimeout(execution) ? "provider_timeout" : "provider_error";
            recordFailure(budget, safePhase, model, start, reason, usage, providerInvoked);
            throw new TracedLlmException(reason);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            recordFailure(budget, safePhase, model, start, "llm_executor_saturated", usage, false);
            throw new TracedLlmException("llm_executor_saturated");
        } catch (TracedLlmException failure) {
            recordFailure(budget, safePhase, model, start, failure.reason(), usage, providerInvoked);
            throw failure;
        } catch (RuntimeException failure) {
            recordFailure(budget, safePhase, model, start, "provider_error", usage, providerInvoked);
            throw new TracedLlmException("provider_error");
        }
    }

    private LlmCallBudget.CallTrace trace(String phase, String model, long start, String status,
                                          String reason, Usage usage, boolean providerInvoked) {
        Integer promptTokens = usage == null ? null : usage.getPromptTokens();
        Integer completionTokens = usage == null ? null : usage.getCompletionTokens();
        Integer totalTokens = usage == null ? null : usage.getTotalTokens();
        if (totalTokens == null && promptTokens != null && completionTokens != null) {
            totalTokens = promptTokens + completionTokens;
        }
        String usageStatus = promptTokens == null && completionTokens == null && totalTokens == null
                ? "unavailable" : "available";
        return new LlmCallBudget.CallTrace(phase, provider, model, elapsedMs(start), status,
                reason, promptTokens, completionTokens, totalTokens, usageStatus, providerInvoked);
    }

    private void recordFailure(LlmCallBudget budget, String phase, String model, long start,
                               String reason, Usage usage, boolean providerInvoked) {
        recordTrace(budget, trace(phase, model, start, "fallback", reason, usage, providerInvoked));
    }

    private void recordTrace(LlmCallBudget budget, LlmCallBudget.CallTrace trace) {
        budget.recordTrace(trace);
        if (meters == null) return;
        Tags tags = Tags.of("phase", metricPhase(trace.phase()), "provider", provider,
                "model", safeLabel(configuredModel, "configured"));
        String outcome = "success".equals(trace.status()) ? "success" : "fallback";
        meters.counter("agent.llm.calls", tags.and("outcome", outcome)).increment();
        Timer.builder("agent.llm.duration")
                .tags(tags.and("outcome", outcome))
                .publishPercentileHistogram()
                .serviceLevelObjectives(java.time.Duration.ofMillis(250), java.time.Duration.ofSeconds(1),
                        java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(15))
                .register(meters)
                .record(Math.max(0, trace.latencyMs()), TimeUnit.MILLISECONDS);
        if (!"success".equals(trace.status())) {
            meters.counter("agent.llm.failures", tags.and("reason", metricFailureReason(trace.fallbackReason()))).increment();
        }
        if (!trace.providerInvoked() && isBudgetReason(trace.fallbackReason())) {
            meters.counter("agent.llm.budget.blocked", tags.and("reason", metricBudgetReason(trace.fallbackReason()))).increment();
        }
        if (trace.providerInvoked() && "available".equals(trace.usageStatus())) {
            recordTokens(tags, "input", trace.promptTokens());
            recordTokens(tags, "output", trace.completionTokens());
            recordTokens(tags, "total", trace.totalTokens());
        } else if (trace.providerInvoked()) {
            meters.counter("agent.llm.usage.unavailable", tags).increment();
        }
    }

    private void recordTokens(Tags tags, String type, Integer value) {
        if (value == null || value < 0) return;
        DistributionSummary.builder("agent.llm.tokens")
                .baseUnit("tokens")
                .tags(tags.and("token_type", type))
                .register(meters)
                .record(value);
    }

    private static String metricPhase(String value) {
        String phase = value == null ? "" : value;
        if (java.util.Set.of("scene_intent", "supervisor", "final_decision", "product_rerank")
                .contains(phase)) return phase;
        if (phase.matches("specialist_(profile|product|inventory|copy)")) return phase;
        return "other";
    }

    private static String metricBudgetReason(String value) {
        if ("llm_budget_exhausted".equals(value) || "duplicate_call_blocked".equals(value)
                || "llm_budget_unavailable".equals(value)) return value;
        return "other";
    }

    private static boolean isBudgetReason(String value) {
        return "llm_budget_exhausted".equals(value) || "duplicate_call_blocked".equals(value)
                || "llm_budget_unavailable".equals(value);
    }

    private static String metricFailureReason(String value) {
        if (value != null && java.util.Set.of("provider_timeout", "provider_error", "llm_executor_saturated",
                "call_interrupted", "empty_model_output", "invalid_json", "invalid_model_output",
                "tool_outside_candidate_set", "unexpected_decision_fields", "invalid_decision_summary",
                "invalid_product_reasons", "product_outside_final_selection").contains(value)) return value;
        return "other";
    }

    private static boolean causedByTimeout(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof TimeoutException || current instanceof java.net.SocketTimeoutException) return true;
        }
        return false;
    }

    private static long elapsedMs(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static String safeLabel(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim().replaceAll("[^A-Za-z0-9._:/-]", "_");
        return normalized.length() > 100 ? normalized.substring(0, 100) : normalized;
    }

    private static ThreadPoolExecutor createExecutor() {
        java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
        return new ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), task -> {
            Thread thread = new Thread(task, "recommendation-llm-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }

    @FunctionalInterface
    public interface OutputParser<T> {
        T parse(String raw) throws Exception;
    }

    public static final class OutputValidationException extends RuntimeException {
        private final String reason;

        public OutputValidationException(String reason) {
            super(reason);
            this.reason = safeLabel(reason, "invalid_model_output");
        }

        public String reason() {
            return reason;
        }
    }

    public static final class TracedLlmException extends RuntimeException {
        private final String reason;

        public TracedLlmException(String reason) {
            super(reason);
            this.reason = safeLabel(reason, "llm_call_failed");
        }

        public String reason() {
            return reason;
        }
    }
}
