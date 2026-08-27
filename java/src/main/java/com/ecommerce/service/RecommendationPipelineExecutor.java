package com.ecommerce.service;

import com.ecommerce.agent.InventoryAgent;
import com.ecommerce.agent.MarketingCopyAgent;
import com.ecommerce.agent.ProductRecAgent;
import com.ecommerce.agent.UserProfileAgent;
import com.ecommerce.data.RecommendationDataService;
import com.ecommerce.data.entity.RecInventoryEntity;
import com.ecommerce.model.AgentId;
import com.ecommerce.model.AgentResult;
import com.ecommerce.model.BlackboardField;
import com.ecommerce.model.EvidenceRecord;
import com.ecommerce.model.Product;
import com.ecommerce.model.VetoRecord;
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
    public static final String LOAD_CAMPAIGN_CONSTRAINTS = "load_campaign_constraints";
    public static final String GET_CAMPAIGN_CONSTRAINTS = LOAD_CAMPAIGN_CONSTRAINTS;
    public static final String LEGACY_GET_CAMPAIGN_CONSTRAINTS = "get_campaign_constraints";
    public static final String GET_RECENT_ORDERS = "get_recent_orders";
    public static final String GET_ORDER_CONTEXT = GET_RECENT_ORDERS;
    public static final String LEGACY_GET_ORDER_CONTEXT = "get_order_context";
    public static final String CHECK_FULFILLMENT = "check_fulfillment";
    public static final String CHECK_MARKET_ELIGIBILITY = "check_market_eligibility";
    public static final String GENERATE_RETENTION_COPY = "generate_retention_copy";
    public static final String SEARCH_PRODUCTS = "search_products";
    public static final String LEGACY_SEARCH_PRODUCTS = "search_cross_border_products";
    public static final String RERANK_PRODUCTS = "rerank";
    public static final String RERANK = RERANK_PRODUCTS;
    public static final String LEGACY_RERANK_PRODUCTS = "rerank_products";
    public static final String CHECK_INVENTORY = "check_inventory";
    public static final String LEGACY_CHECK_INVENTORY = "check_fulfillment_inventory";
    public static final String FILTER_PRODUCTS = "filter_products";
    public static final String GENERATE_COPY = "generate_localized_copy";
    public static final String FINAL_ACTION = "final_answer";

    public static final String USER_PROFILE_RESULT = "user_profile";
    public static final String CAMPAIGN_CONSTRAINTS_RESULT = "campaign_constraints";
    public static final String ORDER_CONTEXT_RESULT = "order_context";
    public static final String MARKET_ELIGIBILITY_RESULT = "market_eligibility";
    public static final String FULFILLMENT_RESULT = "fulfillment";
    public static final String RETENTION_MARKETING_COPY_RESULT = "retention_marketing_copy";
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
    private RecommendationDataService recommendationDataService;

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

    @Autowired(required = false)
    public void setRecommendationDataService(RecommendationDataService recommendationDataService) {
        this.recommendationDataService = recommendationDataService;
    }

    public CompletableFuture<AgentResult> userProfileAsync(RecommendationRequest request) {
        return userProfileAsync(request, null);
    }

    public CompletableFuture<AgentResult> userProfileAsync(
            RecommendationRequest request, LlmCallBudget budget) {
        Map<String, Object> params = new HashMap<>();
        params.put("request", request);
        params.put("userId", safeUserId(request));
        addLlmBudget(params, budget, "user_profile", request);
        return userProfileAgent.runAsync(params, agentExecutor);
    }

    public CompletableFuture<AgentResult> productRecallAsync(RecommendationRequest request) {
        return productRecallAsync(request, null);
    }

    public CompletableFuture<AgentResult> productRecallAsync(
            RecommendationRequest request, LlmCallBudget budget) {
        Map<String, Object> params = new HashMap<>();
        params.put("request", request);
        params.put("numItems", request.getNumItems() * 2);
        addLlmBudget(params, budget, "product_recall", request);
        return productRecAgent.runAsync(params, agentExecutor);
    }

    public CompletableFuture<AgentResult> rerankAsync(
            RecommendationRequest request, UserProfile profile, List<Product> candidates) {
        return rerankAsync(request, profile, candidates, null);
    }

    public CompletableFuture<AgentResult> rerankAsync(
            RecommendationRequest request, UserProfile profile, List<Product> candidates,
            LlmCallBudget budget) {
        Map<String, Object> params = new HashMap<>();
        params.put("request", request);
        params.put("userProfile", profile == null ? new UserProfile() : profile);
        params.put("candidateProducts", candidates == null ? List.of() : candidates);
        params.put("numItems", request.getNumItems());
        addLlmBudget(params, budget, "product_rerank", request);
        return productRecAgent.runAsync(params, agentExecutor);
    }

    public CompletableFuture<AgentResult> inventoryAsync(RecommendationRequest request, List<Product> products) {
        return inventoryAgent.runAsync(Map.of("request", request, "products", products == null ? List.of() : products), agentExecutor);
    }

    public CompletableFuture<AgentResult> marketingCopyAsync(
            RecommendationRequest request, UserProfile profile, List<Product> products) {
        return marketingCopyAsync(request, profile, products, null);
    }

    public CompletableFuture<AgentResult> marketingCopyAsync(
            RecommendationRequest request, UserProfile profile, List<Product> products,
            LlmCallBudget budget) {
        Map<String, Object> params = new HashMap<>();
        params.put("request", request);
        params.put("userProfile", profile == null ? new UserProfile() : profile);
        params.put("products", products == null ? List.of() : products);
        addLlmBudget(params, budget, "marketing_copy", request);
        return marketingCopyAgent.runAsync(params, agentExecutor);
    }

    private void addLlmBudget(
            Map<String, Object> params, LlmCallBudget budget, String phase, RecommendationRequest request) {
        if (budget != null) {
            params.put("llmBudget", budget);
            params.put("llmFingerprint", phase + ":" + safeUserId(request) + ":" + request.getScene());
        }
    }

    public ToolObservation executeTool(String action, RecommendationPipelineState context) {
        String canonical = canonicalAction(action);
        RecommendationPipelineTool tool = toolRegistry.find(canonical)
                .orElseThrow(() -> new IllegalArgumentException("unknown tool: " + action));
        Map<String, Object> arguments = trustedArguments(canonical, context);
        long start = System.nanoTime();
        try {
            for (RecommendationPipelineHook hook : hooks) hook.beforeTool(canonical, context, arguments);
            ToolObservation observation = tool.execute(canonical, context);
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
            for (RecommendationPipelineHook hook : hooks) hook.afterTool(canonical, context, observation, latencyMs);
            return observation;
        } catch (Exception e) {
            double latencyMs = (System.nanoTime() - start) / 1_000_000.0;
            for (RecommendationPipelineHook hook : hooks) hook.onToolError(canonical, context, e, latencyMs);
            throw e;
        }
    }

    public String canonicalAction(String action) {
        if (action == null) return "";
        return switch (action.trim()) {
            case LEGACY_GET_CAMPAIGN_CONSTRAINTS -> LOAD_CAMPAIGN_CONSTRAINTS;
            case LEGACY_GET_ORDER_CONTEXT -> GET_RECENT_ORDERS;
            case CHECK_MARKET_ELIGIBILITY -> CHECK_FULFILLMENT;
            case LEGACY_SEARCH_PRODUCTS -> SEARCH_PRODUCTS;
            case LEGACY_RERANK_PRODUCTS -> RERANK_PRODUCTS;
            case LEGACY_CHECK_INVENTORY -> CHECK_INVENTORY;
            default -> action.trim();
        };
    }

    public Set<String> registeredToolNames() {
        return toolRegistry.names();
    }

    public UserProfile extractProfile(AgentResult result) {
        return result != null && result.isSuccess() && result.getData() != null
                ? (UserProfile) result.getData().get("profile") : null;
    }

    @SuppressWarnings("unchecked")
    public List<Product> extractProducts(AgentResult result, List<Product> fallback) {
        if (result == null || !result.isSuccess() || result.getData() == null) {
            return fallback == null ? List.of() : fallback;
        }
        Object value = result.getData().get("products");
        return value instanceof List<?> ? (List<Product>) value : (fallback == null ? List.of() : fallback);
    }

    public Set<String> extractAvailableIds(AgentResult result) {
        if (result == null || !result.isSuccess() || result.getData() == null) return Set.of();
        Object value = result.getData().get("available_products");
        if (!(value instanceof Iterable<?> values)) return Set.of();
        Set<String> available = new HashSet<>();
        for (Object item : values) if (item != null) available.add(String.valueOf(item));
        return available;
    }

    @SuppressWarnings("unchecked")
    public List<Map<String, String>> extractCopies(AgentResult result) {
        if (result == null || !result.isSuccess() || result.getData() == null) return List.of();
        Object value = result.getData().get("copies");
        return value instanceof List<?> ? (List<Map<String, String>>) value : List.of();
    }

    public List<Product> filterAvailableProducts(List<Product> rankedProducts, Set<String> availableIds, int numItems) {
        return filterAvailableProducts(rankedProducts, availableIds, Set.of(), numItems);
    }

    public List<Product> filterAvailableProducts(
            List<Product> rankedProducts, Set<String> availableIds, Set<String> vetoedIds, int numItems) {
        List<Product> ranked = rankedProducts == null ? List.of() : rankedProducts;
        Set<String> available = availableIds == null ? Set.of() : availableIds;
        Set<String> vetoed = vetoedIds == null ? Set.of() : vetoedIds;
        return ranked.stream()
                .filter(product -> available.contains(product.getProductId()))
                .filter(product -> !vetoed.contains(product.getProductId()))
                .limit(Math.max(0, numItems)).collect(Collectors.toList());
    }

    public void ensureSuccess(AgentResult result) {
        if (result == null || !result.isSuccess()) {
            String error = result == null ? "agent returned null" : result.getError();
            String agentName = result == null ? "agent" : result.getAgentName();
            throw new IllegalStateException(error == null ? agentName + " failed" : error);
        }
    }
    public Map<String, Object> trustedArguments(String action, RecommendationPipelineState context) {
        String canonical = canonicalAction(action);
        Map<String, Object> base = new LinkedHashMap<>(crossBorderState(context.getRequest()));
        switch (canonical) {
            case GET_USER_PROFILE, GET_RECENT_ORDERS ->
                    base.put("userId", safeUserId(context.getRequest()));
            case LOAD_CAMPAIGN_CONSTRAINTS ->
                    base.put("campaignId", String.valueOf(context.getRequest().getContext() == null
                            ? "default" : context.getRequest().getContext().getOrDefault("campaign_id", "default")));
            case SEARCH_PRODUCTS -> {
                    base.put("numItems", context.getRequest().getNumItems() * 2);
                    base.put("vetoRound", context.getRecallAfterVetoCount());
                    base.put("vetoedProductIds", context.vetoedProductIds());
            }
            case CHECK_FULFILLMENT ->
                    base.put("candidateCount", context.getRawProducts() == null ? 0 : context.getRawProducts().size());
            case RERANK_PRODUCTS -> {
                base.put("numItems", context.getRequest().getNumItems());
                base.put("hasProfile", context.getProfile() != null);
            }
            case CHECK_INVENTORY -> {
                    base.put("productCount", context.getRawProducts() == null ? 0 : context.getRawProducts().size());
                    base.put("vetoRound", context.getRecallAfterVetoCount());
            }
            case FILTER_PRODUCTS -> {
                base.put("rankedProductCount", context.getRankedProducts() == null ? 0 : context.getRankedProducts().size());
                base.put("availableCount", context.getAvailableIds() == null ? 0 : context.getAvailableIds().size());
            }
            case GENERATE_COPY, GENERATE_RETENTION_COPY ->
                    base.put("productCount", context.getFinalProducts() == null ? 0 : context.getFinalProducts().size());
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
        toolRegistry.register(LOAD_CAMPAIGN_CONSTRAINTS, List.of(LEGACY_GET_CAMPAIGN_CONSTRAINTS),
                (action, context) -> getCampaignConstraints(context));
        toolRegistry.register(GET_RECENT_ORDERS, List.of(LEGACY_GET_ORDER_CONTEXT),
                (action, context) -> getOrderContext(context));
        toolRegistry.register(CHECK_FULFILLMENT, List.of(CHECK_MARKET_ELIGIBILITY),
                (action, context) -> checkFulfillment(context));
        toolRegistry.register(SEARCH_PRODUCTS, List.of(LEGACY_SEARCH_PRODUCTS),
                (action, context) -> searchProducts(context));
        toolRegistry.register(RERANK_PRODUCTS, List.of(LEGACY_RERANK_PRODUCTS),
                (action, context) -> rerankProducts(context));
        toolRegistry.register(CHECK_INVENTORY, List.of(LEGACY_CHECK_INVENTORY),
                (action, context) -> checkInventory(context));
        toolRegistry.register(FILTER_PRODUCTS, (action, context) -> filterProducts(context));
        toolRegistry.register(GENERATE_COPY, (action, context) -> generateCopy(context, false));
        toolRegistry.register(GENERATE_RETENTION_COPY, (action, context) -> generateCopy(context, true));
    }

    private ToolObservation getCampaignConstraints(RecommendationPipelineState context) {
        Map<String, Object> requestContext = context.getRequest().getContext() == null
                ? Map.of() : context.getRequest().getContext();
        Map<String, Object> constraints = new LinkedHashMap<>();
        constraints.put("campaignId", requestContext.getOrDefault("campaign_id", "default-campaign"));
        constraints.put("objective", requestContext.getOrDefault("campaign_objective", "conversion"));
        constraints.put("maxDeliveryDays", requestContext.getOrDefault("max_delivery_days", 7));
        constraints.put("country", context.getRequest().countryOrDefault());
        constraints.put("currency", context.getRequest().currencyOrDefault());
        context.setCampaignConstraints(constraints);
        context.putAgentResult(CAMPAIGN_CONSTRAINTS_RESULT, AgentResult.builder()
                .agentName("campaign_constraints").success(true).data(constraints).confidence(1.0).build());
        String evidenceId = "campaign:" + constraints.get("campaignId");
        context.addEvidenceIds(List.of(evidenceId));
        return ToolObservation.builder().toolName(LOAD_CAMPAIGN_CONSTRAINTS)
                .summary("Loaded campaign constraints for " + constraints.get("campaignId"))
                .data(constraints).evidenceIds(List.of(evidenceId)).build();
    }

    private ToolObservation getOrderContext(RecommendationPipelineState context) {
        List<Map<String, Object>> orders = recommendationDataService == null
                ? requestOrderContext(context.getRequest())
                : recommendationDataService.orderContext(safeUserId(context.getRequest()));
        context.setOrderContext(orders);
        String source = recommendationDataService == null ? "request_context" : "postgresql";
        Map<String, Object> data = Map.of("orders", orders, "userId", safeUserId(context.getRequest()), "source", source);
        context.putDataSource("orders", source);
        context.putAgentResult(ORDER_CONTEXT_RESULT, AgentResult.builder()
                .agentName("recent_orders").success(true).data(data).confidence(1.0).build());
        List<String> evidenceIds = orders.isEmpty()
                ? List.of("orders:none:" + safeUserId(context.getRequest()))
                : java.util.stream.IntStream.range(0, orders.size())
                .mapToObj(i -> "order:" + String.valueOf(orders.get(i).getOrDefault("order_id", i))).toList();
        context.addEvidenceIds(evidenceIds);
        return ToolObservation.builder().toolName(GET_RECENT_ORDERS)
                .summary("Loaded " + orders.size() + " recent orders for retention planning")
                .data(data).evidenceIds(evidenceIds).build();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> requestOrderContext(RecommendationRequest request) {
        Object value = request.getContext() == null ? null : request.getContext().get("recent_orders");
        return value instanceof List<?> rows ? (List<Map<String, Object>>) rows : List.of();
    }

    private ToolObservation checkFulfillment(RecommendationPipelineState context) {
        List<Product> products = context.getRawProducts() == null ? List.of() : context.getRawProducts();
        int maxDeliveryDays = context.getCampaignConstraints() == null
                ? Integer.MAX_VALUE : integerValue(context.getCampaignConstraints().get("maxDeliveryDays"), 7);
        Set<String> eligible = products.stream()
                .filter(product -> marketEligible(product, context.getRequest()))
                .filter(product -> product.getDeliveryDays() <= maxDeliveryDays)
                .map(Product::getProductId)
                .collect(Collectors.toCollection(java.util.LinkedHashSet::new));
        context.setMarketEligibleIds(eligible);
        context.setFulfillmentEligibleIds(eligible);
        context.setRawProducts(products.stream().filter(product -> eligible.contains(product.getProductId())).toList());

        String source = recommendationDataService == null ? "pipeline_structured_fallback" : "postgresql_structured";
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eligibleProductIds", eligible);
        data.put("country", context.getRequest().countryOrDefault());
        data.put("region", context.getRequest().regionOrDefault());
        data.put("maxDeliveryDays", maxDeliveryDays);
        data.put("source", source);
        context.putDataSource("fulfillment", source);
        context.putAgentResult(FULFILLMENT_RESULT, AgentResult.builder()
                .agentName("fulfillment").success(true).data(data).confidence(1.0).build());
        List<String> evidenceIds = eligible.stream()
                .map(id -> "fulfillment:" + context.getRequest().countryOrDefault() + ":" + id).toList();
        context.addEvidenceIds(evidenceIds);
        return ToolObservation.builder().toolName(CHECK_FULFILLMENT)
                .summary("Fulfillment eligibility kept " + eligible.size() + " products for "
                        + context.getRequest().countryOrDefault())
                .data(data).evidenceIds(evidenceIds).build();
    }

    private boolean marketEligible(Product product, RecommendationRequest request) {
        if (product == null) return false;
        List<String> supported = product.getSupportedRegions() == null ? List.of() : product.getSupportedRegions();
        return product.isCrossBorderEligible()
                && request.platformOrDefault().equalsIgnoreCase(product.getPlatform())
                && request.currencyOrDefault().equalsIgnoreCase(product.getCurrency())
                && supported.stream().anyMatch(value -> value.equalsIgnoreCase(request.countryOrDefault())
                || value.equalsIgnoreCase(request.regionOrDefault()));
    }
    private ToolObservation getUserProfile(RecommendationPipelineState context) {
        AgentResult result = userProfileAsync(context.getRequest(), context.getLlmBudget()).join();
        ensureSuccess(result);
        context.putAgentResult(USER_PROFILE_RESULT, result);
        context.setProfile(extractProfile(result));
        String source = result.getData() == null ? "unknown"
                : String.valueOf(result.getData().getOrDefault("feature_source", "redis_or_fallback"));
        context.putDataSource("userProfile", source);
        String evidenceId = "profile:" + safeUserId(context.getRequest());
        context.addEvidenceIds(List.of(evidenceId));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evidenceId", evidenceId);
        data.put("source", source);
        data.put("crossBorder", crossBorderState(context.getRequest()));
        return ToolObservation.builder()
                .toolName(GET_USER_PROFILE)
                .summary("Generated profile for " + safeUserId(context.getRequest()) + " in "
                        + context.getRequest().countryOrDefault() + " from " + source)
                .data(data).evidenceIds(List.of(evidenceId)).build();
    }

    private ToolObservation searchProducts(RecommendationPipelineState context) {
        AgentResult result;
        String source;
        boolean vectorUsed = false;
        String fallbackReason = null;
        if (recommendationDataService == null) {
            result = productRecallAsync(context.getRequest(), context.getLlmBudget()).join();
            source = "legacy_connector";
            fallbackReason = "postgresql_data_service_unavailable";
        } else {
            Set<String> categories = context.getProfile() == null || context.getProfile().getPreferredCategories() == null
                    ? Set.of() : new HashSet<>(context.getProfile().getPreferredCategories());
            RecommendationDataService.ProductSearchResult recalled = recommendationDataService.searchProductsWithSource(
                    context.getRequest(), categories, Math.max(context.getRequest().getNumItems() * 2, 1));
            source = recalled.source();
            vectorUsed = recalled.vectorUsed();
            fallbackReason = recalled.fallbackReason();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("products", recalled.products());
            data.put("source", source);
            data.put("vector_used", vectorUsed);
            if (fallbackReason != null) data.put("fallback_reason", fallbackReason);
            result = AgentResult.builder().agentName("product_rec").success(true)
                    .data(data).confidence(vectorUsed ? 0.9 : 0.82).build();
        }
        ensureSuccess(result);
        if (context.hasUnhandledVeto()) {
            context.incrementRecallAfterVetoCount();
            context.clearAfterRerecall();
            context.markVetoesHandled();
        }
        context.putAgentResult(CROSS_BORDER_RECALL_RESULT, result);
        context.putDataSource("productRecall", source);
        Set<String> vetoed = context.vetoedProductIds();
        List<Product> filtered = extractProducts(result, List.of()).stream()
                .filter(product -> marketEligible(product, context.getRequest()))
                .filter(product -> !vetoed.contains(product.getProductId()))
                .toList();
        context.setRawProducts(filtered);
        List<String> evidenceIds = filtered.stream()
                .map(product -> "product:" + product.getProductId()).toList();
        context.addEvidenceIds(evidenceIds);
        Map<String, Object> observationData = new LinkedHashMap<>();
        observationData.put("productIds", filtered.stream().map(Product::getProductId).toList());
        observationData.put("source", source);
        observationData.put("vector_used", vectorUsed);
        if (fallbackReason != null) observationData.put("fallback_reason", fallbackReason);
        observationData.put("crossBorder", crossBorderState(context.getRequest()));
        return ToolObservation.builder().toolName(SEARCH_PRODUCTS)
                .summary("Recalled " + filtered.size() + " market-filtered products for "
                        + context.getRequest().countryOrDefault() + "/"
                        + context.getRequest().currencyOrDefault() + " from " + source)
                .data(observationData).evidenceIds(evidenceIds).build();
    }

    private ToolObservation rerankProducts(RecommendationPipelineState context) {
        AgentResult result = rerankAsync(context.getRequest(), context.getProfile(),
                context.getRawProducts() == null ? List.of() : context.getRawProducts(), context.getLlmBudget()).join();
        ensureSuccess(result);
        context.putAgentResult(RERANK_RESULT, result);
        context.setRankedProducts(extractProducts(result, context.getRawProducts()));
        if (context.getAvailableIds() != null) {
            context.setFinalProducts(filterAvailableProducts(
                    context.getRankedProducts(), context.getAvailableIds(), context.vetoedProductIds(),
                    context.getRequest().getNumItems()));
        }
        List<String> rankedIds = context.getRankedProducts() == null ? List.of()
                : context.getRankedProducts().stream().map(Product::getProductId).toList();
        List<String> finalIds = context.getFinalProducts() == null ? List.of()
                : context.getFinalProducts().stream().map(Product::getProductId).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("rankedProductIds", rankedIds);
        data.put("finalProductIds", finalIds);
        data.put("crossBorder", crossBorderState(context.getRequest()));
        return ToolObservation.builder().toolName(RERANK)
                .summary(finalIds.isEmpty() ? "Reranked products; fulfillment filtering remains pending"
                        : "Reranked and selected fulfillment-safe products: " + finalIds)
                .data(data).evidenceIds(rankedIds.stream()
                        .map(id -> "product:" + id).toList()).build();
    }

    private ToolObservation checkInventory(RecommendationPipelineState context) {
        List<Product> products = context.getRawProducts() == null ? List.of() : context.getRawProducts();
        if (context.getFulfillmentEligibleIds() != null) {
            products = products.stream()
                    .filter(product -> context.getFulfillmentEligibleIds().contains(product.getProductId())).toList();
        }
        AgentResult result;
        String source;
        if (recommendationDataService == null) {
            result = inventoryAsync(context.getRequest(), products).join();
            source = "inventory_agent_fallback";
        } else {
            Map<String, RecInventoryEntity> rows = recommendationDataService.inventoryByProduct(
                    context.getRequest().countryOrDefault(),
                    products.stream().map(Product::getProductId).toList());
            List<String> availableIds = products.stream().map(Product::getProductId)
                    .filter(id -> rows.containsKey(id)).filter(id -> inventoryAvailable(rows.get(id))).toList();
            Map<String, Object> details = new LinkedHashMap<>();
            rows.forEach((id, row) -> {
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("warehouseRegion", row.getWarehouseRegion());
                value.put("deliveryDays", row.getDeliveryDays());
                value.put("status", row.getFulfillmentStatus());
                value.put("stock", row.getStock());
                value.put("restricted", row.isRestricted());
                value.put("restrictionReason", row.getRestrictionReason());
                details.put(id, value);
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("available_products", availableIds);
            data.put("inventory", details);
            data.put("source", "postgresql");
            result = AgentResult.builder().agentName("inventory").success(true)
                    .data(data).confidence(1.0).build();
            source = "postgresql";
            context.setFulfillment(details);
        }
        ensureSuccess(result);
        context.putAgentResult(FULFILLMENT_INVENTORY_RESULT, result);
        context.setAvailableIds(extractAvailableIds(result));
        context.putDataSource("inventory", source);
        List<String> available = context.getAvailableIds().stream().toList();
        List<String> rejected = products.stream()
                .map(Product::getProductId)
                .filter(id -> !context.getAvailableIds().contains(id))
                .distinct()
                .toList();
        if (!rejected.isEmpty()) {
            context.write(AgentId.CONSTRAINT, BlackboardField.VETOES, VetoRecord.builder()
                    .source(AgentId.CONSTRAINT)
                    .productIds(rejected)
                    .reason("inventory_unavailable")
                    .build());
        }
        List<String> evidenceIds = available.stream()
                .map(id -> "inventory:" + context.getRequest().countryOrDefault() + ":" + id).toList();
        context.addEvidenceIds(evidenceIds);
        Map<String, Object> observationData = new LinkedHashMap<>();
        observationData.put("availableProductIds", available);
        observationData.put("vetoedProductIds", rejected);
        observationData.put("source", source);
        observationData.put("crossBorder", crossBorderState(context.getRequest()));
        return ToolObservation.builder().toolName(CHECK_INVENTORY)
                .summary("Country inventory kept " + available.size() + " products for "
                        + context.getRequest().countryOrDefault() + " from " + source
                        + (rejected.isEmpty() ? "" : "; vetoed " + rejected))
                .data(observationData).evidenceIds(evidenceIds).build();
    }

    private boolean inventoryAvailable(RecInventoryEntity row) {
        if (row == null || row.getStock() <= 0 || row.isRestricted()) return false;
        String status = row.getFulfillmentStatus();
        return status == null || (!status.equalsIgnoreCase("out_of_stock")
                && !status.equalsIgnoreCase("cancelled")
                && !status.equalsIgnoreCase("inactive"));
    }

    private ToolObservation filterProducts(RecommendationPipelineState context) {
        context.setFinalProducts(filterAvailableProducts(context.getRankedProducts(), context.getAvailableIds(),
                context.vetoedProductIds(), context.getRequest().getNumItems()));
        List<String> productIds = context.getFinalProducts().stream().map(Product::getProductId).toList();
        return ToolObservation.builder().toolName(FILTER_PRODUCTS)
                .summary("Selected final fulfillment-safe products: " + productIds)
                .data(Map.of("finalProductIds", productIds, "crossBorder", crossBorderState(context.getRequest())))
                .evidenceIds(productIds.stream().map(id -> "product:" + id).toList()).build();
    }
    private ToolObservation generateCopy(RecommendationPipelineState context, boolean retention) {
        AgentResult result = marketingCopyAsync(context.getRequest(), context.getProfile(),
                context.getFinalProducts() == null ? List.of() : context.getFinalProducts(), context.getLlmBudget()).join();
        ensureSuccess(result);
        String resultKey = retention ? RETENTION_MARKETING_COPY_RESULT : LOCALIZED_MARKETING_COPY_RESULT;
        context.putAgentResult(resultKey, result);
        Set<String> allowed = context.getFinalProducts() == null ? Set.of()
                : context.getFinalProducts().stream().map(Product::getProductId).collect(Collectors.toSet());
        List<Map<String, String>> copies = extractCopies(result).stream()
                .filter(copy -> allowed.contains(copy.getOrDefault("product_id", "")))
                .toList();
        context.setCopies(copies);
        List<String> evidenceIds = context.getCopies().stream()
                .map(copy -> "copy:" + copy.getOrDefault("product_id", "unknown")).toList();
        context.addEvidenceIds(evidenceIds);
        String toolName = retention ? GENERATE_RETENTION_COPY : GENERATE_COPY;
        return ToolObservation.builder().toolName(toolName)
                .summary("Generated " + context.getCopies().size() + " localized copies in "
                        + context.getRequest().localeOrDefault())
                .data(Map.of("copyProductIds", context.getCopies().stream()
                                .map(copy -> copy.getOrDefault("product_id", "")).toList(),
                        "crossBorder", crossBorderState(context.getRequest())))
                .evidenceIds(evidenceIds).build();
    }

    private int integerValue(Object value, int fallback) {
        if (value instanceof Number number) return number.intValue();
        try {
            return value == null ? fallback : Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}