package com.ecommerce.data;

import com.ecommerce.model.Product;
import com.ecommerce.service.LlmCallBudget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Configuration and safety boundary for product/query embeddings.
 *
 * <p>The production provider is deliberately created lazily. The default
 * configuration is offline, so application startup, seed checks, tests, and
 * CI never create a provider client or make a network request. A live call is
 * possible only when the provider, base URL, model, dimensions, API key,
 * live flag, and positive call budget are all present.</p>
 *
 * <p>Seed/import and query calls use independent short-lived budgets. No
 * budget instance is retained as singleton state, and query fingerprints are
 * scoped to one retrieval/agent run.</p>
 */
@Service
public class ProductEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(ProductEmbeddingService.class);
    private static final String PLACEHOLDER_KEY = "your_api_key_here";
    private static final String OPENAI_COMPATIBLE = "openai-compatible";

    private final String provider;
    private final String baseUrl;
    private final String apiKey;
    private final String modelName;
    private final int dimensions;
    private final boolean liveEnabled;
    private final int maxCalls;
    private final EmbeddingModel suppliedModel;
    private final AtomicReference<EmbeddingModel> lazyModel = new AtomicReference<>();
    private final ThreadLocal<LlmCallBudget> seedBudget = new ThreadLocal<>();
    private final ThreadLocal<LlmCallBudget> queryBudget = new ThreadLocal<>();

    @Autowired
    public ProductEmbeddingService(
            @Value("${agent.embedding.provider:openai-compatible}") String provider,
            @Value("${agent.embedding.base-url:}") String baseUrl,
            @Value("${agent.embedding.api-key:}") String apiKey,
            @Value("${agent.embedding.model:}") String modelName,
            @Value("${agent.embedding.dimensions:0}") int dimensions,
            @Value("${agent.embedding.live-enabled:false}") boolean liveEnabled,
            @Value("${agent.embedding.max-calls:0}") int maxCalls) {
        this(provider, baseUrl, apiKey, modelName, dimensions, liveEnabled, maxCalls, null);
    }

    /** Package-private constructor for offline unit tests with an injected model double. */
    ProductEmbeddingService(
            String provider,
            String baseUrl,
            String apiKey,
            String modelName,
            int dimensions,
            boolean liveEnabled,
            int maxCalls,
            EmbeddingModel suppliedModel) {
        this.provider = normalize(provider, OPENAI_COMPATIBLE);
        this.baseUrl = trim(baseUrl);
        this.apiKey = trim(apiKey);
        this.modelName = trim(modelName);
        this.dimensions = dimensions;
        this.liveEnabled = liveEnabled;
        this.maxCalls = Math.max(0, maxCalls);
        this.suppliedModel = suppliedModel;
    }

    /** Builds the exact product text used both at seed/import time and in tests. */
    public static String productText(Product product) {
        if (product == null) return "";
        String tags = product.getTags() == null ? "" : product.getTags().stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
        return String.join("\n",
                "name: " + value(product.getName()),
                "category: " + value(product.getCategory()),
                "description: " + value(product.getDescription()),
                "tags: " + tags);
    }

    public static String productContentHash(Product product) {
        return contentHash(productText(product));
    }

    /** Stable SHA-256 for import text; the raw text is never logged or stored. */
    public static String contentHash(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256")
                    .digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(String.format(Locale.ROOT, "%02x", value));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }

    public EmbeddingResult embedProduct(Product product) {
        String productId = product == null ? "unknown" : value(product.getProductId());
        return embed(productText(product), "product:" + productId, seedBudget);
    }

    public EmbeddingResult embedProductText(String text, String productId) {
        return embed(text, "product:" + value(productId), seedBudget);
    }

    public EmbeddingResult embedQuery(String query) {
        return embed(query, "query:" + value(query), queryBudget);
    }

    public boolean isLiveEnabled() {
        return liveEnabled;
    }

    public int dimensions() {
        return dimensions;
    }

    public String configuredProvider() {
        return provider;
    }

    public String configuredModel() {
        return modelName;
    }

    public int maxCalls() {
        return maxCalls;
    }

    /**
     * Opens the per-seed/import budget. The scope is deliberately short-lived:
     * it is never retained by this singleton service and cannot consume a
     * later query budget.
     */
    public SeedBatchScope openSeedBatch() {
        LlmCallBudget previous = seedBudget.get();
        seedBudget.set(new LlmCallBudget(maxCalls));
        return new SeedBatchScope(previous);
    }

    /**
     * Opens the per-retrieval/agent-run embedding budget. A new scope gets a
     * fresh duplicate-fingerprint set, so a query from an earlier request
     * cannot permanently block a later request.
     */
    public QueryScope openQueryScope() {
        LlmCallBudget previous = queryBudget.get();
        queryBudget.set(new LlmCallBudget(maxCalls));
        return new QueryScope(previous);
    }

    public Map<String, Object> budgetSnapshot() {
        LlmCallBudget active = queryBudget.get();
        if (active == null) active = seedBudget.get();
        if (active != null) return active.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("llmCallCount", 0);
        result.put("maxLlmCalls", maxCalls);
        result.put("budgetBlockedCalls", 0);
        result.put("duplicateCallsBlocked", 0);
        result.put("scope", "none");
        return result;
    }

    /**
     * Converts a provider vector to pgvector's text input format. No hash or
     * synthetic vector fallback is provided here: unavailable embeddings must
     * remain visible as lexical fallback.
     */
    public static String toPgVectorLiteral(float[] vector) {
        if (vector == null || vector.length == 0) {
            throw new IllegalArgumentException("embedding vector must not be empty");
        }
        StringBuilder value = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            float component = vector[i];
            if (!Float.isFinite(component)) {
                throw new IllegalArgumentException("embedding vector contains a non-finite value");
            }
            if (i > 0) value.append(',');
            value.append(String.format(Locale.ROOT, "%.8f", component));
        }
        return value.append(']').toString();
    }

    private EmbeddingResult embed(String text, String fingerprint,
                                  ThreadLocal<LlmCallBudget> scopedBudget) {
        if (text == null || text.isBlank()) {
            return unavailable("offline", "embedding_text_empty");
        }
        if (!liveEnabled) {
            return unavailable("offline", "embedding_live_disabled");
        }
        if (!isSupportedProvider()) {
            return unavailable(provider, "embedding_provider_unsupported");
        }
        if (baseUrl == null || baseUrl.isBlank()) {
            return unavailable(provider, "embedding_base_url_missing");
        }
        if (modelName == null || modelName.isBlank()) {
            return unavailable(provider, "embedding_model_missing");
        }
        if (dimensions <= 0) {
            return unavailable(provider, "embedding_dimensions_missing");
        }
        if (!hasUsableApiKey()) {
            return unavailable(provider, "embedding_api_key_missing");
        }

        LlmCallBudget budget = scopedBudget.get();
        if (budget == null) {
            // Direct callers/importers that do not open a scope still get a
            // one-operation budget, never a singleton lifetime budget.
            budget = new LlmCallBudget(maxCalls);
        }
        if (!budget.tryAcquire("embedding", fingerprint)) {
            return unavailable(provider, "embedding_call_budget_exceeded");
        }

        try {
            EmbeddingModel model = model();
            if (model == null) {
                return unavailable(provider, "embedding_model_unavailable");
            }
            OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                    .model(modelName)
                    .dimensions(dimensions)
                    .build();
            EmbeddingResponse response = model.call(new EmbeddingRequest(List.of(text), options));
            Embedding result = response == null ? null : response.getResult();
            float[] vector = result == null ? null : result.getOutput();
            if (vector == null || vector.length == 0) {
                return unavailable(provider, "embedding_response_empty");
            }
            if (vector.length != dimensions) {
                return unavailable(provider, "embedding_dimension_mismatch");
            }
            for (float component : vector) {
                if (!Float.isFinite(component)) {
                    return unavailable(provider, "embedding_non_finite_vector");
                }
            }
            return new EmbeddingResult(vector, provider, true, null, vector.length);
        } catch (Exception error) {
            // Never include exception text: compatible clients may echo URL,
            // headers, or provider details that should not reach trace output.
            log.warn("embedding call unavailable source={} vectorUsed=false fallbackReason=embedding_call_failed",
                    provider);
            return unavailable(provider, "embedding_call_failed");
        }
    }

    private boolean isSupportedProvider() {
        return OPENAI_COMPATIBLE.equals(provider) || "qwen".equals(provider);
    }

    private boolean hasUsableApiKey() {
        return apiKey != null && !apiKey.isBlank()
                && !PLACEHOLDER_KEY.equalsIgnoreCase(apiKey)
                && !apiKey.startsWith("${");
    }

    private EmbeddingModel model() {
        EmbeddingModel existing = suppliedModel != null ? suppliedModel : lazyModel.get();
        if (existing != null) return existing;
        synchronized (lazyModel) {
            existing = lazyModel.get();
            if (existing != null) return existing;
            try {
                OpenAiApi api = OpenAiApi.builder()
                        .baseUrl(baseUrl)
                        .apiKey(apiKey)
                        .build();
                EmbeddingModel created = new OpenAiEmbeddingModel(
                        api,
                        MetadataMode.EMBED,
                        OpenAiEmbeddingOptions.builder()
                                .model(modelName)
                                .dimensions(dimensions)
                                .build());
                lazyModel.set(created);
                return created;
            } catch (Exception error) {
                log.warn("embedding client unavailable source={} fallbackReason=embedding_client_init_failed",
                        provider);
                return null;
            }
        }
    }

    private EmbeddingResult unavailable(String source, String reason) {
        log.info("embedding source={} vectorUsed=false fallbackReason={}", source, reason);
        return new EmbeddingResult(null, source, false, reason, 0);
    }

    private static String normalize(String value, String fallback) {
        String normalized = trim(value);
        return normalized == null || normalized.isBlank() ? fallback : normalized.toLowerCase(Locale.ROOT);
    }

    private static String trim(String value) {
        return value == null ? null : value.trim();
    }

    private static String value(String value) {
        return value == null ? "" : value.trim();
    }

    public record EmbeddingResult(float[] vector, String source, boolean vectorUsed,
                                  String fallbackReason, int dimension) {
        public EmbeddingResult {
            vector = vector == null ? null : vector.clone();
        }

        public float[] vectorCopy() {
            return vector == null ? null : vector.clone();
        }
    }

    public final class SeedBatchScope implements AutoCloseable {
        private final LlmCallBudget previous;
        private boolean closed;

        private SeedBatchScope(LlmCallBudget previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) seedBudget.remove();
            else seedBudget.set(previous);
        }
    }

    public final class QueryScope implements AutoCloseable {
        private final LlmCallBudget previous;
        private boolean closed;

        private QueryScope(LlmCallBudget previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) queryBudget.remove();
            else queryBudget.set(previous);
        }
    }
}
