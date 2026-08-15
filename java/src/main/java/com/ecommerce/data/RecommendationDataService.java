package com.ecommerce.data;

import com.ecommerce.data.entity.RecInventoryEntity;
import com.ecommerce.data.entity.RecOrderEntity;
import com.ecommerce.data.entity.RecProductEntity;
import com.ecommerce.data.entity.RecUserEntity;
import com.ecommerce.data.entity.RecUserEventEntity;
import com.ecommerce.data.repository.RecInventoryRepository;
import com.ecommerce.data.repository.RecOrderRepository;
import com.ecommerce.data.repository.RecProductRepository;
import com.ecommerce.data.repository.RecUserEventRepository;
import com.ecommerce.data.repository.RecUserRepository;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Shared PostgreSQL data access for the recommendation runtime: users,
 * user_events, products, inventory, orders. Recommendation tools never read
 * from a static demo factory at runtime — the demo catalog is only a seed
 * initializer ({@link CatalogSeedInitializer}) writing into these tables.
 */
@Service
public class RecommendationDataService {

    private final RecProductRepository productRepository;
    private final RecInventoryRepository inventoryRepository;
    private final RecUserRepository userRepository;
    private final RecUserEventRepository userEventRepository;
    private final RecOrderRepository orderRepository;
    private final SemanticProductSearchService semanticSearch;

