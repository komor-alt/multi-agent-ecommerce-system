package com.ecommerce.data;

import com.ecommerce.model.Product;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DemoCatalogDataFactoryTest {

    @Test
    void createsDeterministicCatalogWithCrossBorderCoverage() {
        List<Product> catalog = DemoCatalogDataFactory.createCatalog();
        Map<String, Object> summary = DemoCatalogDataFactory.summarize(catalog);

        assertThat(catalog).hasSizeGreaterThanOrEqualTo(50);
        assertThat(catalog).extracting(Product::getProductId).contains("P001", "P002", "P003", "P060");
        assertThat(catalog).anyMatch(product -> "shopee".equals(product.getPlatform()));
        assertThat(catalog).anyMatch(product -> !product.isCrossBorderEligible());
        assertThat(catalog).anyMatch(product -> product.getStock() <= 0);
        assertThat(summary).containsEntry("dataset_type", "deterministic_demo_seed");
        assertThat(summary).containsEntry("production_data", false);
        assertThat(summary.get("total_products")).isEqualTo(catalog.size());
    }
}
