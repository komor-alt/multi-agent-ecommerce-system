package com.ecommerce.connector;

import com.ecommerce.model.Product;
import com.ecommerce.model.RecommendationRequest;
import com.ecommerce.model.UserProfile;

import java.util.List;
import java.util.Map;

public interface CommerceConnector {
    String platform();

    List<Product> searchProducts(RecommendationRequest request, UserProfile profile, int limit);

    Map<String, Object> getOrderSnapshot(String userId, String region);
}
