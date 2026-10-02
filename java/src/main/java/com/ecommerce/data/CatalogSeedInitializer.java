package com.ecommerce.data;

import com.ecommerce.data.entity.RecInventoryEntity;
import com.ecommerce.data.entity.RecOrderEntity;
import com.ecommerce.data.entity.RecProductEntity;
import com.ecommerce.data.entity.RecUserEntity;
import com.ecommerce.data.repository.RecInventoryRepository;
import com.ecommerce.data.repository.RecOrderRepository;
import com.ecommerce.data.repository.RecProductRepository;
import com.ecommerce.data.repository.RecUserEventRepository;
import com.ecommerce.data.repository.RecUserRepository;
import com.ecommerce.data.DemoCatalogDataFactory;
import com.ecommerce.data.DemoFulfillmentDataFactory;
import com.ecommerce.model.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Demo catalog / fulfillment seed initializer. The demo data factories are
 * ONLY used here, at startup, to populate PostgreSQL (products,
 * inventory, orders, users, user_events). The recommendation
 * runtime reads the database; DemoCatalogDataFactory / DemoFulfillmentDataFactory
 * are never a runtime source.
 *
 * <p>Seed covers SG / TH / VN markets including TH-only and VN-only products,
 * so market filtering tests can prove that an SG request never returns a
 * TH-only or VN-only product.
 */
