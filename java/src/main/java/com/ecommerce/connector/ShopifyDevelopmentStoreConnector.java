package com.ecommerce.connector;

import com.ecommerce.data.RecommendationDataService;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class ShopifyDevelopmentStoreConnector implements CommerceConnector {

    private final RecommendationDataService dataService;

    public ShopifyDevelopmentStoreConnector(RecommendationDataService dataService) {
        this.dataService = dataService;
    }

    @Override
    public String platform() {
        return "shopify";
    }

    @Override
    public List<Product> searchProducts(RecommendationRequest request, UserProfile profile, int limit) {
        Set<String> preferredCategories = profile == null || profile.getPreferredCategories() == null
                ? Set.of() : new HashSet<>(profile.getPreferredCategories());
        return dataService.searchProducts(request, preferredCategories, limit);
    }

    @Override
    public Map<String, Object> getOrderSnapshot(String userId, String region) {
        List<Map<String, Object>> orders = dataService.orderContext(userId);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("user_id", userId);
        snapshot.put("platform", platform());
        snapshot.put("region", region == null || region.isBlank() ? "SEA" : region);
        snapshot.put("recent_orders", orders);
        snapshot.put("source", "postgresql.orders");
        return snapshot;
    }

    public List<Product> catalogSnapshot() {
        return dataService.findAllProducts();
    }

    public Map<String, Object> catalogSummary() {
        List<Product> products = catalogSnapshot();
        return Map.of(
                "source", "postgresql.products",
                "total", products.size(),
                "eligible", products.stream().filter(Product::isCrossBorderEligible).count(),
                "countries", products.stream().flatMap(product -> product.getSupportedRegions().stream()).distinct().sorted().toList());
    }
}