package com.ecommerce.data;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ProductionSeedIsolationTest {
    @ParameterizedTest
    @ValueSource(strings = {"production", "prod"})
    void productionNeverInstantiatesTheDemoDataWriterEvenWhenFlagIsEnabled(String profile) {
        new ApplicationContextRunner().withUserConfiguration(CatalogSeedInitializer.class)
                .withInitializer(context -> context.getEnvironment().setActiveProfiles(profile))
                .withPropertyValues("agent.demo.seed-enabled=true")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CatalogSeedInitializer.class));
    }

    @Test
    void demoSeedingCanAlsoBeDisabledInDevelopment() {
        new ApplicationContextRunner().withUserConfiguration(CatalogSeedInitializer.class)
                .withPropertyValues("agent.demo.seed-enabled=false")
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(CatalogSeedInitializer.class));
    }
}
