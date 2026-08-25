package com.ecommerce.data;

import com.ecommerce.model.Product;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ProductEmbeddingServiceTest {

    @Test
    void usesConfiguredOpenAiCompatibleModelAndDimensionWithInjectedDouble() {
        EmbeddingModel model = modelWithVector(0.1f, -0.2f, 0.3f);
        ProductEmbeddingService service = new ProductEmbeddingService(
                "qwen", "https://embedding.invalid/v1", "test-key", "qwen-embedding-test",
                3, true, 2, model);

        ProductEmbeddingService.EmbeddingResult result = service.embedQuery("travel charger");

        assertThat(result.vectorUsed()).isTrue();
        assertThat(result.source()).isEqualTo("qwen");
        assertThat(result.fallbackReason()).isNull();
        assertThat(result.dimension()).isEqualTo(3);
        assertThat(result.vectorCopy()).containsExactly(0.1f, -0.2f, 0.3f);
    }

    @Test
    void offlineDefaultNeverTouchesEmbeddingModel() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        ProductEmbeddingService service = new ProductEmbeddingService(
                "qwen", "https://embedding.invalid/v1", "test-key", "qwen-embedding-test",
                3, false, 2, model);

        ProductEmbeddingService.EmbeddingResult result = service.embedQuery("travel charger");

        assertThat(result.vectorUsed()).isFalse();
        assertThat(result.source()).isEqualTo("offline");
        assertThat(result.fallbackReason()).isEqualTo("embedding_live_disabled");
        verifyNoInteractions(model);
    }

    @Test
    void qwenDefaultsStayOfflineWithoutKeyOrBudget() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        ProductEmbeddingService service = new ProductEmbeddingService(
                "qwen",
                "https://dashscope.aliyuncs.com/compatible-mode",
                "",
                "text-embedding-v4",
                1024,
                false,
                0,
                model);

        ProductEmbeddingService.EmbeddingResult result = service.embedQuery("travel charger");

        assertThat(result.vectorUsed()).isFalse();
        assertThat(result.source()).isEqualTo("offline");
        assertThat(result.fallbackReason()).isEqualTo("embedding_live_disabled");
        assertThat(result.dimension()).isZero();
        verifyNoInteractions(model);
    }

    @Test
    void missingDimensionIsVisibleAndDoesNotCallProvider() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        ProductEmbeddingService service = new ProductEmbeddingService(
                "qwen", "https://embedding.invalid/v1", "test-key", "qwen-embedding-test",
                0, true, 2, model);

        ProductEmbeddingService.EmbeddingResult result = service.embedQuery("travel charger");

        assertThat(result.vectorUsed()).isFalse();
        assertThat(result.fallbackReason()).isEqualTo("embedding_dimensions_missing");
        verifyNoInteractions(model);
    }

    @Test
    void seedBatchBudgetDoesNotConsumeLaterQueryBudget() {
        EmbeddingModel model = modelWithVector(0.1f, 0.2f, 0.3f);
        ProductEmbeddingService service = new ProductEmbeddingService(
                "qwen", "https://embedding.invalid/v1", "test-key", "qwen-embedding-test",
                3, true, 1, model);

        try (ProductEmbeddingService.SeedBatchScope ignored = service.openSeedBatch()) {
            assertThat(service.embedProductText("first", "P001").vectorUsed()).isTrue();
            assertThat(service.embedProductText("second", "P002").vectorUsed()).isFalse();
        }

        assertThat(service.embedQuery("same query").vectorUsed()).isTrue();
        verify(model, times(2)).call(any());
    }

    @Test
    void sameQueryInLaterRequestGetsFreshBudgetAndFingerprintScope() {
        EmbeddingModel model = modelWithVector(0.1f, 0.2f, 0.3f);
        ProductEmbeddingService service = new ProductEmbeddingService(
                "qwen", "https://embedding.invalid/v1", "test-key", "qwen-embedding-test",
                3, true, 1, model);

        assertThat(service.embedQuery("same query").vectorUsed()).isTrue();
        assertThat(service.embedQuery("same query").vectorUsed()).isTrue();

        verify(model, times(2)).call(any());
    }

    @Test
    void productDocumentContainsAllRequiredFieldsAndHashChangesWithContent() {
        Product product = Product.builder()
                .productId("P001")
                .name("Travel Charger")
                .category("accessory")
                .description("Fast charging for SEA travel")
                .tags(List.of("travel", "fast-charge"))
                .build();

        String text = ProductEmbeddingService.productText(product);

        assertThat(text).contains("name: Travel Charger")
                .contains("category: accessory")
                .contains("description: Fast charging for SEA travel")
                .contains("tags: travel, fast-charge");
        assertThat(ProductEmbeddingService.productContentHash(product))
                .isNotEqualTo(ProductEmbeddingService.productContentHash(
                        Product.builder().productId("P001").name("Different").build()));
    }

    @Test
    void pgvectorLiteralUsesProviderComponentsWithoutSyntheticHashing() {
        assertThat(ProductEmbeddingService.toPgVectorLiteral(new float[]{1.0f, -0.25f, 0.125f}))
                .isEqualTo("[1.00000000,-0.25000000,0.12500000]");
    }

    private static EmbeddingModel modelWithVector(float... values) {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.call(any())).thenReturn(new EmbeddingResponse(
                List.of(new Embedding(values, 0))));
        return model;
    }
}
