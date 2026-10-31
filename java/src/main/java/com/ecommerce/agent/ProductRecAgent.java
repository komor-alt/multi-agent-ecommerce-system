package com.ecommerce.agent;

import com.ecommerce.connector.CommerceConnector;
import com.ecommerce.data.RecommendationDataService;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.RecommendationModeResolver;
import com.ecommerce.service.TracedRecommendationLlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Product recall/rerank Agent backed by PostgreSQL + pgvector at runtime. */
@Component
public class ProductRecAgent extends BaseAgent {

    private static final long LLM_OUTER_TIMEOUT_MARGIN_MS = 2_000L;

    private final TracedRecommendationLlmClient llmClient;
    private final List<CommerceConnector> connectors;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecommendationModeResolver modeResolver;
    private final RecommendationDataService recommendationDataService;

    public ProductRecAgent(ChatClient.Builder chatClientBuilder, List<CommerceConnector> connectors) {
        this(chatClientBuilder, connectors, new RecommendationModeResolver("RULES", ""), null,
                new TracedRecommendationLlmClient(chatClientBuilder, "test", "unknown", 15000));
    }

    public ProductRecAgent(ChatClient.Builder chatClientBuilder, List<CommerceConnector> connectors,
                           RecommendationModeResolver modeResolver) {
        this(chatClientBuilder, connectors, modeResolver, null,
                new TracedRecommendationLlmClient(chatClientBuilder, "test", "unknown", 15000));
    }

    public ProductRecAgent(ChatClient.Builder chatClientBuilder, List<CommerceConnector> connectors,
                           RecommendationModeResolver modeResolver,
                           RecommendationDataService recommendationDataService) {
        this(chatClientBuilder, connectors, modeResolver, recommendationDataService,
                new TracedRecommendationLlmClient(chatClientBuilder, "test", "unknown", 15000));
    }

    @Autowired
    public ProductRecAgent(ChatClient.Builder chatClientBuilder, List<CommerceConnector> connectors,
                           RecommendationModeResolver modeResolver,
                           RecommendationDataService recommendationDataService,
                           TracedRecommendationLlmClient llmClient) {
        super("product_rec", 8.0, 1);
        this.llmClient = llmClient;
        this.connectors = connectors == null ? List.of() : connectors;
        this.modeResolver = modeResolver;
        this.recommendationDataService = recommendationDataService;
    }

