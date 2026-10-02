-- Apply this idempotent upgrade after the after-sales tables exist.
-- It supports multi-instance job claiming, expired-worker recovery, and
-- indexed retry scans without depending on Hibernate ddl-auto=update.

ALTER TABLE after_sales_execution_jobs
    ADD COLUMN IF NOT EXISTS lease_owner VARCHAR(255),
    ADD COLUMN IF NOT EXISTS lease_until TIMESTAMP WITH TIME ZONE;

CREATE INDEX IF NOT EXISTS idx_execution_status_retry
    ON after_sales_execution_jobs (status, next_retry_at, created_at);

CREATE INDEX IF NOT EXISTS idx_execution_status_lease
    ON after_sales_execution_jobs (status, lease_until);

-- AfterSalesRunEventService serializes sequence allocation with a parent-run
-- row lock. This unique index is the final database invariant.
CREATE UNIQUE INDEX IF NOT EXISTS uk_after_sales_run_sequence
    ON after_sales_run_events (run_id, sequence);