@Component
@Profile("!production & !prod")
@ConditionalOnProperty(name = "agent.demo.seed-enabled", havingValue = "true", matchIfMissing = true)
public class CatalogSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CatalogSeedInitializer.class);

    private final RecProductRepository productRepository;
    private final RecInventoryRepository inventoryRepository;
    private final RecOrderRepository orderRepository;
    private final RecUserRepository userRepository;
    private final RecUserEventRepository userEventRepository;
    private final RecommendationDataService dataService;
    private final SemanticProductSearchService semanticSearch;

    public CatalogSeedInitializer(RecProductRepository productRepository,
                                  RecInventoryRepository inventoryRepository,
                                  RecOrderRepository orderRepository,
                                  RecUserRepository userRepository,
                                  RecUserEventRepository userEventRepository,
                                  RecommendationDataService dataService,
                                  SemanticProductSearchService semanticSearch) {
        this.productRepository = productRepository;
        this.inventoryRepository = inventoryRepository;
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.userEventRepository = userEventRepository;
        this.dataService = dataService;
        this.semanticSearch = semanticSearch;
    }

    @Override
    public void run(ApplicationArguments args) {
        long started = System.nanoTime();
        seedProducts();
        seedInventory();
        seedUsersAndOrders();
        if (userEventRepository.count() == 0) seedUserEvents();
        log.info("recommendation database seed checked in {} ms",
                String.format("%.1f", (System.nanoTime() - started) / 1_000_000.0));
    }

    private void seedProducts() {
        List<Product> catalog = new ArrayList<>(DemoCatalogDataFactory.createCatalog());
        catalog.addAll(marketTestProducts());
        try (ProductEmbeddingService.SeedBatchScope ignored = semanticSearch.openSeedBatch()) {
            for (Product product : catalog) {
                if (productRepository.findByProductId(product.getProductId()).isEmpty()) {
                    productRepository.save(toEntity(product));
                }
                semanticSearch.writeProductEmbedding(product.getProductId(), product);
            }
        }
    }

    private void seedInventory() {
        List<RecProductEntity> products = productRepository.findAll();
        for (RecProductEntity product : products) {
            for (String country : RecommendationDataService.split(product.getSupportedCountries())) {
                if ("SEA".equalsIgnoreCase(country)) {
                    continue;
                }
                String inventoryId = product.getProductId() + ":" + country;
                if (inventoryRepository.findById(inventoryId).isEmpty()) {
                    inventoryRepository.save(inventoryRow(product, country));
                }
            }
        }
        // Country-specific demo scenarios that the base catalog does not express:
        // battery product is restricted by air freight in TH/VN; P006 has no stock anywhere.
        restrict("P008", "TH", "battery_air_freight_restriction");
        restrict("P008", "VN", "battery_air_freight_restriction");
        outOfStock("P006", "SG");
        outOfStock("P006", "TH");
    }

    private void seedUsersAndOrders() {
        for (Map<String, Object> orderRow : DemoFulfillmentDataFactory.createOrders()) {
            String userId = String.valueOf(orderRow.get("user_id"));
            String country = String.valueOf(orderRow.get("country"));
            dataService.upsertUser(userId, country,
                    String.valueOf(orderRow.get("currency")),
                    "en-" + country,
                    String.valueOf(orderRow.get("platform")));
            String orderId = String.valueOf(orderRow.get("order_id"));
            if (orderRepository.findByOrderId(orderId).isEmpty()) {
                orderRepository.save(toOrderEntity(orderRow));
            }
        }
        // retention scenario users
        dataService.upsertUser("user_889", "SG", "SGD", "en-SG", "shopify");
        dataService.upsertUser("user_001", "SG", "SGD", "en-SG", "shopify");
        dataService.upsertUser("user_208", "SG", "SGD", "en-SG", "shopify");
    }

    private void seedUserEvents() {
        // Durable user events (mirrors DemoDataService.seedBehaviors).
        dataService.recordUserEvent("sea_sg_001", "view", "P001", behaviorMeta("shopify", "SG", "SGD", "homepage"));
        dataService.recordUserEvent("sea_sg_001", "cart", "P002", behaviorMeta("shopify", "SG", "SGD", "detail"));
        dataService.recordUserEvent("sea_sg_001", "purchase", "P004", behaviorMeta("shopify", "SG", "SGD", "checkout"));
        dataService.recordUserEvent("sea_my_001", "view", "P005", behaviorMeta("shopify", "MY", "MYR", "homepage"));
        dataService.recordUserEvent("sea_my_001", "cart", "P015", behaviorMeta("shopify", "MY", "MYR", "detail"));
        dataService.recordUserEvent("sea_th_001", "view", "P022", behaviorMeta("shopify", "TH", "THB", "homepage"));
        dataService.recordUserEvent("sea_id_001", "view", "P034", behaviorMeta("shopify", "ID", "IDR", "homepage"));
        dataService.recordUserEvent("sea_vn_001", "purchase", "P010", behaviorMeta("shopify", "VN", "VND", "checkout"));
        dataService.recordUserEvent("user_001", "view", "P001", behaviorMeta("shopify", "SG", "SGD", "homepage"));
        dataService.recordUserEvent("user_001", "view", "P002", behaviorMeta("shopify", "SG", "SGD", "homepage"));
        dataService.recordUserEvent("user_001", "cart", "P003", behaviorMeta("shopify", "SG", "SGD", "detail"));
        dataService.recordUserEvent("user_208", "view", "P014", behaviorMeta("shopify", "SG", "SGD", "campaign"));
        dataService.recordUserEvent("user_208", "view", "P015", behaviorMeta("shopify", "SG", "SGD", "campaign"));
        dataService.recordUserEvent("user_889", "purchase", "P002", behaviorMeta("shopify", "SG", "SGD", "checkout"));
        dataService.recordUserEvent("user_889", "view", "P009", behaviorMeta("shopify", "SG", "SGD", "homepage"));
    }

    /** TH-only / VN-only / shared products used to prove market filtering. */
    private List<Product> marketTestProducts() {
        return List.of(
                marketProduct("P-SG-01", "Singapore Travel Charger", "accessory", 79.0, "S-SG-21", 180,
                        List.of("travel", "fast-charge"), List.of("SG", "MY"), "SGD", "SG", 2, true),
                marketProduct("P-SG-02", "Singapore Wireless Earbuds", "headphone", 129.0, "S-SG-22", 95,
                        List.of("wireless", "commute"), List.of("SG"), "SGD", "SG", 2, true),
                marketProduct("P-SG-03", "SEA Workday Accessory Set", "accessory", 59.0, "S-SG-23", 140,
                        List.of("office", "bundle"), List.of("SG", "TH"), "SGD", "SG", 3, true),
                marketProduct("P-TH-01", "Thai Silk Scarf", "fashion", 120.0, "S-TH-11", 120,
                        List.of("gift", "local"), List.of("TH"), "THB", "TH", 4, true),
                marketProduct("P-TH-02", "Thai Herbal Balm Set", "beauty", 45.0, "S-TH-12", 80,
                        List.of("local", "wellness"), List.of("TH", "VN"), "THB", "TH", 5, true),
                marketProduct("P-VN-01", "Vietnamese Coffee Gift Box", "grocery", 38.0, "S-VN-11", 200,
                        List.of("gift", "local"), List.of("VN"), "VND", "VN", 3, true),
                marketProduct("P-SG-TH-01", "Merlion Travel Card Holder", "accessory", 29.0, "S-SG-11", 300,
                        List.of("travel", "local"), List.of("SG", "TH"), "SGD", "SG", 2, true)
        );
    }

    private Product marketProduct(String id, String name, String category, double price, String sellerId, int stock,
                                  List<String> tags, List<String> supportedRegions, String currency,
                                  String warehouseRegion, int deliveryDays, boolean crossBorderEligible) {
        return Product.builder()
                .productId(id)
                .name(name)
                .category(category)
                .price(price)
                .description("Deterministic market-filtering test product for cross-border recommendation.")
                .brand("SeedLab")
                .sellerId(sellerId)
                .stock(stock)
                .tags(tags)
                .supportedRegions(supportedRegions)
                .currency(currency)
                .warehouseRegion(warehouseRegion)
                .deliveryDays(deliveryDays)
                .platform("shopify")
                .crossBorderEligible(crossBorderEligible)
                .build();
    }

    private RecProductEntity toEntity(Product product) {
        return RecProductEntity.builder()
                .id(java.util.UUID.randomUUID().toString())
                .productId(product.getProductId())
                .name(product.getName())
                .category(product.getCategory())
                .price(product.getPrice())
                .description(product.getDescription())
                .brand(product.getBrand())
                .sellerId(product.getSellerId())
                .stock(product.getStock())
                .tags(String.join(",", product.getTags() == null ? List.of() : product.getTags()))
                .supportedCountries(String.join(",", product.getSupportedRegions() == null ? List.of() : product.getSupportedRegions()))
                .currency(product.getCurrency())
                .warehouseRegion(product.getWarehouseRegion())
                .deliveryDays(product.getDeliveryDays())
                .platform(product.getPlatform())
                .crossBorderEligible(product.isCrossBorderEligible())
                .status("ACTIVE")
                .build();
    }

    private RecInventoryEntity inventoryRow(RecProductEntity product, String country) {
        boolean localWarehouse = country.equalsIgnoreCase(product.getWarehouseRegion());
        int stock = product.getStock();
        String status = stock <= 0 ? "out_of_stock"
                : product.getDeliveryDays() > 7 ? "customs_document_required"
                : localWarehouse ? "ready_to_ship" : "cross_border_shipping";
        return RecInventoryEntity.builder()
                .id(product.getProductId() + ":" + country)
                .productId(product.getProductId())
                .country(country)
                .warehouseRegion(product.getWarehouseRegion())
                .stock(stock)
                .fulfillmentStatus(status)
                .deliveryDays(product.getDeliveryDays() + (localWarehouse ? 0 : 2))
                .restricted(false)
                .build();
    }

    private void restrict(String productId, String country, String reason) {
        inventoryRepository.findById(productId + ":" + country).ifPresent(row -> {
            row.setRestricted(true);
            row.setFulfillmentStatus("restricted");
            row.setRestrictionReason(reason);
            inventoryRepository.save(row);
        });
    }

    private void outOfStock(String productId, String country) {
        inventoryRepository.findById(productId + ":" + country).ifPresent(row -> {
            row.setStock(0);
            row.setFulfillmentStatus("out_of_stock");
            inventoryRepository.save(row);
        });
    }

    private RecOrderEntity toOrderEntity(Map<String, Object> row) {
        @SuppressWarnings("unchecked")
        List<String> productIds = (List<String>) row.get("product_ids");
        return RecOrderEntity.builder()
                .id(java.util.UUID.randomUUID().toString())
                .orderId(String.valueOf(row.get("order_id")))
                .userId(String.valueOf(row.get("user_id")))
                .platform(String.valueOf(row.get("platform")))
                .country(String.valueOf(row.get("country")))
                .currency(String.valueOf(row.get("currency")))
                .warehouseRegion(String.valueOf(row.get("warehouse_region")))
                .productIds(String.join(",", productIds))
                .orderValue(((Number) row.get("order_value")).doubleValue())
                .paymentStatus(String.valueOf(row.get("payment_status")))
                .fulfillmentStatus(String.valueOf(row.get("fulfillment_status")))
                .promisedDeliveryDays(((Number) row.get("promised_delivery_days")).intValue())
                .riskLevel(String.valueOf(row.get("risk_level")))
                .build();
    }

    private Map<String, Object> behaviorMeta(String platform, String country, String currency, String scene) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("platform", platform);
        metadata.put("region", "SEA");
        metadata.put("country", country);
        metadata.put("locale", country.equals("SG") ? "en-SG" : "en-" + country);
        metadata.put("currency", currency);
        metadata.put("scene", scene);
        metadata.put("source", "demo_seed");
        return metadata;
    }
}