    @Override
    protected long executionTimeoutMs() {
        long perCallTimeout = Math.max(1L, llmClient.timeoutMs());
        long twoCallTimeout = perCallTimeout > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : perCallTimeout * 2;
        return timeoutWithMargin(twoCallTimeout, LLM_OUTER_TIMEOUT_MARGIN_MS);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected AgentResult execute(Map<String, Object> params) throws Exception {
        RecommendationRequest request = requestFrom(params);
        UserProfile profile = (UserProfile) params.get("userProfile");
        int numItems = (int) params.getOrDefault("numItems", 10);
        Set<String> excludedProductIds = stringIds(params.getOrDefault(
                "excludedProductIds", params.get("vetoedProductIds")));

        RecallResult recalled;
        List<Product> candidates;
        if (params.get("candidateProducts") instanceof List<?> raw) {
            candidates = (List<Product>) raw;
            recalled = new RecallResult(candidates, "pipeline_candidates", false, null);
        } else {
            recalled = recall(request, profile, Math.max(numItems * 2, numItems), excludedProductIds);
            candidates = recalled.products();
        }
        candidates = candidates.stream()
                .filter(product -> !excludedProductIds.contains(product.getProductId()))
                .toList();

        List<String> blockedIds = candidates.stream()
                .filter(product -> !matchesCrossBorder(product, request) || product.getStock() <= 0)
                .map(Product::getProductId)
                .collect(Collectors.toList());
        candidates = candidates.stream()
                .filter(product -> matchesCrossBorder(product, request))
                .filter(product -> product.getStock() > 0)
                .limit(Math.max(numItems * 2, numItems))
                .collect(Collectors.toList());

        RerankOutcome rerankOutcome = rerank(request, profile, candidates, numItems, params);
        List<String> rankedIds = rerankOutcome.productIds();
        Map<String, Product> idMap = candidates.stream()
                .collect(Collectors.toMap(Product::getProductId, p -> p, (a, b) -> a));
        List<Product> finalProducts = rankedIds.stream()
                .filter(idMap::containsKey)
                .map(idMap::get)
                .limit(numItems)
                .collect(Collectors.toCollection(ArrayList::new));

        if (finalProducts.size() < numItems) {
            Set<String> selected = finalProducts.stream().map(Product::getProductId).collect(Collectors.toSet());
            candidates.stream()
                    .filter(p -> !selected.contains(p.getProductId()))
                    .limit(numItems - finalProducts.size())
                    .forEach(finalProducts::add);
        }

        Map<String, Object> data = new HashMap<>();
        data.put("products", finalProducts);
        data.put("recall_strategy", recalled.source() + "+cross_border_filter+llm_or_rules_rerank");
        data.put("data_source", recalled.source());
        data.put("vector_used", recalled.vectorUsed());
        if (recalled.fallbackReason() != null) data.put("fallback_reason", recalled.fallbackReason());
        data.put("candidate_count", candidates.size());
        data.put("rerank_diagnostics", rerankOutcome.diagnostics());
        data.put("blocked_product_ids", blockedIds);
        data.put("cross_border_context", crossBorderContext(request));
        data.put("connector_platform", recalled.source().startsWith("postgresql") ? "postgresql" : selectConnector(request).platform());

        return AgentResult.builder()
                .agentName(name)
                .success(true)
                .data(data)
                .confidence(recalled.vectorUsed() ? 0.9 : 0.82)
                .build();
    }

    private RecallResult recall(RecommendationRequest request, UserProfile profile, int limit,
                                Set<String> excludedProductIds) {
        if (recommendationDataService != null) {
            Set<String> categories = profile == null || profile.getPreferredCategories() == null
                    ? Set.of() : new HashSet<>(profile.getPreferredCategories());
            RecommendationDataService.ProductSearchResult result = recommendationDataService.searchProductsWithSource(
                    request, categories, limit, excludedProductIds);
            return new RecallResult(result.products(), result.source(), result.vectorUsed(), result.fallbackReason());
        }
        int connectorLimit = (int) Math.min(Integer.MAX_VALUE, (long) limit + excludedProductIds.size());
        return new RecallResult(selectConnector(request).searchProducts(request, profile, connectorLimit),
                "legacy_connector", false, "postgresql_data_service_unavailable");
    }

    private RerankOutcome rerank(RecommendationRequest request, UserProfile profile,
                                 List<Product> candidates, int numItems, Map<String, Object> params) {
        if (candidates.isEmpty()) {
            return rulesOutcome(List.of(), 0);
        }
        List<String> safeOrder = candidates.stream().map(Product::getProductId).limit(numItems).toList();
        if (profile == null) return rulesOutcome(safeOrder, candidates.size());
        if (!modeResolver.llmEnabled()) return rulesOutcome(safeOrder, candidates.size());

        LlmCallBudget budget = params.get("llmBudget") instanceof LlmCallBudget value ? value : null;
        int selectionLimit = Math.max(1, Math.min(numItems, candidates.size()));
        String prompt = rerankPrompt(request, profile, candidates, selectionLimit, null);
        Map<String, String> candidateIds = candidateIdWhitelist(candidates);
        String fingerprint = String.valueOf(params.getOrDefault("llmFingerprint", request.getUserId()));
        AttemptDiagnostics firstAttempt = new AttemptDiagnostics();
        try {
            List<String> selected = completeRerank(prompt, budget, fingerprint, candidateIds,
                    selectionLimit, firstAttempt);
            return modelOutcome(selected, "llm_success", null, null, null,
                    0, candidates.size(), firstAttempt.invalidIdCount());
        } catch (TracedRecommendationLlmClient.TracedLlmException firstFailure) {
            String firstReason = firstFailure.reason();
            int invalidIdCount = firstAttempt.invalidIdCount();
            if (recoverableRerankFailure(firstReason)) {
                String retrySkippedReason = retryUnavailableReason(budget);
                if (retrySkippedReason == null) {
                    AttemptDiagnostics retryAttempt = new AttemptDiagnostics();
                    try {
                        String retryPrompt = rerankPrompt(request, profile, candidates, selectionLimit, firstReason);
                        List<String> selected = completeRerank(retryPrompt, budget,
                                fingerprint + ":retry:1", candidateIds, selectionLimit, retryAttempt);
                        return modelOutcome(selected, "llm_retry_success", firstReason, null, null,
                                1, candidates.size(), invalidIdCount + retryAttempt.invalidIdCount());
                    } catch (TracedRecommendationLlmClient.TracedLlmException retryFailure) {
                        invalidIdCount += retryAttempt.invalidIdCount();
                        boolean retryBlocked = isBudgetReason(retryFailure.reason());
                        String finalReason = retryBlocked ? firstReason : retryFailure.reason();
                        String retrySkippedReasonAfterReserve = retryBlocked ? retryFailure.reason() : null;
                        int retryCount = retryBlocked ? 0 : 1;
                        log.warn("LLM rerank retry fell back; firstReason={}, finalReason={}, retrySkippedReason={}, candidateCount={}, invalidIdCount={}",
                                firstReason, finalReason, retrySkippedReasonAfterReserve,
                                candidates.size(), invalidIdCount);
                        return fallbackOutcome(safeOrder, firstReason, finalReason,
                                retrySkippedReasonAfterReserve, retryCount, candidates.size(), invalidIdCount);
                    }
                }
                llmClient.recordLocalFallback("product_rerank", budget, retrySkippedReason);
                log.warn("LLM rerank retry skipped; firstReason={}, skipReason={}, candidateCount={}, invalidIdCount={}",
                        firstReason, retrySkippedReason, candidates.size(), invalidIdCount);
                return fallbackOutcome(safeOrder, firstReason, firstReason, retrySkippedReason,
                        0, candidates.size(), invalidIdCount);
            }
            log.warn("LLM rerank fell back; reason={}, candidateCount={}, invalidIdCount={}",
                    firstReason, candidates.size(), invalidIdCount);
            return fallbackOutcome(safeOrder, firstReason, firstReason, null,
                    0, candidates.size(), invalidIdCount);
        } catch (Exception failure) {
            log.warn("LLM rerank failed; using safe candidate order; candidateCount={}", candidates.size());
            return fallbackOutcome(safeOrder, "local_processing_failed", "local_processing_failed",
                    null, 0, candidates.size(), firstAttempt.invalidIdCount());
        }
    }

    private List<String> completeRerank(String prompt, LlmCallBudget budget, String fingerprint,
                                        Map<String, String> candidateIds, int selectionLimit,
                                        AttemptDiagnostics diagnostics) {
        return llmClient.complete("product_rerank",
                "Return JSON only. Select at least one supplied product ID; never invent or omit the selection field.",
                prompt, budget, fingerprint,
                raw -> parseRerankSelection(raw, candidateIds, selectionLimit, diagnostics));
    }

    private List<String> parseRerankSelection(String raw, Map<String, String> candidateIds,
                                               int selectionLimit, AttemptDiagnostics diagnostics) throws Exception {
        JsonNode root = objectMapper.readTree(stripFence(raw));
        JsonNode selection = root;
        if (root != null && root.isObject()) {
            selection = firstArray(root, "productIds", "selectedProductIds", "rankedProductIds", "ids");
        }
        if (selection == null || !selection.isArray()) {
            throw new TracedRecommendationLlmClient.OutputValidationException("invalid_model_output");
        }

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        int invalidCount = 0;
        for (JsonNode value : selection) {
            if (!value.isTextual()) {
                invalidCount++;
                continue;
            }
            String normalized = normalizeProductId(value.asText());
            String canonical = candidateIds.get(normalized);
            if (canonical == null) {
                invalidCount++;
            } else {
                selected.add(canonical);
            }
        }
        diagnostics.addInvalidIds(invalidCount);
        if (invalidCount > 0) {
            throw new TracedRecommendationLlmClient.OutputValidationException("product_outside_candidate_set");
        }
        if (selected.isEmpty()) {
            throw new TracedRecommendationLlmClient.OutputValidationException("empty_rerank_selection");
        }
        return selected.stream().limit(selectionLimit).toList();
    }

    private JsonNode firstArray(JsonNode object, String... fields) {
        for (String field : fields) {
            JsonNode value = object.get(field);
            if (value != null) return value;
        }
        return null;
    }

    private Map<String, String> candidateIdWhitelist(List<Product> candidates) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (Product candidate : candidates) {
            String canonical = candidate.getProductId();
            String normalized = normalizeProductId(canonical);
            if (!normalized.isEmpty()) ids.putIfAbsent(normalized, canonical);
        }
        return Map.copyOf(ids);
    }