    public RecommendationDataService(RecProductRepository productRepository,
                                     RecInventoryRepository inventoryRepository,
                                     RecUserRepository userRepository,
                                     RecUserEventRepository userEventRepository,
                                     RecOrderRepository orderRepository,
                                     SemanticProductSearchService semanticSearch) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.userRepository = userRepository;
        this.userEventRepository = userEventRepository;
        this.orderRepository = orderRepository;
        this.semanticSearch = semanticSearch;
    }

    /** Market-filtered recall backed by PostgreSQL and the semantic search service. */
    public List<Product> searchProducts(RecommendationRequest request, Set<String> preferredCategories, int limit) {
        return searchProductsWithSource(request, preferredCategories, limit).products();
    }

    /**
     * Same recall operation with an explicit source report. The caller can
     * surface whether pgvector was used or whether the deterministic lexical
     * fallback was required.
     */
    public ProductSearchResult searchProductsWithSource(RecommendationRequest request,
                                                         Set<String> preferredCategories,
                                                         int limit) {
        List<RecProductEntity> rows = productRepository.findByMarket(
                request.platformOrDefault(), request.countryOrDefault(), request.regionOrDefault(),
                request.currencyOrDefault());
        String queryText = semanticQuery(request, preferredCategories);
        SemanticProductSearchService.RetrievalResult ranked = semanticSearch.retrieve(
                rows, preferredCategories, queryText, Math.max(1, limit));
        List<Product> products = ranked.products().stream()
                .map(this::toProduct)
                .collect(Collectors.toList());
        return new ProductSearchResult(products, ranked.source(), ranked.vectorUsed(), ranked.fallbackReason());
    }

    private String semanticQuery(RecommendationRequest request, Set<String> preferredCategories) {
        List<String> parts = new ArrayList<>();
        if (preferredCategories != null) parts.addAll(preferredCategories);
        Map<String, Object> context = request.getContext() == null ? Map.of() : request.getContext();
        for (String key : List.of("recent_views", "campaign_objective", "note", "keywords")) {
            Object value = context.get(key);
            if (value instanceof Iterable<?> values) {
                values.forEach(item -> parts.add(String.valueOf(item)));
            } else if (value != null) {
                parts.add(String.valueOf(value));
            }
        }
        parts.add(request.getScene() == null ? "homepage" : request.getScene());
        return parts.stream().filter(value -> value != null && !value.isBlank())
                .collect(Collectors.joining(" "));
    }

    public record ProductSearchResult(List<Product> products, String source,
                                      boolean vectorUsed, String fallbackReason) {
        public ProductSearchResult {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }
    /** Market-eligible products by explicit country list (used by market eligibility tool). */
    public List<Product> searchProductsForCountries(RecommendationRequest request, Set<String> countries, int limit) {
        List<RecProductEntity> rows = productRepository.findByMarket(
                request.platformOrDefault(), request.countryOrDefault(), request.currencyOrDefault());
        List<RecProductEntity> eligible = rows.stream()
                .filter(row -> supportsAny(row, countries))
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
        return eligible.stream().map(this::toProduct).collect(Collectors.toList());
    }

    public Optional<Product> findProduct(String productId) {
        return productRepository.findByProductId(productId).map(this::toProduct);
    }

    public List<Product> findAllProducts() {
        return productRepository.findAll().stream()
                .sorted(Comparator.comparing(RecProductEntity::getProductId))
                .map(this::toProduct)
                .collect(Collectors.toList());
    }

    public List<Product> findByProductIds(Set<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return List.of();
        }
        return productRepository.findByProductIdIn(new ArrayList<>(productIds)).stream()
                .map(this::toProduct)
                .collect(Collectors.toList());
    }

    /** Country-specific inventory row; absent row means the product is not sellable in that country. */
    public Optional<RecInventoryEntity> findInventory(String productId, String country) {
        return inventoryRepository.findByProductIdAndCountry(productId, country);
    }

    public Map<String, RecInventoryEntity> inventoryByProduct(String country, List<String> productIds) {
        if (productIds == null || productIds.isEmpty()) {
            return Map.of();
        }
        return inventoryRepository.findByProductIdInAndCountry(productIds, country).stream()
                .collect(Collectors.toMap(RecInventoryEntity::getProductId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
    }

    public List<RecInventoryEntity> inventoryForCountry(String country) {
        return inventoryRepository.findByCountry(country);
    }

    public Optional<RecUserEntity> findUser(String userId) {
        return userRepository.findByUserId(userId);
    }

    public RecUserEntity upsertUser(String userId, String country, String currency, String locale, String platform) {
        Optional<RecUserEntity> existing = userRepository.findByUserId(userId);
        if (existing.isPresent()) {
            return existing.get();
        }
        RecUserEntity created = RecUserEntity.builder()
                .userId(userId)
                .email(userId + "@local.ecommerce")
                .name(userId)
                .region("SEA")
                .country(country)
                .currency(currency)
                .locale(locale)
                .platform(platform)
                .build();
        return userRepository.save(created);
    }

    public void recordUserEvent(String userId, String behaviorType, String productId, Map<String, Object> metadata) {
        RecUserEventEntity event = RecUserEventEntity.builder()
                .id(java.util.UUID.randomUUID().toString())
                .userId(userId)
                .behaviorType(behaviorType)
                .productId(productId)
                .metadataJson(toJson(metadata))
                .createdAt(java.time.Instant.now())
                .build();
        userEventRepository.save(event);
    }

    public List<Map<String, Object>> recentUserEvents(String userId, int limit) {
        return userEventRepository.findTop20ByUserIdOrderByCreatedAtDesc(userId).stream()
                .limit(limit)
                .map(event -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("user_id", event.getUserId());
                    row.put("behavior_type", event.getBehaviorType());
                    row.put("product_id", event.getProductId());
                    row.put("timestamp", event.getCreatedAt().toString());
                    row.put("metadata", parseJson(event.getMetadataJson()));
                    return row;
                })
                .collect(Collectors.toList());
    }

    public long userEventCount(String userId) {
        return userEventRepository.countByUserId(userId);
    }

    public List<RecOrderEntity> ordersForUser(String userId) {
        return orderRepository.findByUserId(userId);
    }

    public List<Map<String, Object>> orderContext(String userId) {
        return orderRepository.findTop5ByUserIdOrderByOrderIdDesc(userId).stream()
                .map(this::toOrderMap)
                .collect(Collectors.toList());
    }

    private Map<String, Object> toOrderMap(RecOrderEntity order) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("order_id", order.getOrderId());
        row.put("country", order.getCountry());
        row.put("currency", order.getCurrency());
        row.put("warehouse_region", order.getWarehouseRegion());
        row.put("order_value", order.getOrderValue());
        row.put("payment_status", order.getPaymentStatus());
        row.put("fulfillment_status", order.getFulfillmentStatus());
        row.put("promised_delivery_days", order.getPromisedDeliveryDays());
        row.put("risk_level", order.getRiskLevel());
        row.put("product_ids", List.of(order.getProductIds().split(",")));
        return row;
    }

    private boolean supportsAny(RecProductEntity row, Set<String> countries) {
        Set<String> supported = split(row.getSupportedCountries());
        return countries.stream().anyMatch(supported::contains);
    }

    public static Set<String> split(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.isBlank()) {
            return Set.of();
        }
        return java.util.Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
    }

    public Product toProduct(RecProductEntity row) {
        return Product.builder()
                .productId(row.getProductId())
                .name(row.getName())
                .category(row.getCategory())
                .price(row.getPrice())
                .description(row.getDescription())
                .brand(row.getBrand())
                .sellerId(row.getSellerId())
                .stock(row.getStock())
                .tags(new ArrayList<>(split(row.getTags())))
                .supportedRegions(new ArrayList<>(split(row.getSupportedCountries())))
                .currency(row.getCurrency())
                .warehouseRegion(row.getWarehouseRegion())
                .deliveryDays(row.getDeliveryDays())
                .platform(row.getPlatform())
                .crossBorderEligible(row.isCrossBorderEligible())
                .build();
    }

    private String toJson(Object value) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, Map.class);
        } catch (Exception e) {
            return Map.of();
        }
    }
}
