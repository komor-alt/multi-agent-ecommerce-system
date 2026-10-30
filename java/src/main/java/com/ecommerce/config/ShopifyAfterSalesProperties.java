package com.ecommerce.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "agent.aftersales.shopify")
public class ShopifyAfterSalesProperties {
    private static final Set<String> SUPPORTED_WRITE_ACTIONS = Set.of(
            "DELAY_COMPENSATION_COUPON", "DAMAGE_COMPENSATION_COUPON", "LOST_PARCEL_REFUND");

    private String connector = "mock";
    private String shopDomain = "";
    private String accessToken = "";
    private String apiVersion = "2026-10";
    private int connectTimeoutMs = 2_000;
    private int readTimeoutMs = 5_000;
    private boolean writeEnabled;
    private List<String> allowedWriteActions = List.of();

    @PostConstruct
    public void validate() {
        if (!"mock".equalsIgnoreCase(connector) && !"shopify".equalsIgnoreCase(connector)) {
            throw new IllegalStateException("agent.aftersales.shopify.connector must be mock or shopify");
        }
        if (apiVersion == null || !apiVersion.matches("\\d{4}-(01|04|07|10)")) {
            throw new IllegalStateException("agent.aftersales.shopify.api-version must be a quarterly YYYY-MM version");
        }
        if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
            throw new IllegalStateException("Shopify HTTP timeouts must be positive");
        }
        if (allowedWriteActions == null || !SUPPORTED_WRITE_ACTIONS.containsAll(allowedWriteActions)) {
            throw new IllegalStateException("Shopify allowed write actions contain an unsupported action");
        }
        if ("shopify".equalsIgnoreCase(connector) && !isConfigured()) {
            throw new IllegalStateException("Shopify connector requires a valid shop-domain and non-blank access-token");
        }
    }

    public boolean isConfigured() {
        return shopDomain != null
                && shopDomain.matches("(?i)^[a-z0-9][a-z0-9-]*\\.myshopify\\.com$")
                && accessToken != null && !accessToken.isBlank()
                && apiVersion != null && apiVersion.matches("\\d{4}-(01|04|07|10)");
    }
}
