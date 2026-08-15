package com.ecommerce.data;

import com.ecommerce.data.entity.RecProductEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * PostgreSQL product retrieval with a pgvector first path and a visible,
 * deterministic lexical fallback for H2/development environments.
 */
@Service
public class SemanticProductSearchService {

    private static final Logger log = LoggerFactory.getLogger(SemanticProductSearchService.class);
    private static final int EMBEDDING_DIM = 8;

    private final JdbcTemplate jdbcTemplate;
    private volatile boolean probed = false;
    private volatile boolean semanticAvailable = false;

    public SemanticProductSearchService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public boolean isSemanticAvailable() {
        probe();
        return semanticAvailable;
    }

    /** Compatibility API retained for existing callers. */
    public List<RecProductEntity> rerankByRelevance(List<RecProductEntity> candidates,
                                                    Set<String> preferredCategories,
                                                    int limit) {
        return retrieve(candidates, preferredCategories, categoriesQuery(preferredCategories), limit).products();
    }

    /**
     * Rank candidates by vector distance when pgvector and product embeddings
     * are available. The returned source is deliberately explicit so a
     * development fallback cannot be mistaken for the production path.
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
            return new RetrievalResult(safe.stream().limit(target).collect(Collectors.toList()),
                    "postgresql_structured", false, "empty_semantic_query");
        }
        String fallbackReason = "pgvector_unavailable";
        try {
            if (probe()) {
                List<RecProductEntity> semantic = rankByVector(safe, query, target);
                if (!semantic.isEmpty()) {
                    return new RetrievalResult(semantic, "postgresql+pgvector", true, null);
                }
                fallbackReason = "no_embedded_candidates";
            }
        } catch (Exception e) {
            semanticAvailable = false;
            fallbackReason = "pgvector_query_failed";
            log.warn("semantic ranking unavailable, falling back to lexical: {}", e.getMessage());
        }
        return new RetrievalResult(rankByLexical(safe, preferredCategories, query, target),
                "postgresql+lexical_fallback", false, fallbackReason);
    }

    /** Write the product vector during database seeding, never during request recall. */
    public void writeEmbedding(String productId, String text) {
        if (!probe()) return;
        try {
            jdbcTemplate.update("UPDATE products SET embedding = ?::vector WHERE product_id = ?",
                    embeddingLiteral(text), productId);
        } catch (Exception e) {
            log.debug("embedding write skipped for {}: {}", productId, e.getMessage());
        }
    }

    /** Deterministic eight-dimensional seed embedding used by the offline demo. */
    public static String embeddingLiteral(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest((text == null ? "" : text).getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder("[");
            for (int i = 0; i < EMBEDDING_DIM; i++) {
                int value = (hash[i * 2] & 0xFF) * 256 + (hash[i * 2 + 1] & 0xFF);
                double normalized = (value / 65535.0) * 2.0 - 1.0;
                if (i > 0) builder.append(',');
                builder.append(String.format(java.util.Locale.ROOT, "%.6f", normalized));
            }
            return builder.append(']').toString();
        } catch (Exception e) {
            throw new IllegalStateException("embedding hashing failed", e);
        }
    }

    private List<RecProductEntity> rankByVector(List<RecProductEntity> candidates, String query, int limit) {
        List<String> ids = candidates.stream().map(RecProductEntity::getProductId).collect(Collectors.toList());
        String inClause = ids.stream()
                .map(id -> "'" + id.replace("'", "''") + "'")
                .collect(Collectors.joining(","));
        String sql = "SELECT product_id, embedding <=> ?::vector AS distance FROM products "
                + "WHERE product_id IN (" + inClause + ") AND embedding IS NOT NULL "
                + "ORDER BY distance ASC LIMIT " + Math.max(1, limit);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, embeddingLiteral(query));
        Map<String, RecProductEntity> byId = candidates.stream()
                .collect(Collectors.toMap(RecProductEntity::getProductId, value -> value, (a, b) -> a));
        List<RecProductEntity> ranked = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            RecProductEntity entity = byId.get(String.valueOf(row.get("product_id")));
            if (entity != null) ranked.add(entity);
        }
        return ranked;
    }

    private List<RecProductEntity> rankByLexical(List<RecProductEntity> candidates,
                                                   Set<String> preferredCategories,
                                                   String query,
                                                   int limit) {
        Set<String> normalized = preferredCategories == null ? Set.of() : preferredCategories.stream()
                .map(value -> value == null ? "" : value.toLowerCase())
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet());
        Set<String> queryTerms = java.util.Arrays.stream(query.toLowerCase().split("\\s+")).collect(Collectors.toSet());
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
        String text = String.join(" ", product.getName(), category,
                product.getTags() == null ? "" : product.getTags()).toLowerCase();
        if (preferredCategories.contains(category)) score += 4;
        if (preferredCategories.stream().anyMatch(category::contains)) score += 2;
        score += (int) Math.min(3, queryTerms.stream().filter(term -> !term.isBlank() && text.contains(term)).count());
        return score;
    }

    private String categoriesQuery(Set<String> categories) {
        return categories == null ? "" : categories.stream().filter(value -> value != null && !value.isBlank())
                .sorted().collect(Collectors.joining(" "));
    }

    private boolean probe() {
        if (probed) return semanticAvailable;
        synchronized (this) {
            if (probed) return semanticAvailable;
            try {
                jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS vector");
                jdbcTemplate.execute("ALTER TABLE products ADD COLUMN IF NOT EXISTS embedding vector(8)");
                semanticAvailable = true;
                log.info("pgvector semantic retrieval enabled (embedding vector(8) column ready)");
            } catch (Exception e) {
                semanticAvailable = false;
                log.debug("pgvector unavailable ({}), using deterministic lexical fallback", e.getMessage());
            }
            probed = true;
            return semanticAvailable;
        }
    }

    public record RetrievalResult(List<RecProductEntity> products, String source,
                                  boolean vectorUsed, String fallbackReason) {
        public RetrievalResult {
            products = products == null ? List.of() : List.copyOf(products);
        }
    }
}
