package com.ecommerce.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** Market context that materially filters and explains recommendation output. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MarketContext {
    private String platform;
    private String region;
    private String country;
    private String locale;
    private String currency;
    /** Countries the request market can serve (country + regional support). */
    @Builder.Default
    private List<String> supportedCountries = List.of();
}
