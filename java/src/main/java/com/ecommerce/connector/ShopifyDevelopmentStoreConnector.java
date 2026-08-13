package com.ecommerce.connector;

import com.ecommerce.data.DemoCatalogDataFactory;
import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ShopifyDevelopmentStoreConnector implements CommerceConnector {

    private static final List<Product> CATALOG = DemoCatalogDataFactory.createCatalog();

    @Override
    public String platform() {
        return "shopify";
    }

    @Override
    public List<Product> searchProducts(RecommendationRequest request, UserProfile profile, int limit) {
        String platform = request.platformOrDefault();
        String region = request.regionOrDefault();
        String country = request.countryOrDefault();
        String currency = request.currencyOrDefault();
        Set<String> preferredCategories = profile == null || profile.getPreferredCategories() == null
                ? Set.of()
                : new HashSet<>(profile.getPreferredCategories());

        return CATALOG.stream()
                .filter(product -> product.getPlatform() != null && product.getPlatform().equalsIgnoreCase(platform))
                .sorted(Comparator
                        .comparing((Product product) -> !preferredCategories.contains(product.getCategory()))
                        .thenComparing(product -> !supports(product, country, region))
                        .thenComparing(product -> !currency.equalsIgnoreCase(product.getCurrency()))
                        .thenComparing(Product::getDeliveryDays)
                        .thenComparing(Comparator.comparingInt(Product::getStock).reversed()))
                .limit(Math.max(1, limit))
                .collect(Collectors.toList());
    }

    @Override
    public Map<String, Object> getOrderSnapshot(String userId, String region) {
        Map<String, Object> snapshot = new HashMap<>();
        snapshot.put("user_id", userId);
        snapshot.put("platform", platform());
        snapshot.put("region", region == null || region.isBlank() ? "SEA" : region);
        snapshot.put("last_order_currency", "SGD");
        snapshot.put("last_order_country", "SG");
        snapshot.put("recent_categories", List.of("accessory", "headphone"));
        snapshot.put("note", "Mocked Shopify Development Store order snapshot");
        return snapshot;
    }

    public List<Product> catalogSnapshot() {
        return new ArrayList<>(CATALOG);
    }

    public Map<String, Object> catalogSummary() {
        return DemoCatalogDataFactory.summarize(CATALOG);
    }

    private boolean supports(Product product, String country, String region) {
        List<String> supported = product.getSupportedRegions() == null ? List.of() : product.getSupportedRegions();
        return supported.stream().anyMatch(value -> value.equalsIgnoreCase(country) || value.equalsIgnoreCase(region));
    }
}
