CREATE EXTENSION IF NOT EXISTS vector;

ALTER TABLE IF EXISTS users
    ADD COLUMN IF NOT EXISTS country VARCHAR(16) NOT NULL DEFAULT 'SG',
    ADD COLUMN IF NOT EXISTS currency VARCHAR(16) NOT NULL DEFAULT 'SGD',
    ADD COLUMN IF NOT EXISTS locale VARCHAR(32) NOT NULL DEFAULT 'en-SG',
    ADD COLUMN IF NOT EXISTS platform VARCHAR(32) NOT NULL DEFAULT 'shopify';

ALTER TABLE IF EXISTS products
    ADD COLUMN IF NOT EXISTS platform VARCHAR(64) NOT NULL DEFAULT 'shopify',
    ADD COLUMN IF NOT EXISTS supported_countries VARCHAR(255) NOT NULL DEFAULT 'SEA,SG',
    ADD COLUMN IF NOT EXISTS warehouse_region VARCHAR(16) NOT NULL DEFAULT 'SG',
    ADD COLUMN IF NOT EXISTS delivery_days INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS cross_border_eligible BOOLEAN NOT NULL DEFAULT TRUE,
    ADD COLUMN IF NOT EXISTS status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS recommendation_tags VARCHAR(255) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS embedding_provider VARCHAR(64),
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(256),
    ADD COLUMN IF NOT EXISTS embedding_dimensions INTEGER,
    ADD COLUMN IF NOT EXISTS embedding_content_hash VARCHAR(64);

ALTER TABLE IF EXISTS orders
    ADD COLUMN IF NOT EXISTS country VARCHAR(16) NOT NULL DEFAULT 'SG',
    ADD COLUMN IF NOT EXISTS warehouse_region VARCHAR(16) NOT NULL DEFAULT 'SG',
    ADD COLUMN IF NOT EXISTS product_ids_text VARCHAR(1000) NOT NULL DEFAULT '',
    ADD COLUMN IF NOT EXISTS payment_status VARCHAR(32) NOT NULL DEFAULT 'paid',
    ADD COLUMN IF NOT EXISTS fulfillment_status VARCHAR(40) NOT NULL DEFAULT 'ready_to_ship',
    ADD COLUMN IF NOT EXISTS promised_delivery_days INTEGER NOT NULL DEFAULT 3,
    ADD COLUMN IF NOT EXISTS risk_level VARCHAR(16) NOT NULL DEFAULT 'low';

-- Embeddings intentionally use an unbounded pgvector column because the Qwen
-- model and dimensions are supplied later. Existing fixed-dimension demo
-- vectors are cleared once; seed/import regenerates them only when live
-- embedding configuration and budget are explicitly enabled. No ANN index is
-- created: pgvector indexes require a fixed dimension, so retrieval uses an
-- exact cosine scan over the market-filtered candidate set.
--
-- This is an explicit schema/migration step. Runtime probe code is read-only
-- and will report a fallback if this migration has not been applied.
-- Add the column before normalizing its type so this remains idempotent
-- for products tables created by JPA or a previous schema run.
ALTER TABLE IF EXISTS products
    ADD COLUMN IF NOT EXISTS embedding vector;

-- PostgreSQL/pgvector accepts this as a no-op for an existing unbounded
-- vector and normalizes legacy vector(8) (or another fixed dimension) to
-- unbounded vector without dropping stored vectors.
-- The USING expression also clears only old demo vectors that lack
-- complete provenance metadata; complete metadata rows are preserved.
ALTER TABLE IF EXISTS products
    ALTER COLUMN embedding TYPE vector
    USING CASE
        WHEN embedding IS NOT NULL
             AND (embedding_provider IS NULL
                  OR embedding_model IS NULL
                  OR embedding_dimensions IS NULL
                  OR embedding_content_hash IS NULL)
        THEN NULL::vector
        ELSE embedding
    END;
