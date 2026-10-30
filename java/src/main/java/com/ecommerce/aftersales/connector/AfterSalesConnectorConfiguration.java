package com.ecommerce.aftersales.connector;

import com.ecommerce.config.ShopifyAfterSalesProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class AfterSalesConnectorConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "agent.aftersales.shopify", name = "connector", havingValue = "mock", matchIfMissing = true)
    AfterSalesConnector mockShopifyAfterSalesConnector() {
        return new MockShopifyAfterSalesConnector();
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.aftersales.shopify", name = "connector", havingValue = "shopify")
    ShopifyGraphqlTransport shopifyGraphqlTransport(ShopifyAfterSalesProperties properties) {
        return new RestClientShopifyGraphqlTransport(properties);
    }

    @Bean
    @ConditionalOnProperty(prefix = "agent.aftersales.shopify", name = "connector", havingValue = "shopify")
    AfterSalesConnector shopifyGraphqlAfterSalesConnector(
            ShopifyAfterSalesProperties properties,
            ShopifyGraphqlTransport transport,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        return new ShopifyGraphqlAfterSalesConnector(properties, transport, objectMapper);
    }
}
