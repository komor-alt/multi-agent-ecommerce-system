package com.ecommerce.data;

import com.ecommerce.data.entity.RecProductEntity;
import com.ecommerce.model.Product;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class SemanticProductSearchServiceTest {

    @Test
    void providerVectorLiteralSupportsAnyConfiguredDimension() {
        String literal = ProductEmbeddingService.toPgVectorLiteral(
                new float[]{0.1f, -0.2f, 0.3f, 0.4f});

        assertThat(literal).startsWith("[").endsWith("]");
        assertThat(literal.substring(1, literal.length() - 1).split(",")).hasSize(4);
    }

    @Test
    void currentEmbeddingIsSkippedBeforeProviderCall() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingModel model = modelWithVector();
        ProductEmbeddingService embedding = embeddingService("model-a", model);
        stubHealthyProbe(jdbc);
        Product product = product();
        when(jdbc.queryForList(contains("embedding IS NOT NULL"), any(Object[].class)))
                .thenReturn(List.of(metadataRow(product, "model-a", true)));

        SemanticProductSearchService search = new SemanticProductSearchService(jdbc, embedding);

        SemanticProductSearchService.EmbeddingWriteResult result =
                search.writeProductEmbedding("P001", product);

        assertThat(result.action()).isEqualTo("skipped_current");
        assertThat(result.vectorUsed()).isTrue();
        verifyNoInteractions(model);
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void modelChangeMakesExistingEmbeddingStaleAndRebuildsIt() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingModel model = modelWithVector();
        ProductEmbeddingService embedding = embeddingService("model-new", model);
        stubHealthyProbe(jdbc);
        Product product = product();
        when(jdbc.queryForList(contains("embedding IS NOT NULL"), any(Object[].class)))
                .thenReturn(List.of(metadataRow(product, "model-old", true)));
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(1);

        SemanticProductSearchService search = new SemanticProductSearchService(jdbc, embedding);

        SemanticProductSearchService.EmbeddingWriteResult result =
                search.writeProductEmbedding("P001", product);

        assertThat(result.action()).isEqualTo("written");
        assertThat(result.vectorUsed()).isTrue();
        verify(model).call(any());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("embedding_provider", "embedding_model",
                "embedding_dimensions", "embedding_content_hash");
        assertThat(List.of(params.getValue())).contains("qwen", "model-new", 3, "P001",
                ProductEmbeddingService.productContentHash(product));
    }

    @Test
    void zeroAffectedRowsCannotBeReportedAsSuccessfulWrite() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EmbeddingModel model = modelWithVector();
        ProductEmbeddingService embedding = embeddingService("model-a", model);
        stubHealthyProbe(jdbc);
        Product product = product();
        when(jdbc.queryForList(contains("embedding IS NOT NULL"), any(Object[].class)))
                .thenReturn(List.of());
        when(jdbc.update(anyString(), any(Object[].class))).thenReturn(0);

        SemanticProductSearchService search = new SemanticProductSearchService(jdbc, embedding);

        SemanticProductSearchService.EmbeddingWriteResult result =
                search.writeProductEmbedding("P001", product);

        assertThat(result.vectorUsed()).isFalse();
        assertThat(result.action()).isEqualTo("failed");
        assertThat(result.fallbackReason()).isEqualTo("embedding_write_not_found");
    }

    @Test
    void querySqlIsolatesProviderModelAndDimension() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductEmbeddingService embedding = embeddingService("model-a", modelWithVector());
        stubHealthyProbe(jdbc);
        when(jdbc.queryForList(contains("embedding_provider = ?"), any(Object[].class)))
                .thenReturn(List.of(Map.of("product_id", "P001", "distance", 0.1)));

        SemanticProductSearchService search = new SemanticProductSearchService(jdbc, embedding);

        SemanticProductSearchService.RetrievalResult result = search.retrieve(
                List.of(entity("P001")), Set.of("accessory"), "travel accessory", 1);

        assertThat(result.vectorUsed()).isTrue();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> params = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).queryForList(sql.capture(), params.capture());
        assertThat(sql.getValue()).contains("embedding_provider = ?")
                .contains("embedding_model = ?")
                .contains("embedding_dimensions = ?")
                .contains("vector_dims(embedding) = ?");
        assertThat(List.of(params.getValue())).contains("qwen", "model-a", 3);
    }

    @Test
    void runtimeProbeIsReadOnlyAndDoesNotExecuteDdl() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductEmbeddingService embedding = mock(ProductEmbeddingService.class);
        stubHealthyProbe(jdbc);

        SemanticProductSearchService search = new SemanticProductSearchService(jdbc, embedding);

        assertThat(search.isSemanticAvailable()).isTrue();
        verify(jdbc, never()).execute(anyString());
    }

    @Test
    void fixedDimensionSchemaUsesExplicitFallbackReason() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ProductEmbeddingService embedding = mock(ProductEmbeddingService.class);
        when(jdbc.queryForObject(eq("SELECT to_regtype('vector') IS NOT NULL"), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForObject(contains("format_type"), eq(String.class)))
                .thenReturn("vector(8)");

        SemanticProductSearchService search = new SemanticProductSearchService(jdbc, embedding);

        SemanticProductSearchService.RetrievalResult result = search.retrieve(
                List.of(entity("P001")), Set.of("accessory"), "travel accessory", 1);

        assertThat(result.vectorUsed()).isFalse();
        assertThat(result.fallbackReason()).isEqualTo("legacy_fixed_dimension");
        verifyNoInteractions(embedding);
        verify(jdbc, never()).execute(anyString());
    }

    private static void stubHealthyProbe(JdbcTemplate jdbc) {
        when(jdbc.queryForObject(eq("SELECT to_regtype('vector') IS NOT NULL"), eq(Boolean.class)))
                .thenReturn(true);
        when(jdbc.queryForObject(contains("format_type"), eq(String.class)))
                .thenReturn("vector");
        when(jdbc.queryForList(contains("information_schema.columns"), eq(String.class)))
                .thenReturn(List.of("embedding_provider", "embedding_model",
                        "embedding_dimensions", "embedding_content_hash"));
    }

    private static ProductEmbeddingService embeddingService(String modelName, EmbeddingModel model) {
        return new ProductEmbeddingService(
                "qwen", "https://embedding.invalid/v1", "test-key", modelName,
                3, true, 1, model);
    }

    private static EmbeddingModel modelWithVector() {
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.call(any())).thenReturn(new EmbeddingResponse(
                List.of(new Embedding(new float[]{0.1f, 0.2f, 0.3f}, 0))));
        return model;
    }

    private static Product product() {
        return Product.builder()
                .productId("P001")
                .name("Travel Charger")
                .category("accessory")
                .description("Fast charging for SEA travel")
                .tags(List.of("travel", "fast-charge"))
                .build();
    }

    private static Map<String, Object> metadataRow(Product product, String model, boolean hasEmbedding) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("has_embedding", hasEmbedding);
        row.put("vector_dimensions", 3);
        row.put("embedding_provider", "qwen");
        row.put("embedding_model", model);
        row.put("embedding_dimensions", 3);
        row.put("embedding_content_hash", ProductEmbeddingService.productContentHash(product));
        return row;
    }

    private static RecProductEntity entity(String productId) {
        return RecProductEntity.builder()
                .productId(productId)
                .name("Travel Charger")
                .category("accessory")
                .description("Fast charging")
                .tags("travel")
                .deliveryDays(2)
                .stock(10)
                .build();
    }
}
