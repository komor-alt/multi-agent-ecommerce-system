-- Apply before deploying V2 against an existing PostgreSQL database.
-- Legacy jobs have no snapshot: execution fails closed and requires human re-review.
BEGIN;
ALTER TABLE after_sales_execution_jobs ADD COLUMN IF NOT EXISTS approval_id varchar(255);
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS run_id varchar(255);
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS order_id varchar(255);
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS user_id varchar(255);
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS action_type varchar(255);
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS currency varchar(255);
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS policy_version varchar(255);
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS proposal_version varchar(255);
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS amount numeric(38,2);
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS paid_amount numeric(38,2);
-- Hibernate PostgreSQL @Lob String uses an OID, consistent with the existing schema.
ALTER TABLE after_sales_approval_records ADD COLUMN IF NOT EXISTS evidence_ids_json oid;
COMMIT;
