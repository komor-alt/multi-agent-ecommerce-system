package com.ecommerce.data;

import com.ecommerce.data.entity.RecProductEntity;
import org.springframework.dao.EmptyResultDataAccessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL product retrieval with a real EmbeddingModel + pgvector path and
 * an explicit lexical fallback for offline/H2/degraded environments.
 *
 * <p>The embedding column intentionally has no fixed pgvector typmod. The
 * configured provider/model/dimension metadata is persisted beside each vector
 * and is required for both current-seed checks and query retrieval. An
 * untyped vector column cannot use pgvector's fixed-dimension ANN indexes;
 * retrieval therefore performs an exact cosine scan over the market-filtered
 * candidate set.</p>
 */
@Service
public class SemanticProductSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticProductSearchService.class);
    private static final List<String> EMBEDDING_METADATA_COLUMNS = List.of(
            "embedding_provider",
            "embedding_model",
            "embedding_dimensions",
            "embedding_content_hash");

    private final JdbcTemplate jdbcTemplate;
    private final ProductEmbeddingService embeddingService;
    private volatile boolean probed = false;
    private volatile boolean semanticAvailable = false;
    private volatile String probeFallbackReason = "pgvector_probe_not_run";

    public SemanticProductSearchService(JdbcTemplate jdbcTemplate,
                                        ProductEmbeddingService embeddingService) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingService = embeddingService;
    }

    public boolean isSemanticAvailable() {
        probe();
        return semanticAvailable;
    }

    public String semanticFallbackReason() {
        probe();
        return probeFallbackReason;
    }

    /** Opens the short-lived seed/import budget owned by the embedding service. */
    public ProductEmbeddingService.SeedBatchScope openSeedBatch() {
        return embeddingService.openSeedBatch();
    }

    /** Compatibility API retained for existing callers. */
    public List<RecProductEntity> rerankByRelevance(List<RecProductEntity> candidates,
                                                    Set<String> preferredCategories,
                                                    int limit) {
        return retrieve(candidates, preferredCategories, categoriesQuery(preferredCategories), limit).products();
    }

    /**
     * Rank candidates by cosine distance when pgvector and query embeddings are
     * available. No deterministic/hash vector is generated when the provider
     * is unavailable; the source and reason remain visible to callers.
     */
    public RetrievalResult retrieve(List<RecProductEntity> candidates,
                                    Set<String> preferredCategories,
                                    String queryText,
                                    int limit) {
        if (candidates == null || candidates.isEmpty()) {
            return new RetrievalResult(List.of(), "postgresql_empty", false, "no_market_candidates");
        }
        List<RecProductEntity> safe = new ArrayList<>(candidates);
        int target = Math.max(1, limit);
        String query = queryText == null || queryText.isBlank()
                ? categoriesQuery(preferredCategories) : queryText;
        if (query.isBlank()) {
            return lexicalFallback(safe, preferredCategories, query, target, "empty_semantic_query");
        }

        if (!probe()) {
            return lexicalFallback(safe, preferredCategories, query, target, probeFallbackReason);
        }

        ProductEmbeddingService.EmbeddingResult queryEmbedding;
        try (ProductEmbeddingService.QueryScope ignored = embeddingService.openQueryScope()) {
            queryEmbedding = embeddingService.embedQuery(query);
        }
        if (!queryEmbedding.vectorUsed()) {
            return lexicalFallback(safe, preferredCategories, query, target, queryEmbedding.fallbackReason());
        }

        try {
            List<RecProductEntity> semantic = rankByVector(
                    safe,
                    queryEmbedding.vectorCopy(),
                    queryEmbedding.dimension(),
                    embeddingService.configuredProvider(),
                    embeddingService.configuredModel(),
                    target);
            if (!semantic.isEmpty()) {
                return new RetrievalResult(semantic,
                        "postgresql+" + queryEmbedding.source() + "+pgvector",
                        true, null);
            }
            return lexicalFallback(safe, preferredCategories, query, target,
                    "no_embedded_candidates_for_configured_metadata");
        } catch (Exception error) {
            log.warn("semantic retrieval source=postgresql+pgvector vectorUsed=false fallbackReason=pgvector_query_failed");
            return lexicalFallback(safe, preferredCategories, query, target, "pgvector_query_failed");
        }
    }

    /**
     * Write one product embedding during seed/import. Existing vectors are
     * checked before provider invocation, so current content/model metadata is
     * skipped without consuming the seed budget.
     */
    public EmbeddingWriteResult writeProductEmbedding(String productId,
                                                      com.ecommerce.model.Product product) {
        if (!probe()) {
            return writeResult(productId, "postgresql", false, probeFallbackReason, "fallback");
        }
        String contentHash = ProductEmbeddingService.productContentHash(product);
        if (readEmbeddingMetadata(productId).isCurrent(
                embeddingService.configuredProvider(),
                embeddingService.configuredModel(),
                embeddingService.dimensions(),
                contentHash)) {
            return writeResult(productId, embeddingService.configuredProvider(),
                    true, null, "skipped_current");
        }
        ProductEmbeddingService.EmbeddingResult embedding = embeddingService.embedProduct(product);
        return persistEmbedding(productId, embedding, contentHash);
    }

    /**
     * Embeds and persists one product document. Offline mode returns a visible
     * fallback result and does not update the database.
     */
    public EmbeddingWriteResult writeEmbedding(String productId, String text) {
        if (!probe()) {
            return writeResult(productId, "postgresql", false, probeFallbackReason, "fallback");
        }
        String contentHash = ProductEmbeddingService.contentHash(text);
        if (readEmbeddingMetadata(productId).isCurrent(
                embeddingService.configuredProvider(),
                embeddingService.configuredModel(),
                embeddingService.dimensions(),
                contentHash)) {
            return writeResult(productId, embeddingService.configuredProvider(),
                    true, null, "skipped_current");
        }
        return persistEmbedding(productId,
                embeddingService.embedProductText(text, productId), contentHash);
    }

    private EmbeddingWriteResult persistEmbedding(String productId,
                                                  ProductEmbeddingService.EmbeddingResult embedding,
                                                  String contentHash) {
        if (!embedding.vectorUsed()) {
            return writeResult(productId, embedding.source(), false,
                    embedding.fallbackReason(), "fallback");
        }
        try {
            String sql = "UPDATE products SET embedding = ?::vector, "
                    + "embedding_provider = ?, embedding_model = ?, "
                    + "embedding_dimensions = ?, embedding_content_hash = ? "
                    + "WHERE product_id = ?";
            int affected = jdbcTemplate.update(sql,
                    ProductEmbeddingService.toPgVectorLiteral(embedding.vectorCopy()),
                    embeddingService.configuredProvider(),
                    embeddingService.configuredModel(),
                    embedding.dimension(),
                    contentHash,
                    productId);
            if (affected != 1) {
                String reason = affected == 0
                        ? "embedding_write_not_found"
                        : "embedding_write_unexpected_row_count";
                log.warn("embedding persistence source={} vectorUsed=false fallbackReason={} productId={} affectedRows={}",
                        embedding.source(), reason, productId, affected);
                return writeResult(productId, embedding.source(), false, reason, "failed");
            }
            return writeResult(productId, embedding.source(), true, null, "written");
        } catch (Exception error) {
            log.warn("embedding persistence source={} vectorUsed=false fallbackReason=embedding_write_failed productId={}",
                    embedding.source(), productId);
            return writeResult(productId, embedding.source(), false,
                    "embedding_write_failed", "failed");
        }
    }

    private EmbeddingWriteResult writeResult(String productId, String source,
                                             boolean vectorUsed, String fallbackReason,
                                             String action) {
        log.info("embedding persistence productId={} source={} vectorUsed={} action={} fallbackReason={}",
                productId, source, vectorUsed, action, fallbackReason);
        return new EmbeddingWriteResult(productId, source, vectorUsed, fallbackReason, action);
    }

    private EmbeddingMetadata readEmbeddingMetadata(String productId) {
        String sql = "SELECT embedding IS NOT NULL AS has_embedding, "
                + "vector_dims(embedding) AS vector_dimensions, "
                + "embedding_provider, embedding_model, embedding_dimensions, "
                + "embedding_content_hash FROM products WHERE product_id = ?";
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, productId);
            if (rows.isEmpty()) return EmbeddingMetadata.missing();
            Map<String, Object> row = rows.get(0);
            return new EmbeddingMetadata(
                    asBoolean(row.get("has_embedding")),
                    asInteger(row.get("vector_dimensions")),
                    asText(row.get("embedding_provider")),
                    asText(row.get("embedding_model")),
                    asInteger(row.get("embedding_dimensions")),
                    asText(row.get("embedding_content_hash")));
        } catch (Exception error) {
            log.info("embedding metadata source=postgresql vectorUsed=false fallbackReason=embedding_metadata_read_failed productId={}",
                    productId);
            return EmbeddingMetadata.missing();
        }
    }

    private List<RecProductEntity> rankByVector(List<RecProductEntity> candidates,
                                                float[] queryVector,
                                                int dimensions,
                                                String provider,
                                                String model,
                                                int limit) {
        List<String> ids = candidates.stream()
                .map(RecProductEntity::getProductId)
                .filter(Objects::nonNull)
                .toList();
        if (ids.isEmpty()) return List.of();

        String placeholders = ids.stream().map(ignored -> "?").collect(Collectors.joining(","));
        String sql = "SELECT product_id, embedding <=> ?::vector AS distance FROM products "
                + "WHERE product_id IN (" + placeholders + ") AND embedding IS NOT NULL "
                + "AND vector_dims(embedding) = ? "
                + "AND embedding_provider = ? AND embedding_model = ? "
                + "AND embedding_dimensions = ? "
                + "ORDER BY embedding <=> ?::vector ASC LIMIT ?";
        List<Object> parameters = new ArrayList<>();
        String literal = ProductEmbeddingService.toPgVectorLiteral(queryVector);
        parameters.add(literal);
        parameters.addAll(ids);
        parameters.add(dimensions);
        parameters.add(provider);
        parameters.add(model);
        parameters.add(dimensions);
        parameters.add(literal);
        parameters.add(Math.max(1, limit));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, parameters.toArray());
        Map<String, RecProductEntity> byId = candidates.stream()
                .collect(Collectors.toMap(RecProductEntity::getProductId, value -> value,
                        (a, b) -> a, LinkedHashMap::new));
        List<RecProductEntity> ranked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            RecProductEntity entity = byId.get(String.valueOf(row.get("product_id")));
            if (entity != null) ranked.add(entity);
        }
        return ranked;
    }

    private RetrievalResult lexicalFallback(List<RecProductEntity> candidates,
                                             Set<String> preferredCategories,
                                             String query,
                                             int limit,
                                             String reason) {
        String source = "postgresql+lexical_fallback";
        log.info("semantic retrieval source={} vectorUsed=false fallbackReason={}", source, reason);
        return new RetrievalResult(rankByLexical(candidates, preferredCategories, query, limit),
                source, false, reason);
    }

    private List<RecProductEntity> rankByLexical(List<RecProductEntity> candidates,
                                                 Set<String> preferredCategories,
                                                 String query,
                                                 int limit) {
        Set<String> normalized = preferredCategories == null ? Set.of() : preferredCategories.stream()
                .map(value -> value == null ? "" : value.toLowerCase())
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> queryTerms = java.util.Arrays.stream((query == null ? "" : query).toLowerCase()
                .split("\\s+"))
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        Comparator<RecProductEntity> comparator = Comparator
                .comparingInt((RecProductEntity p) -> lexicalScore(p, normalized, queryTerms))
                .reversed()
                .thenComparing(RecProductEntity::getDeliveryDays)
                .thenComparing(Comparator.comparingInt(RecProductEntity::getStock).reversed())
                .thenComparing(RecProductEntity::getProductId);
        return candidates.stream().sorted(comparator).limit(limit).collect(Collectors.toList());
    }

    private int lexicalScore(RecProductEntity product, Set<String> preferredCategories, Set<String> queryTerms) {
        int score = 0;
        String category = product.getCategory() == null ? "" : product.getCategory().toLowerCase();
        String text = String.join(" ",
                product.getName() == null ? "" : product.getName(),
                category,
                product.getDescription() == null ? "" : product.getDescription(),
                product.getTags() == null ? "" : product.getTags()).toLowerCase();
        if (preferredCategories.contains(category)) score += 4;
        if (preferredCategories.stream().anyMatch(category::contains)) score += 2;
        score += (int) Math.min(3, queryTerms.stream()
                .filter(term -> !term.isBlank() && text.contains(term)).count());
        return score;
    }

    private String categoriesQuery(Set<String> categories) {
        return categories == null ? "" : categories.stream()
                .filter(value -> value != null && !value.isBlank())
                .sorted().collect(Collectors.joining(" "));
    }

    /**
     * Read-only schema probe. Runtime recommendation paths never create an
     * extension, alter a table, or drop a column. Fixed-dimension legacy
     * vectors and missing metadata are explicit fallback states; the
     * destructive reset remains only in schema-postgresql.sql.
     */
    private boolean probe() {
        if (probed) return semanticAvailable;
        synchronized (this) {
            if (probed) return semanticAvailable;
            try {
                Boolean vectorTypeAvailable = jdbcTemplate.queryForObject(
                        "SELECT to_regtype('vector') IS NOT NULL", Boolean.class);
                if (!Boolean.TRUE.equals(vectorTypeAvailable)) {
                    return markProbeFailure("pgvector_extension_missing");
                }

                String columnType;
                try {
                    columnType = jdbcTemplate.queryForObject(
                            "SELECT format_type(a.atttypid, a.atttypmod) "
                                    + "FROM pg_attribute a "
                                    + "JOIN pg_class c ON c.oid = a.attrelid "
                                    + "WHERE c.relname = 'products' AND a.attname = 'embedding' "
                                    + "AND a.attnum > 0 AND NOT a.attisdropped",
                            String.class);
                } catch (EmptyResultDataAccessException error) {
                    return markProbeFailure("embedding_column_missing");
                }
                if (columnType == null || columnType.isBlank()) {
                    return markProbeFailure("embedding_column_missing");
                }
                if (!"vector".equalsIgnoreCase(columnType)) {
                    String reason = columnType.toLowerCase(Locale.ROOT).startsWith("vector(")
                            ? "legacy_fixed_dimension" : "embedding_column_incompatible";
                    return markProbeFailure(reason);
                }

                List<String> columns = jdbcTemplate.queryForList(
                        "SELECT column_name FROM information_schema.columns "
                                + "WHERE table_schema = current_schema() AND table_name = 'products' "
                                + "AND column_name IN ('embedding_provider', 'embedding_model', "
                                + "'embedding_dimensions', 'embedding_content_hash')",
                        String.class);
                if (!columns.containsAll(EMBEDDING_METADATA_COLUMNS)) {
                    return markProbeFailure("embedding_metadata_schema_missing");
                }

                semanticAvailable = true;
                probeFallbackReason = null;
                log.info("pgvector semantic retrieval enabled columnType=vector distance=cosine index=none");
            } catch (Exception error) {
                return markProbeFailure("pgvector_probe_failed");
            }
            probed = true;
            return semanticAvailable;
        }
    }

    private boolean markProbeFailure(String reason) {
        semanticAvailable = false;
        probeFallbackReason = reason;
        probed = true;
        log.info("pgvector semantic retrieval source=postgresql vectorUsed=false fallbackReason={}", reason);
        return false;
    }

    private static String asText(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static Integer asInteger(Object value) {
        return value instanceof Number number ? number.intValue()
                : value == null ? null : Integer.valueOf(String.valueOf(value));
    }

    private static boolean asBoolean(Object value) {
        if (value instanceof Boolean booleanValue) return booleanValue;
        return value != null && Boolean.parseBoolean(String.valueOf(value));
    }

    private record EmbeddingMetadata(boolean hasEmbedding,
                                     Integer vectorDimensions,
                                     String provider,
                                     String model,
                                     Integer dimensions,
                                     String contentHash) {
        private static EmbeddingMetadata missing() {
            return new EmbeddingMetadata(false, null, null, null, null, null);
        }

        private boolean isCurrent(String expectedProvider, String expectedModel,
                                  int expectedDimensions, String expectedContentHash) {
            return hasEmbedding
                    && vectorDimensions != null
                    && vectorDimensions == expectedDimensions
                    && Objects.equals(provider, expectedProvider)
                    && Objects.equals(model, expectedModel)
                    && Objects.equals(dimensions, expectedDimensions)
                    && Objects.equals(contentHash, expectedContentHash);
        }
    }

    public record RetrievalResult(List<RecProductEntity> products, String source,
                                  boolean vectorUsed, String fallbackReason) {
        public RetrievalResult {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }

    public record EmbeddingWriteResult(String productId, String source,
                                       boolean vectorUsed, String fallbackReason,
                                       String action) {
        public EmbeddingWriteResult(String productId, String source,
                                    boolean vectorUsed, String fallbackReason) {
            this(productId, source, vectorUsed, fallbackReason,
                    fallbackReason == null ? "written" : "fallback");
        }
    }
}
