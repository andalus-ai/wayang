CREATE TABLE IF NOT EXISTS wayang_deployment_operation_receipt (
    deployment_id VARCHAR(160) NOT NULL REFERENCES wayang_deployment_snapshot(deployment_id),
    operation_key VARCHAR(200) NOT NULL,
    command_fingerprint CHAR(64) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    status VARCHAR(16) NOT NULL,
    attempt INTEGER NOT NULL CHECK (attempt >= 0),
    target_operation_key VARCHAR(200) NOT NULL,
    result_code VARCHAR(120),
    result_detail VARCHAR(2048),
    lease_until TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (deployment_id, operation_key),
    CONSTRAINT ck_wayang_deployment_operation_status CHECK
        (status IN ('ACCEPTED', 'RUNNING', 'SUCCEEDED', 'FAILED', 'UNKNOWN'))
);

CREATE INDEX IF NOT EXISTS ix_wayang_deployment_operation_recovery
    ON wayang_deployment_operation_receipt (status, lease_until)
    WHERE status = 'RUNNING';
