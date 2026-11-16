-- The payload is authenticated-encryption ciphertext. Do not add plaintext command or audit columns.
CREATE TABLE IF NOT EXISTS wayang_deployment_reconciliation_context (
    deployment_id VARCHAR(160) NOT NULL,
    operation_key VARCHAR(200) NOT NULL,
    command_fingerprint CHAR(64) NOT NULL,
    operation VARCHAR(40) NOT NULL,
    encrypted_context BYTEA NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (deployment_id, operation_key),
    CONSTRAINT fk_wayang_deployment_reconciliation_receipt
        FOREIGN KEY (deployment_id, operation_key)
        REFERENCES wayang_deployment_operation_receipt (deployment_id, operation_key)
);
