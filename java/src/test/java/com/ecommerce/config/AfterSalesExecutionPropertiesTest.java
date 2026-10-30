package com.ecommerce.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AfterSalesExecutionPropertiesTest {

    @Test
    void defaultConfigurationIsValid() {
        assertThatCode(new AfterSalesExecutionProperties()::validate).doesNotThrowAnyException();
    }

    @Test
    void rejectsExecutorMaxSizeBelowCoreSize() {
        AfterSalesExecutionProperties properties = new AfterSalesExecutionProperties();
        properties.getExecutor().setCoreSize(8);
        properties.getExecutor().setMaxSize(4);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-size must be >= core-size");
    }

    @Test
    void rejectsSilentTaskDroppingPolicy() {
        AfterSalesExecutionProperties properties = new AfterSalesExecutionProperties();
        properties.getExecutor().setRejectionPolicy("DISCARD");

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be ABORT or CALLER_RUNS");
    }

    @Test
    void shopifyConnectorConfigurationDefaultsToMockAndRejectsUnsafeWriteOptions() {
        ShopifyAfterSalesProperties properties = new ShopifyAfterSalesProperties();
        properties.validate();
        assertThat(properties.getConnector()).isEqualTo("mock");
        assertThat(properties.isWriteEnabled()).isFalse();
        assertThat(properties.getAllowedWriteActions()).isEmpty();
        assertThat(properties.getApiVersion()).isEqualTo("2026-10");

        properties.setAllowedWriteActions(java.util.List.of("UNREVIEWED_MUTATION"));
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unsupported action");

        properties.setAllowedWriteActions(java.util.List.of());
        properties.setApiVersion("latest");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("quarterly YYYY-MM");
    }

    @Test
    void shopifyCredentialsRequireAnExactShopDomainAndNonBlankToken() {
        ShopifyAfterSalesProperties properties = new ShopifyAfterSalesProperties();
        properties.setConnector("shopify");
        assertThat(properties.isConfigured()).isFalse();
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a valid shop-domain");
        properties.setShopDomain("https://demo.myshopify.com/evil");
        properties.setAccessToken("not-real");
        assertThat(properties.isConfigured()).isFalse();
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires a valid shop-domain");
        properties.setShopDomain("demo.myshopify.com");
        assertThat(properties.isConfigured()).isTrue();
        assertThatCode(properties::validate).doesNotThrowAnyException();
        assertThat(properties.toString()).doesNotContain("not-real");
    }
}