    private String normalizeProductId(String value) {
        return value == null ? "" : value.trim();
    }

    private String rerankPrompt(RecommendationRequest request, UserProfile profile,
                                List<Product> candidates, int selectionLimit, String priorFailure) {
        String correction = priorFailure == null ? "" : String.format(
                "The previous response was rejected for %s. Correct only the output format; do not add products.\n",
                priorFailure);
        return String.format(
                "Rerank products for a cross-border ecommerce recommendation.\n" +
                        "User categories: %s, price range: %.0f-%.0f.\n" +
                        "Cross-border context: platform=%s, region=%s, country=%s, locale=%s, currency=%s.\n" +
                        "%sChoose between 1 and %d IDs from the provided candidates. Even if relevance is weak, choose the best available candidate; never return an empty selection.\n" +
                        "Return exactly one JSON object: {\"productIds\":[\"candidate-id\"]}. Do not invent fields or IDs.\n%s",
                profile.getPreferredCategories(), priceMin(profile), priceMax(profile),
                request.platformOrDefault(), request.regionOrDefault(), request.countryOrDefault(),
                request.localeOrDefault(), request.currencyOrDefault(), correction, selectionLimit,
                candidates.stream()
                        .map(p -> String.format("ID:%s name:%s category:%s price:%s %.2f warehouse:%s deliveryDays:%d platform:%s supported:%s crossBorder:%s stock:%d",
                                p.getProductId(), p.getName(), p.getCategory(), p.getCurrency(), p.getPrice(),
                                p.getWarehouseRegion(), p.getDeliveryDays(), p.getPlatform(), p.getSupportedRegions(),
                                p.isCrossBorderEligible(), p.getStock()))
                        .collect(Collectors.joining("\n")));
    }

