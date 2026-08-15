package com.ecommerce.agent;

import com.ecommerce.connector.CommerceConnector;
import com.ecommerce.data.RecommendationDataService;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.ecommerce.service.LlmCallBudget;
import com.ecommerce.service.RecommendationModeResolver;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Product recall/rerank Agent backed by PostgreSQL + pgvector at runtime. */
@Component
public class ProductRecAgent extends BaseAgent {

    private final ChatClient chatClient;
    private final List<CommerceConnector> connectors;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RecommendationModeResolver modeResolver;
    private final RecommendationDataService recommendationDataService;

    public ProductRecAgent(ChatClient.Builder chatClientBuilder, List<CommerceConnector> connectors) {
        this(chatClientBuilder, connectors, new RecommendationModeResolver("RULES", ""), null);
    }

    public ProductRecAgent(ChatClient.Builder chatClientBuilder, List<CommerceConnector> connectors,
                           RecommendationModeResolver modeResolver) {
        this(chatClientBuilder, connectors, modeResolver, null);
    }

    @Autowired
    public ProductRecAgent(ChatClient.Builder chatClientBuilder, List<CommerceConnector> connectors,
                           RecommendationModeResolver modeResolver,
                           RecommendationDataService recommendationDataService) {
        super("product_rec", 8.0, 1);
        this.chatClient = chatClientBuilder.build();
        this.connectors = connectors == null ? List.of() : connectors;
        this.modeResolver = modeResolver;
        this.recommendationDataService = recommendationDataService;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected AgentResult execute(Map<String, Object> params) throws Exception {
        RecommendationRequest request = requestFrom(params);
        UserProfile profile = (UserProfile) params.get("userProfile");
        int numItems = (int) params.getOrDefault("numItems", 10);

        RecallResult recalled;
        List<Product> candidates;
        if (params.get("candidateProducts") instanceof List<?> raw) {
            candidates = (List<Product>) raw;
            recalled = new RecallResult(candidates, "pipeline_candidates", false, null);
        } else {
            recalled = recall(request, profile, Math.max(numItems * 2, numItems));
            candidates = recalled.products();
        }

        List<String> blockedIds = candidates.stream()
                .filter(product -> !matchesCrossBorder(product, request) || product.getStock() <= 0)
                .map(Product::getProductId)
                .collect(Collectors.toList());
        candidates = candidates.stream()
                .filter(product -> matchesCrossBorder(product, request))
                .filter(product -> product.getStock() > 0)
                .limit(Math.max(numItems * 2, numItems))
                .collect(Collectors.toList());

        List<String> rankedIds = rerank(request, profile, candidates, numItems, params);
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

    private RecallResult recall(RecommendationRequest request, UserProfile profile, int limit) {
        if (recommendationDataService != null) {
            Set<String> categories = profile == null || profile.getPreferredCategories() == null
                    ? Set.of() : new HashSet<>(profile.getPreferredCategories());
            RecommendationDataService.ProductSearchResult result = recommendationDataService.searchProductsWithSource(
                    request, categories, limit);
            return new RecallResult(result.products(), result.source(), result.vectorUsed(), result.fallbackReason());
        }
        return new RecallResult(selectConnector(request).searchProducts(request, profile, limit),
                "legacy_connector", false, "postgresql_data_service_unavailable");
    }

    private List<String> rerank(RecommendationRequest request, UserProfile profile, List<Product> candidates, int numItems, Map<String, Object> params) {
        if (candidates.isEmpty()) return List.of();
        if (profile == null) return candidates.stream().map(Product::getProductId).limit(numItems).collect(Collectors.toList());
        try {
            if (!modeResolver.llmEnabled()) {
                return candidates.stream().map(Product::getProductId).limit(numItems).collect(Collectors.toList());
            }
            LlmCallBudget budget = params.get("llmBudget") instanceof LlmCallBudget value ? value : null;
            if (budget == null || !budget.tryAcquire(name, String.valueOf(params.getOrDefault("llmFingerprint", request.getUserId())))) {
                return candidates.stream().map(Product::getProductId).limit(numItems).collect(Collectors.toList());
            }
            String prompt = String.format(
                    "Rerank products for a cross-border ecommerce recommendation.\n" +
                            "User categories: %s, price range: %.0f-%.0f.\n" +
                            "Cross-border context: platform=%s, region=%s, country=%s, locale=%s, currency=%s.\n" +
                            "Only choose from the provided product IDs. Do not invent fields or products. Return JSON array of product IDs, max %d.\n%s",
                    profile.getPreferredCategories(), priceMin(profile), priceMax(profile),
                    request.platformOrDefault(), request.regionOrDefault(), request.countryOrDefault(),
                    request.localeOrDefault(), request.currencyOrDefault(), numItems,
                    candidates.stream()
                            .map(p -> String.format("ID:%s name:%s category:%s price:%s %.2f warehouse:%s deliveryDays:%d platform:%s supported:%s crossBorder:%s stock:%d",
                                    p.getProductId(), p.getName(), p.getCategory(), p.getCurrency(), p.getPrice(),
                                    p.getWarehouseRegion(), p.getDeliveryDays(), p.getPlatform(), p.getSupportedRegions(),
                                    p.isCrossBorderEligible(), p.getStock()))
                            .collect(Collectors.joining("\n")));
            String response = chatClient.prompt().user(prompt).call().content();
            String cleaned = response.trim();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.substring(cleaned.indexOf('\n') + 1);
                cleaned = cleaned.substring(0, cleaned.lastIndexOf("```"));
            }
            List<String> ids = objectMapper.readValue(cleaned, new TypeReference<>() {});
            Set<String> candidateIds = candidates.stream().map(Product::getProductId).collect(Collectors.toSet());
            return ids.stream().filter(candidateIds::contains).limit(numItems).collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("LLM rerank failed, using safe candidate order: {}", e.getMessage());
            return candidates.stream().map(Product::getProductId).limit(numItems).collect(Collectors.toList());
        }
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

    private record RecallResult(List<Product> products, String source, boolean vectorUsed, String fallbackReason) {
        private RecallResult {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }
}
