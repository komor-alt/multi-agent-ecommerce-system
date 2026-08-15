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
DO $$
DECLARE
    current_embedding_type TEXT;
BEGIN
    IF to_regclass('products') IS NULL THEN
        RETURN;
    END IF;

    SELECT format_type(a.atttypid, a.atttypmod)
      INTO current_embedding_type
      FROM pg_attribute a
      JOIN pg_class c ON c.oid = a.attrelid
     WHERE c.relname = 'products'
       AND a.attname = 'embedding'
       AND a.attnum > 0
       AND NOT a.attisdropped;

    IF current_embedding_type IS NULL THEN
        EXECUTE 'ALTER TABLE products ADD COLUMN embedding vector';
    ELSIF current_embedding_type <> 'vector' THEN
        EXECUTE 'ALTER TABLE products DROP COLUMN embedding';
        EXECUTE 'ALTER TABLE products ADD COLUMN embedding vector';
        EXECUTE 'UPDATE products SET embedding_provider = NULL, embedding_model = NULL, embedding_dimensions = NULL, embedding_content_hash = NULL';
    END IF;
END $$;