    private boolean recoverableRerankFailure(String reason) {
        return Set.of("empty_rerank_selection", "invalid_json", "invalid_model_output").contains(reason);
    }

    private boolean isBudgetReason(String reason) {
        return "llm_budget_exhausted".equals(reason)
                || "llm_budget_unavailable".equals(reason)
                || "duplicate_call_blocked".equals(reason);
    }

    private String retryUnavailableReason(LlmCallBudget budget) {
        if (budget == null) return "llm_budget_unavailable";
        return budget.getCallCount() >= budget.getMaxCalls() ? "llm_budget_exhausted" : null;
    }

    private RerankOutcome rulesOutcome(List<String> ids, int candidateCount) {
        return modelOutcome(ids, "rules", null, null, null, 0, candidateCount, 0);
    }

    private RerankOutcome fallbackOutcome(List<String> ids, String firstReason, String finalReason,
                                          String retrySkippedReason, int retryCount,
                                          int candidateCount, int invalidIdCount) {
        return modelOutcome(ids, "safe_fallback", firstReason, finalReason, retrySkippedReason,
                retryCount, candidateCount, invalidIdCount);
    }

    private RerankOutcome modelOutcome(List<String> ids, String status, String firstReason,
                                       String finalReason, String retrySkippedReason, int retryCount,
                                       int candidateCount, int invalidIdCount) {
        Map<String, Object> diagnostics = new LinkedHashMap<>();
        diagnostics.put("status", status);
        diagnostics.put("fallbackUsed", "safe_fallback".equals(status));
        diagnostics.put("firstFailureReason", firstReason);
        diagnostics.put("finalFailureReason", finalReason);
        diagnostics.put("retrySkippedReason", retrySkippedReason);
        diagnostics.put("retryCount", retryCount);
        diagnostics.put("candidateCount", candidateCount);
        diagnostics.put("invalidIdCount", invalidIdCount);
        diagnostics.put("selectedCount", ids.size());
        return new RerankOutcome(List.copyOf(ids),
                java.util.Collections.unmodifiableMap(new LinkedHashMap<>(diagnostics)));
    }

