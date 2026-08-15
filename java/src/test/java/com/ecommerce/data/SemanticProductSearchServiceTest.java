package com.ecommerce.data;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SemanticProductSearchServiceTest {

    @Test
    void createsPgvectorCompatibleEightDimensionLiteral() {
        String literal = SemanticProductSearchService.embeddingLiteral("travel charger accessory");

        assertThat(literal).startsWith("[").endsWith("]");
        assertThat(literal.substring(1, literal.length() - 1).split(",")).hasSize(8);
        assertThat(literal).isEqualTo(SemanticProductSearchService.embeddingLiteral("travel charger accessory"));
    }
}