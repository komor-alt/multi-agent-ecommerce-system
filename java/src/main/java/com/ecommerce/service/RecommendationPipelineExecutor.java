package com.ecommerce.service;

import com.ecommerce.agent.InventoryAgent;
import com.ecommerce.agent.MarketingCopyAgent;
import com.ecommerce.agent.ProductRecAgent;
import com.ecommerce.agent.UserProfileAgent;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.EvidenceRecord;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.RecommendationResponse;
import com.ecommerce.model.ToolObservation;
import com.ecommerce.model.UserProfile;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class RecommendationPipelineExecutor {
    public static final String GET_USER_PROFILE = "get_user_profile";
    public static final String SEARCH_PRODUCTS = "search_cross_border_products";
    public static final String RERANK_PRODUCTS = "rerank_products";
    public static final String CHECK_INVENTORY = "check_fulfillment_inventory";
    public static final String FILTER_PRODUCTS = "filter_products";
    public static final String GENERATE_COPY = "generate_localized_copy";
    public static final String FINAL_ACTION = "final_answer";

    public static final String USER_PROFILE_RESULT = "user_profile";
    public static final String CROSS_BORDER_RECALL_RESULT = "cross_border_recall";
    public static final String RERANK_RESULT = "rerank";
    public static final String FULFILLMENT_INVENTORY_RESULT = "fulfillment_inventory";
    public static final String LOCALIZED_MARKETING_COPY_RESULT = "localized_marketing_copy";

    private final UserProfileAgent userProfileAgent;
    private final ProductRecAgent productRecAgent;
    private final InventoryAgent inventoryAgent;
    private final MarketingCopyAgent marketingCopyAgent;
    private final Executor agentExecutor;
    private final List<RecommendationPipelineHook> hooks;
    private final RecommendationPipelineToolRegistry toolRegistry = new RecommendationPipelineToolRegistry();

    public RecommendationPipelineExecutor(
            UserProfileAgent userProfileAgent,
            ProductRecAgent productRecAgent,
            InventoryAgent inventoryAgent,
            MarketingCopyAgent marketingCopyAgent,
            @Qualifier("agentExecutor") Executor agentExecutor) {
        this(userProfileAgent, productRecAgent, inventoryAgent, marketingCopyAgent, agentExecutor, List.of());
    }

    @Autowired
    public RecommendationPipelineExecutor(
            UserProfileAgent userProfileAgent,
            ProductRecAgent productRecAgent,
            InventoryAgent inventoryAgent,
            MarketingCopyAgent marketingCopyAgent,
            @Qualifier("agentExecutor") Executor agentExecutor,
            List<RecommendationPipelineHook> hooks) {
        this.userProfileAgent = userProfileAgent;
        this.productRecAgent = productRecAgent;
        this.inventoryAgent = inventoryAgent;
        this.marketingCopyAgent = marketingCopyAgent;
        this.agentExecutor = agentExecutor;
        this.hooks = hooks == null ? List.of() : List.copyOf(hooks);
        registerBuiltInTools();
    }

    public CompletableFuture<AgentResult> userProfileAsync(RecommendationRequest request) {
        return userProfileAgent.runAsync(Map.of("request", request, "userId", safeUserId(request)), agentExecutor);
    }

    public CompletableFuture<AgentResult> productRecallAsync(RecommendationRequest request) {
        return productRecAgent.runAsync(Map.of("request", request, "numItems", request.getNumItems() * 2), agentExecutor);
    }

    public CompletableFuture<AgentResult> rerankAsync(RecommendationRequest request, UserProfile profile, List<Product> candidates) {
        return productRecAgent.runAsync(Map.of(
                "request", request,
                "userProfile", profile == null ? new UserProfile() : profile,
                "candidateProducts", candidates == null ? List.of() : candidates,
                "numItems", request.getNumItems()
        ), agentExecutor);
    }

    public CompletableFuture<AgentResult> inventoryAsync(RecommendationRequest request, List<Product> products) {
        return inventoryAgent.runAsync(Map.of("request", request, "products", products == null ? List.of() : products), agentExecutor);
    }

    public CompletableFuture<AgentResult> marketingCopyAsync(RecommendationRequest request, UserProfile profile, List<Product> products) {
        return marketingCopyAgent.runAsync(Map.of(
                "request", request,
                "userProfile", profile == null ? new UserProfile() : profile,
                "products", products == null ? List.of() : products
        ), agentExecutor);
    }

    public ToolObservation executeTool(String action, RecommendationPipelineState context) {
        RecommendationPipelineTool tool = toolRegistry.find(action)
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + action));
        Map<String, Object> arguments = trustedArguments(action, context);
        long start = System.nanoTime();
        try {
            for (RecommendationPipelineHook hook : hooks) {
                hook.beforeTool(action, context, arguments);
            }
            ToolObservation observation = tool.execute(action, context);
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
            for (RecommendationPipelineHook hook : hooks) {
                hook.afterTool(action, context, observation, latencyMs);
            }
            return observation;
        } catch (Exception e) {
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
            for (RecommendationPipelineHook hook : hooks) {
                hook.onToolError(action, context, e, latencyMs);
            }
            throw e;
        }
    }

    public Set<String> registeredToolNames() {
        return toolRegistry.names();
    }

    public UserProfile extractProfile(AgentResult result) {
        return result != null && result.isSuccess() && result.getData() != null
                ? (UserProfile) result.getData().get("profile")
                : null;
    }

    @SuppressWarnings("unchecked")
    public List<Product> extractProducts(AgentResult result, List<Product> fallback) {
        if (result == null || !result.isSuccess() || result.getData() == null) {
            return fallback == null ? List.of() : fallback;
        }
        return (List<Product>) result.getData().getOrDefault("products", fallback == null ? List.of() : fallback);
    }

    @SuppressWarnings("unchecked")
    public Set<String> extractAvailableIds(AgentResult result) {
        if (result == null || !result.isSuccess() || result.getData() == null) {
            return Set.of();
        }
        List<String> available = (List<String>) result.getData().getOrDefault("available_products", List.of());
        return new HashSet<>(available);
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> extractCopies(AgentResult result) {
        if (result == null || !result.isSuccess() || result.getData() == null) {
            return List.of();
        }
        return (List<Map<String, String>>) result.getData().getOrDefault("copies", List.of());
    }

    public List<Product> filterAvailableProducts(List<Product> rankedProducts, Set<String> availableIds, int numItems) {
        List<Product> ranked = rankedProducts == null ? List.of() : rankedProducts;
        Set<String> available = availableIds == null ? Set.of() : availableIds;
        return ranked.stream()
                .filter(product -> available.contains(product.getProductId()))
                .limit(numItems)
                .collect(Collectors.toList());
    }

    public void ensureSuccess(AgentResult result) {
        if (result == null || !result.isSuccess()) {
            String error = result == null ? "agent returned null" : result.getError();
            String agentName = result == null ? "agent" : result.getAgentName();
            throw new IllegalStateException(error == null ? agentName + " failed" : error);
        }
    }

    public Map<String, Object> trustedArguments(String action, RecommendationPipelineState context) {
        Map<String, Object> base = new LinkedHashMap<>(crossBorderState(context.getRequest()));
        switch (action) {
            case GET_USER_PROFILE -> base.put("userId", safeUserId(context.getRequest()));
            case SEARCH_PRODUCTS, "search_products" -> base.put("numItems", context.getRequest().getNumItems() * 2);
            case RERANK_PRODUCTS -> {
                base.put("numItems", context.getRequest().getNumItems());
                base.put("hasProfile", context.getProfile() != null);
            }
            case CHECK_INVENTORY, "check_inventory" -> base.put("productCount", context.getRawProducts() == null ? 0 : context.getRawProducts().size());
            case FILTER_PRODUCTS -> {
                base.put("rankedProductCount", context.getRankedProducts() == null ? 0 : context.getRankedProducts().size());
                base.put("availableCount", context.getAvailableIds() == null ? 0 : context.getAvailableIds().size());
            }
            case GENERATE_COPY, "generate_copy" -> base.put("productCount", context.getFinalProducts() == null ? 0 : context.getFinalProducts().size());
            default -> {
                return Map.of();
            }
        }
        return base;
    }

    public Map<String, Object> crossBorderState(RecommendationRequest request) {
        return Map.of(
                "userId", safeUserId(request),
                "scene", request.getScene() == null ? "homepage" : request.getScene(),
                "numItems", request.getNumItems(),
                "platform", request.platformOrDefault(),
                "region", request.regionOrDefault(),
                "country", request.countryOrDefault(),
                "locale", request.localeOrDefault(),
                "currency", request.currencyOrDefault()
        );
    }

    public List<EvidenceRecord> evidenceRecords(ToolObservation observation) {
        return observation.getEvidenceIds().stream()
                .map(id -> EvidenceRecord.builder()
                        .evidenceId(id)
                        .sourceType(id.contains(":") ? id.substring(0, id.indexOf(':')) : "unknown")
                        .sourceId(id.contains(":") ? id.substring(id.indexOf(':') + 1) : id)
                        .title(observation.getToolName())
                        .summary(observation.getSummary())
                        .metadata(new HashMap<>(observation.getData()))
                        .build())
                .toList();
    }

    public RecommendationResponse buildResponse(RecommendationPipelineState context, String experimentGroup, long start) {
        RecommendationRequest request = context.getRequest();
        return RecommendationResponse.builder()
                .requestId(context.getRunId())
                .userId(safeUserId(request))
                .platform(request.platformOrDefault())
                .region(request.regionOrDefault())
                .country(request.countryOrDefault())
                .locale(request.localeOrDefault())
                .currency(request.currencyOrDefault())
                .products(context.getFinalProducts() == null ? List.of() : context.getFinalProducts())
                .marketingCopies(context.getCopies() == null ? List.of() : context.getCopies())
                .experimentGroup(experimentGroup)
                .agentResults(context.getAgentResults())
                .totalLatencyMs((System.nanoTime() - start) / 1_000_000.0)
                .build();
    }

    public String safeUserId(RecommendationRequest request) {
        return request.getUserId() == null || request.getUserId().isBlank() ? "anonymous" : request.getUserId();
    }

    private void registerBuiltInTools() {
        toolRegistry.register(GET_USER_PROFILE, (action, context) -> getUserProfile(context));
        toolRegistry.register(SEARCH_PRODUCTS, List.of("search_products"), (action, context) -> searchProducts(context, action));
        toolRegistry.register(RERANK_PRODUCTS, (action, context) -> rerankProducts(context));
        toolRegistry.register(CHECK_INVENTORY, List.of("check_inventory"), (action, context) -> checkInventory(context, action));
        toolRegistry.register(FILTER_PRODUCTS, (action, context) -> filterProducts(context));
        toolRegistry.register(GENERATE_COPY, List.of("generate_copy"), (action, context) -> generateCopy(context, action));
    }
    private ToolObservation getUserProfile(RecommendationPipelineState context) {
        AgentResult result = userProfileAsync(context.getRequest()).join();
        ensureSuccess(result);
        context.putAgentResult(USER_PROFILE_RESULT, result);
        context.setProfile(extractProfile(result));
        String evidenceId = "profile:" + safeUserId(context.getRequest());
        context.addEvidenceIds(List.of(evidenceId));
        return ToolObservation.builder()
                .toolName(GET_USER_PROFILE)
                .summary("Generated profile for " + safeUserId(context.getRequest()) + " in " + context.getRequest().countryOrDefault())
                .data(Map.of("evidenceId", evidenceId, "crossBorder", crossBorderState(context.getRequest())))
                .evidenceIds(List.of(evidenceId))
                .build();
    }

    private ToolObservation searchProducts(RecommendationPipelineState context, String action) {
        AgentResult result = productRecallAsync(context.getRequest()).join();
        ensureSuccess(result);
        context.putAgentResult(CROSS_BORDER_RECALL_RESULT, result);
        context.setRawProducts(extractProducts(result, List.of()));
        List<String> evidenceIds = context.getRawProducts().stream()
                .map(product -> "product:" + product.getProductId())
                .toList();
        context.addEvidenceIds(evidenceIds);
        return ToolObservation.builder()
                .toolName(action)
                .summary("Recalled " + context.getRawProducts().size() + " cross-border products for " + context.getRequest().countryOrDefault() + "/" + context.getRequest().currencyOrDefault())
                .data(Map.of("productIds", context.getRawProducts().stream().map(Product::getProductId).toList(), "crossBorder", crossBorderState(context.getRequest())))
                .evidenceIds(evidenceIds)
                .build();
    }

    private ToolObservation rerankProducts(RecommendationPipelineState context) {
        AgentResult result = rerankAsync(context.getRequest(), context.getProfile(), context.getRawProducts()).join();
        ensureSuccess(result);
        context.putAgentResult(RERANK_RESULT, result);
        context.setRankedProducts(extractProducts(result, context.getRawProducts()));
        List<String> rankedIds = context.getRankedProducts() == null
                ? List.of()
                : context.getRankedProducts().stream().map(Product::getProductId).toList();
        return ToolObservation.builder()
                .toolName(RERANK_PRODUCTS)
                .summary("Reranked cross-border products: " + rankedIds)
                .data(Map.of("rankedProductIds", rankedIds, "crossBorder", crossBorderState(context.getRequest())))
                .evidenceIds(rankedIds.stream().map(id -> "product:" + id).toList())
                .build();
    }

    private ToolObservation checkInventory(RecommendationPipelineState context, String action) {
        List<Product> products = context.getRawProducts() == null ? List.of() : context.getRawProducts();
        AgentResult result = inventoryAsync(context.getRequest(), products).join();
        ensureSuccess(result);
        context.putAgentResult(FULFILLMENT_INVENTORY_RESULT, result);
        context.setAvailableIds(extractAvailableIds(result));
        List<String> available = context.getAvailableIds().stream().toList();
        List<String> evidenceIds = available.stream().map(id -> "inventory:" + id).toList();
        context.addEvidenceIds(evidenceIds);
        return ToolObservation.builder()
                .toolName(action)
                .summary("Fulfillment available product count: " + available.size())
                .data(Map.of("availableProductIds", available, "crossBorder", crossBorderState(context.getRequest())))
                .evidenceIds(evidenceIds)
                .build();
    }

    private ToolObservation filterProducts(RecommendationPipelineState context) {
        context.setFinalProducts(filterAvailableProducts(context.getRankedProducts(), context.getAvailableIds(), context.getRequest().getNumItems()));
        List<String> productIds = context.getFinalProducts().stream().map(Product::getProductId).toList();
        return ToolObservation.builder()
                .toolName(FILTER_PRODUCTS)
                .summary("Selected final fulfillment-safe products: " + productIds)
                .data(Map.of("finalProductIds", productIds, "crossBorder", crossBorderState(context.getRequest())))
                .evidenceIds(productIds.stream().map(id -> "product:" + id).toList())
                .build();
    }

    private ToolObservation generateCopy(RecommendationPipelineState context, String action) {
        AgentResult result = marketingCopyAsync(context.getRequest(), context.getProfile(), context.getFinalProducts()).join();
        ensureSuccess(result);
        context.putAgentResult(LOCALIZED_MARKETING_COPY_RESULT, result);
        context.setCopies(extractCopies(result));
        List<String> evidenceIds = context.getCopies().stream()
                .map(copy -> "copy:" + copy.getOrDefault("product_id", "unknown"))
                .toList();
        context.addEvidenceIds(evidenceIds);
        return ToolObservation.builder()
                .toolName(action)
                .summary("Generated " + context.getCopies().size() + " localized copies in " + context.getRequest().localeOrDefault())
                .data(Map.of("copyProductIds", context.getCopies().stream().map(copy -> copy.getOrDefault("product_id", "")).toList(), "crossBorder", crossBorderState(context.getRequest())))
                .evidenceIds(evidenceIds)
                .build();
    }
}
