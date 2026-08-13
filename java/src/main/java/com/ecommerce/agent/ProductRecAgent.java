package com.ecommerce.agent;

import com.ecommerce.connector.CommerceConnector;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Product recommendation Agent: connector recall + cross-border filtering + LLM ID-only rerank.
 */
@Component
public class ProductRecAgent extends BaseAgent {

    private final ChatClient chatClient;
    private final List<CommerceConnector> connectors;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ProductRecAgent(ChatClient.Builder chatClientBuilder, List<CommerceConnector> connectors) {
        super("product_rec", 8.0, 2);
        this.chatClient = chatClientBuilder.build();
        this.connectors = connectors;
    }

    @Override
    @SuppressWarnings("unchecked")
    protected AgentResult execute(Map<String, Object> params) throws Exception {
        RecommendationRequest request = requestFrom(params);
        UserProfile profile = (UserProfile) params.get("userProfile");
        int numItems = (int) params.getOrDefault("numItems", 10);

        List<Product> candidates = params.get("candidateProducts") instanceof List<?> raw
                ? (List<Product>) raw
                : recall(request, profile, Math.max(numItems * 2, numItems));
        List<String> blockedIds = candidates.stream()
                .filter(product -> !matchesCrossBorder(product, request))
                .map(Product::getProductId)
                .collect(Collectors.toList());
        candidates = candidates.stream()
                .filter(product -> matchesCrossBorder(product, request))
                .limit(Math.max(numItems * 2, numItems))
                .collect(Collectors.toList());

        List<String> rankedIds = rerank(request, profile, candidates, numItems);
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
        data.put("recall_strategy", "commerce_connector+cross_border_filter+llm_id_rerank");
        data.put("candidate_count", candidates.size());
        data.put("blocked_product_ids", blockedIds);
        data.put("cross_border_context", crossBorderContext(request));
        data.put("connector_platform", selectConnector(request).platform());

        return AgentResult.builder()
                .agentName(name)
                .success(true)
                .data(data)
                .confidence(0.82)
                .build();
    }

    private List<Product> recall(RecommendationRequest request, UserProfile profile, int limit) {
        return selectConnector(request).searchProducts(request, profile, limit);
    }

    private List<String> rerank(RecommendationRequest request, UserProfile profile, List<Product> candidates, int numItems) {
        if (candidates.isEmpty()) {
            return List.of();
        }
        if (profile == null) {
            return candidates.stream().map(Product::getProductId).limit(numItems).collect(Collectors.toList());
        }
        try {
            String prompt = String.format(
                    "Rerank products for a cross-border ecommerce recommendation.\n" +
                            "User categories: %s, price range: %.0f-%.0f.\n" +
                            "Cross-border context: platform=%s, region=%s, country=%s, locale=%s, currency=%s.\n" +
                            "Only choose from the provided product IDs. Do not invent fields or products. Return JSON array of product IDs, max %d.\n%s",
                    profile.getPreferredCategories(),
                    priceMin(profile), priceMax(profile),
                    request.platformOrDefault(), request.regionOrDefault(), request.countryOrDefault(), request.localeOrDefault(), request.currencyOrDefault(),
                    numItems,
                    candidates.stream()
                            .map(p -> String.format("ID:%s name:%s category:%s price:%s %.2f warehouse:%s deliveryDays:%d platform:%s supported:%s crossBorder:%s stock:%d",
                                    p.getProductId(), p.getName(), p.getCategory(), p.getCurrency(), p.getPrice(),
                                    p.getWarehouseRegion(), p.getDeliveryDays(), p.getPlatform(), p.getSupportedRegions(), p.isCrossBorderEligible(), p.getStock()))
                            .collect(Collectors.joining("\n"))
            );
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
            log.warn("LLM rerank failed, using cross-border default order: {}", e.getMessage());
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
                && request.platformOrDefault().equalsIgnoreCase(product.getPlatform())
                && request.currencyOrDefault().equalsIgnoreCase(product.getCurrency())
                && supported.stream().anyMatch(value -> value.equalsIgnoreCase(request.countryOrDefault()) || value.equalsIgnoreCase(request.regionOrDefault()));
    }

    private RecommendationRequest requestFrom(Map<String, Object> params) {
        Object request = params.get("request");
        if (request instanceof RecommendationRequest recommendationRequest) {
            return recommendationRequest;
        }
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
                "platform", request.platformOrDefault(),
                "region", request.regionOrDefault(),
                "country", request.countryOrDefault(),
                "locale", request.localeOrDefault(),
                "currency", request.currencyOrDefault()
        );
    }
}
