CREATE TABLE IF NOT EXISTS wayang_deployment_snapshot (
    deployment_id VARCHAR(160) PRIMARY KEY,
    tenant_id VARCHAR(160) NOT NULL,
    environment VARCHAR(120) NOT NULL,
    target_id VARCHAR(200) NOT NULL,
    release_id VARCHAR(200) NOT NULL,
    state VARCHAR(32) NOT NULL,
    version BIGINT NOT NULL CHECK (version >= 0),
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    last_reason VARCHAR(2048),
    CONSTRAINT ck_wayang_deployment_state CHECK (state IN (
        'INSTALLED', 'ACTIVATING', 'ACTIVE', 'DEGRADED', 'FAILED',
        'ROLLING_BACK', 'ROLLED_BACK', 'DEACTIVATING', 'DEACTIVATED'
    ))
);

CREATE TABLE IF NOT EXISTS wayang_deployment_event (
    deployment_id VARCHAR(160) NOT NULL REFERENCES wayang_deployment_snapshot(deployment_id),
    sequence BIGINT NOT NULL CHECK (sequence >= 0),
    from_state VARCHAR(32),
    to_state VARCHAR(32) NOT NULL,
    actor VARCHAR(200) NOT NULL,
    reason VARCHAR(2048) NOT NULL,
    correlation_id VARCHAR(200) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (deployment_id, sequence),
    CONSTRAINT uq_wayang_deployment_idempotency UNIQUE (deployment_id, idempotency_key)
);

CREATE INDEX IF NOT EXISTS ix_wayang_deployment_tenant_env
    ON wayang_deployment_snapshot (tenant_id, environment, updated_at DESC);
