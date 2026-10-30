package com.ecommerce.aftersales.connector;

import com.ecommerce.config.ShopifyAfterSalesProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class AfterSalesConnectorConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AfterSalesConnectorConfiguration.class, PropertiesConfiguration.class)
            .withBean(ObjectMapper.class, ObjectMapper::new);

    @Test
    void defaultsToExactlyOneMockConnector() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(AfterSalesConnector.class);
            assertThat(context.getBean(AfterSalesConnector.class)).isInstanceOf(MockShopifyAfterSalesConnector.class);
            assertThat(context).doesNotHaveBean(ShopifyGraphqlTransport.class);
        });
    }

    @Test
    void shopifyModeRegistersExactlyOneReadOnlyConnector() {
        contextRunner.withPropertyValues(
                "agent.aftersales.shopify.connector=shopify",
                "agent.aftersales.shopify.shop-domain=contract-test.myshopify.com",
                "agent.aftersales.shopify.access-token=placeholder-not-a-real-token").run(context -> {
            assertThat(context).hasSingleBean(AfterSalesConnector.class);
            assertThat(context.getBean(AfterSalesConnector.class)).isInstanceOf(ShopifyGraphqlAfterSalesConnector.class);
            assertThat(context).hasSingleBean(ShopifyGraphqlTransport.class);
        });
    }

    @Test
    void shopifyModeFailsApplicationContextWhenCredentialsAreMissing() {
        contextRunner.withPropertyValues("agent.aftersales.shopify.connector=shopify").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "Shopify connector requires a valid shop-domain and non-blank access-token");
        });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ShopifyAfterSalesProperties.class)
    static class PropertiesConfiguration {}
}