    private String stripFence(String raw) {
        String cleaned = raw == null ? "" : raw.trim();
        if (cleaned.startsWith("```")) {
            int firstLine = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstLine < 0 || lastFence <= firstLine) {
                throw new TracedRecommendationLlmClient.OutputValidationException("invalid_json");
            }
            return cleaned.substring(firstLine + 1, lastFence).trim();
        }
        return cleaned;
    }

    private CommerceConnector selectConnector(RecommendationRequest request) {
        return connectors.stream()
                .filter(connector -> connector.platform().equalsIgnoreCase(request.platformOrDefault()))
                .findFirst()
                .orElseGet(() -> connectors.stream().findFirst()
                        .orElseThrow(() -> new IllegalStateException("no commerce connector configured")));
    }

    private boolean matchesCrossBorder(Product product, RecommendationRequest request) {
        List<String> supported = product.getSupportedRegions() == null ? List.of() : product.getSupportedRegions();
        return product.isCrossBorderEligible()
                && product.getPlatform() != null
                && request.platformOrDefault().equalsIgnoreCase(product.getPlatform())
                && request.currencyOrDefault().equalsIgnoreCase(product.getCurrency())
                && supported.stream().anyMatch(value -> value.equalsIgnoreCase(request.countryOrDefault())
                || value.equalsIgnoreCase(request.regionOrDefault()));
    }

    private RecommendationRequest requestFrom(Map<String, Object> params) {
        Object request = params.get("request");
        if (request instanceof RecommendationRequest recommendationRequest) return recommendationRequest;
        return RecommendationRequest.builder().userId(String.valueOf(params.getOrDefault("userId", "anonymous"))).build();
    }

    private double priceMin(UserProfile profile) {
        return profile.getPriceRange() != null && profile.getPriceRange().length > 0 ? profile.getPriceRange()[0] : 0;
    }

    private double priceMax(UserProfile profile) {
        return profile.getPriceRange() != null && profile.getPriceRange().length > 1 ? profile.getPriceRange()[1] : 2000;
    }

    private Map<String, Object> crossBorderContext(RecommendationRequest request) {
        return Map.of(
                "platform", request.platformOrDefault(), "region", request.regionOrDefault(),
                "country", request.countryOrDefault(), "locale", request.localeOrDefault(),
                "currency", request.currencyOrDefault());
    }

    private Set<String> stringIds(Object value) {
        if (!(value instanceof Iterable<?> values)) return Set.of();
        Set<String> ids = new HashSet<>();
        for (Object item : values) {
            if (item != null && !String.valueOf(item).isBlank()) ids.add(String.valueOf(item));
        }
        return Set.copyOf(ids);
    }

    private record RecallResult(List<Product> products, String source, boolean vectorUsed, String fallbackReason) {
        private RecallResult {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }

    private record RerankOutcome(List<String> productIds, Map<String, Object> diagnostics) {}

    private static final class AttemptDiagnostics {
        private int invalidIdCount;

        void addInvalidIds(int count) {
            invalidIdCount += Math.max(0, count);
        }

        int invalidIdCount() {
            return invalidIdCount;
        }
    }
}
