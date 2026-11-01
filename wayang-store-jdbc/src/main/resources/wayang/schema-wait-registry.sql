-- Wayang wait registry schema (standard SQL, run against PostgreSQL 16).
CREATE TABLE IF NOT EXISTS wayang_wait (
    token           VARCHAR(64)  PRIMARY KEY,
    run_id          VARCHAR(128) NOT NULL,
    node_id         VARCHAR(128) NOT NULL,
    kind            VARCHAR(16)  NOT NULL,
    info_json       TEXT         NOT NULL,
    due_at_ms       BIGINT,
    message_name    VARCHAR(256),
    correlation_key VARCHAR(256),
    created_ms      BIGINT       NOT NULL,
    CONSTRAINT wayang_wait_run_node UNIQUE (run_id, node_id)
);
CREATE INDEX IF NOT EXISTS wayang_wait_due ON wayang_wait (kind, due_at_ms);
CREATE INDEX IF NOT EXISTS wayang_wait_msg ON wayang_wait (message_name, correlation_key);

CREATE TABLE IF NOT EXISTS wayang_buffered_message (
    id              VARCHAR(64)  PRIMARY KEY,
    message_name    VARCHAR(256) NOT NULL,
    correlation_key VARCHAR(256) NOT NULL,
    payload_json    TEXT         NOT NULL,
    created_ms      BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS wayang_buffered_key ON wayang_buffered_message (message_name, correlation_key, created_ms);
