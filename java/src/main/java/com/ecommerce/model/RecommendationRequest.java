package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RecommendationRequest {
    private static final String DEFAULT_PLATFORM = "shopify";
    private static final String DEFAULT_REGION = "SEA";
    private static final String DEFAULT_COUNTRY = "SG";
    private static final String DEFAULT_LOCALE = "en-SG";
    private static final String DEFAULT_CURRENCY = "SGD";

    private String userId;
    @Builder.Default
    private String scene = "homepage";
    @Builder.Default
    private int numItems = 10;
    @Builder.Default
    private String platform = DEFAULT_PLATFORM;
    @Builder.Default
    private String region = DEFAULT_REGION;
    @Builder.Default
    private String country = DEFAULT_COUNTRY;
    @Builder.Default
    private String locale = DEFAULT_LOCALE;
    @Builder.Default
    private String currency = DEFAULT_CURRENCY;
    private Map<String, Object> context;

    public String platformOrDefault() {
        return isBlank(platform) ? DEFAULT_PLATFORM : platform;
    }

    public String regionOrDefault() {
        return isBlank(region) ? DEFAULT_REGION : region;
    }

    public String countryOrDefault() {
        return isBlank(country) ? DEFAULT_COUNTRY : country;
    }

    public String localeOrDefault() {
        return isBlank(locale) ? DEFAULT_LOCALE : locale;
    }

    public String currencyOrDefault() {
        return isBlank(currency) ? DEFAULT_CURRENCY : currency;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
