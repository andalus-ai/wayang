CREATE TABLE IF NOT EXISTS wayang_pressure_event (
    event_id UUID PRIMARY KEY,
    stream_id VARCHAR(256) NOT NULL,
    sequence_no BIGINT NOT NULL CHECK (sequence_no > 0),
    recorded_at TIMESTAMPTZ NOT NULL,
    recorded_at_nano INTEGER NOT NULL CHECK (recorded_at_nano BETWEEN 0 AND 999999999),
    payload BYTEA NOT NULL,
    consumer_id VARCHAR(256),
    claim_token UUID,
    lease_expires_at TIMESTAMPTZ,
    delivery_attempts INTEGER NOT NULL DEFAULT 0 CHECK (delivery_attempts >= 0),
    acknowledged_at TIMESTAMPTZ,
    CONSTRAINT uq_wayang_pressure_event_stream_sequence UNIQUE (stream_id, sequence_no),
    CONSTRAINT ck_wayang_pressure_event_claim_state CHECK (
        (consumer_id IS NULL AND claim_token IS NULL AND lease_expires_at IS NULL)
        OR
        (consumer_id IS NOT NULL AND claim_token IS NOT NULL AND lease_expires_at IS NOT NULL)
    )
);

CREATE INDEX IF NOT EXISTS ix_wayang_pressure_event_pending
    ON wayang_pressure_event (stream_id, sequence_no)
    WHERE acknowledged_at IS NULL;


CREATE TABLE IF NOT EXISTS wayang_pressure_event_schema (
    singleton BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (singleton = TRUE),
    schema_version INTEGER NOT NULL CHECK (schema_version > 0),
    installed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

INSERT INTO wayang_pressure_event_schema (singleton, schema_version)
VALUES (TRUE, 1)
ON CONFLICT (singleton) DO UPDATE SET schema_version = GREATEST(
    wayang_pressure_event_schema.schema_version, EXCLUDED.schema_version);
