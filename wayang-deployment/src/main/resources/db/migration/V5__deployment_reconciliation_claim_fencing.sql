-- Opaque lease ownership token prevents expired workers from completing reclaimed work.
ALTER TABLE wayang_deployment_reconciliation_work
    ADD COLUMN IF NOT EXISTS claim_token VARCHAR(64);
