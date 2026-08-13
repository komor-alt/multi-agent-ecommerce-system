package com.ecommerce.service;

import com.ecommerce.connector.ShopifyDevelopmentStoreConnector;
import com.ecommerce.model.Product;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class DemoDataService {

    private final ShopifyDevelopmentStoreConnector shopifyConnector;
    private final RedisFeatureStoreService featureStoreService;

    public DemoDataService(ShopifyDevelopmentStoreConnector shopifyConnector,
                           RedisFeatureStoreService featureStoreService) {
        this.shopifyConnector = shopifyConnector;
        this.featureStoreService = featureStoreService;
    }

    public List<Product> catalog() {
        return shopifyConnector.catalogSnapshot();
    }

    public Map<String, Object> catalogSummary() {
        return shopifyConnector.catalogSummary();
    }

    public Map<String, Object> seedBehaviors() {
        List<Map<String, Object>> seeded = new ArrayList<>();
        seeded.add(featureStoreService.recordBehavior("sea_sg_001", "view", "P001", metadata("shopify", "SG", "SGD", "homepage")));
        seeded.add(featureStoreService.recordBehavior("sea_sg_001", "cart", "P002", metadata("shopify", "SG", "SGD", "detail")));
        seeded.add(featureStoreService.recordBehavior("sea_sg_001", "purchase", "P004", metadata("shopify", "SG", "SGD", "checkout")));
        seeded.add(featureStoreService.recordBehavior("sea_my_001", "view", "P005", metadata("shopify", "MY", "MYR", "homepage")));
        seeded.add(featureStoreService.recordBehavior("sea_my_001", "cart", "P015", metadata("shopify", "MY", "MYR", "detail")));
        seeded.add(featureStoreService.recordBehavior("sea_th_001", "view", "P022", metadata("shopify", "TH", "THB", "homepage")));
        seeded.add(featureStoreService.recordBehavior("sea_id_001", "view", "P034", metadata("shopify", "ID", "IDR", "homepage")));
        seeded.add(featureStoreService.recordBehavior("sea_vn_001", "purchase", "P010", metadata("shopify", "VN", "VND", "checkout")));

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dataset_type", "deterministic_demo_behavior_seed");
        response.put("production_data", false);
        response.put("seeded_event_count", seeded.size());
        response.put("users", List.of("sea_sg_001", "sea_my_001", "sea_th_001", "sea_id_001", "sea_vn_001"));
        response.put("events", seeded);
        response.put("note", "Events are written to Redis when available, otherwise RedisFeatureStoreService keeps an in-memory fallback.");
        return response;
    }

    private Map<String, Object> metadata(String platform, String country, String currency, String scene) {
        return Map.of(
                "platform", platform,
                "region", "SEA",
                "country", country,
                "locale", country.equals("SG") ? "en-SG" : "en-" + country,
                "currency", currency,
                "scene", scene,
                "source", "demo_seed"
        );
    }
}

