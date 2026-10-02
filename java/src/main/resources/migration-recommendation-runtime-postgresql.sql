-- Production migration for durable recommendation Agent orchestration.
-- Apply with Flyway/Liquibase before disabling Hibernate schema management.

CREATE TABLE IF NOT EXISTS recommendation_agent_runs (
    id VARCHAR(255) PRIMARY KEY,
    scene VARCHAR(255) NOT NULL,
    status VARCHAR(64) NOT NULL,
    stop_reason TEXT,
    request_json TEXT NOT NULL,
    recoverable BOOLEAN NOT NULL DEFAULT FALSE,
    execution_request_json TEXT,
    execution_attempt INTEGER NOT NULL DEFAULT 0,
    execution_token VARCHAR(255),
    execution_owner VARCHAR(255),
    execution_lease_until TIMESTAMPTZ,
    state_version BIGINT NOT NULL DEFAULT 0,
    optimistic_lock_version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);

ALTER TABLE recommendation_agent_runs ADD COLUMN IF NOT EXISTS recoverable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE recommendation_agent_runs ADD COLUMN IF NOT EXISTS execution_request_json TEXT;
ALTER TABLE recommendation_agent_runs ADD COLUMN IF NOT EXISTS execution_attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE recommendation_agent_runs ADD COLUMN IF NOT EXISTS execution_token VARCHAR(255);
ALTER TABLE recommendation_agent_runs ADD COLUMN IF NOT EXISTS execution_owner VARCHAR(255);
ALTER TABLE recommendation_agent_runs ADD COLUMN IF NOT EXISTS execution_lease_until TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_recommendation_run_recovery
    ON recommendation_agent_runs (status, recoverable, execution_lease_until, created_at);

CREATE TABLE IF NOT EXISTS recommendation_agent_tasks (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL REFERENCES recommendation_agent_runs(id),
    parent_agent_id VARCHAR(255) NOT NULL,
    parent_role VARCHAR(255) NOT NULL,
    role VARCHAR(255) NOT NULL,
    agent VARCHAR(64) NOT NULL,
    goal TEXT NOT NULL,
    tool_scopes_json TEXT NOT NULL,
    planned_action VARCHAR(255),
    dependencies_json TEXT NOT NULL,
    status VARCHAR(64) NOT NULL,
    context_json TEXT NOT NULL,
    artifact_ids_json TEXT NOT NULL,
    stop_reason TEXT,
    candidate_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    updated_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rec_task_run
    ON recommendation_agent_tasks (run_id, created_at);
CREATE INDEX IF NOT EXISTS idx_rec_task_status
    ON recommendation_agent_tasks (status, updated_at);

CREATE TABLE IF NOT EXISTS recommendation_agent_artifacts (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL REFERENCES recommendation_agent_runs(id),
    task_id VARCHAR(255) NOT NULL REFERENCES recommendation_agent_tasks(id),
    producer_role VARCHAR(255) NOT NULL,
    artifact_type VARCHAR(255) NOT NULL,
    base_candidate_version BIGINT NOT NULL,
    data_json TEXT NOT NULL,
    evidence_ids_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_rec_artifact_run_task
    ON recommendation_agent_artifacts (run_id, task_id);

CREATE TABLE IF NOT EXISTS recommendation_run_events (
    id VARCHAR(255) PRIMARY KEY,
    run_id VARCHAR(255) NOT NULL REFERENCES recommendation_agent_runs(id),
    sequence INTEGER NOT NULL,
    type VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(64) NOT NULL,
    summary TEXT,
    data_json TEXT NOT NULL,
    elapsed_ms DOUBLE PRECISION NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uk_recommendation_run_sequence UNIQUE (run_id, sequence)
);
CREATE INDEX IF NOT EXISTS idx_recommendation_event_run
    ON recommendation_run_events (run_id, sequence);

CREATE TABLE IF NOT EXISTS recommendation_outbox (
    id VARCHAR(255) PRIMARY KEY,
    dedup_key VARCHAR(255) NOT NULL,
    aggregate_type VARCHAR(64) NOT NULL,
    aggregate_id VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload_json TEXT NOT NULL,
    status VARCHAR(64) NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ,
    last_error TEXT,
    claim_token VARCHAR(255),
    lease_until TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    published_at TIMESTAMPTZ,
    CONSTRAINT uk_recommendation_outbox_dedup UNIQUE (dedup_key)
);
CREATE INDEX IF NOT EXISTS idx_recommendation_outbox_dispatch
    ON recommendation_outbox (status, next_attempt_at, created_at);

-- Additive upgrade for deployments that already applied the original runtime migration.
ALTER TABLE recommendation_outbox ADD COLUMN IF NOT EXISTS claim_token VARCHAR(255);
ALTER TABLE recommendation_outbox ADD COLUMN IF NOT EXISTS lease_until TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_recommendation_outbox_lease
    ON recommendation_outbox (status, lease_until);
