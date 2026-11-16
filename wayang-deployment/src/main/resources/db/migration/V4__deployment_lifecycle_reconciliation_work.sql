-- Cross-process lease and outcome ledger for lifecycle reconciliation.
CREATE TABLE IF NOT EXISTS wayang_deployment_reconciliation_work (
    deployment_id VARCHAR(160) NOT NULL,
    operation_key VARCHAR(200) NOT NULL,
    command_fingerprint CHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    lease_until TIMESTAMP WITH TIME ZONE,
    retry_at TIMESTAMP WITH TIME ZONE,
    detail VARCHAR(2048),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (deployment_id, operation_key),
    CONSTRAINT fk_wayang_deployment_reconciliation_work_receipt
        FOREIGN KEY (deployment_id, operation_key)
        REFERENCES wayang_deployment_operation_receipt (deployment_id, operation_key)
);
CREATE INDEX IF NOT EXISTS idx_wayang_deployment_reconciliation_work_due
    ON wayang_deployment_reconciliation_work (status, retry_at, lease_until, updated_at);
