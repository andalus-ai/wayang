-- Wayang definition store schema (standard SQL, run against PostgreSQL 16).
CREATE TABLE IF NOT EXISTS wayang_definition_draft (
    tenant_id   VARCHAR(128) NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    def_id      VARCHAR(128) NOT NULL,
    revision    BIGINT       NOT NULL,
    doc_json    TEXT         NOT NULL,
    updated_ms  BIGINT       NOT NULL,
    updated_by  VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, kind, def_id)
);

CREATE TABLE IF NOT EXISTS wayang_definition_version (
    tenant_id   VARCHAR(128) NOT NULL,
    kind        VARCHAR(16)  NOT NULL,
    def_id      VARCHAR(128) NOT NULL,
    version     INTEGER      NOT NULL,
    revision    BIGINT       NOT NULL,
    doc_json    TEXT         NOT NULL,
    updated_ms  BIGINT       NOT NULL,
    updated_by  VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, kind, def_id, version)
);

CREATE TABLE IF NOT EXISTS wayang_project (
    tenant_id   VARCHAR(128)  NOT NULL,
    project_id  VARCHAR(128)  NOT NULL,
    name        VARCHAR(256)  NOT NULL,
    description TEXT,
    created_by  VARCHAR(128)  NOT NULL,
    created_ms  BIGINT        NOT NULL,
    updated_ms  BIGINT        NOT NULL,
    PRIMARY KEY (tenant_id, project_id)
);

CREATE TABLE IF NOT EXISTS wayang_secret (
    tenant_id  VARCHAR(128)  NOT NULL,
    name       VARCHAR(100)  NOT NULL,
    version    INT           NOT NULL,
    key_id     VARCHAR(32)   NOT NULL,
    sealed     TEXT          NOT NULL,
    updated_ms BIGINT        NOT NULL,
    updated_by VARCHAR(128)  NOT NULL,
    PRIMARY KEY (tenant_id, name)
);

CREATE TABLE IF NOT EXISTS wayang_trigger (
    tenant_id      VARCHAR(128) NOT NULL,
    trigger_id     VARCHAR(64)  NOT NULL,
    kind           VARCHAR(16)  NOT NULL,
    process_id     VARCHAR(128) NOT NULL,
    enabled        BOOLEAN      NOT NULL,
    config         TEXT         NOT NULL,
    cursor_value   TEXT,
    secret_hash    VARCHAR(64),
    last_polled_ms BIGINT,
    last_error     VARCHAR(600),
    failures       INT          NOT NULL,
    created_by     VARCHAR(128) NOT NULL,
    created_ms     BIGINT       NOT NULL,
    PRIMARY KEY (tenant_id, trigger_id)
);

CREATE TABLE IF NOT EXISTS wayang_oauth_connection (
    tenant_id      VARCHAR(128) NOT NULL,
    connection_id  VARCHAR(64)  NOT NULL,
    provider       VARCHAR(64)  NOT NULL,
    client_id      VARCHAR(300) NOT NULL,
    client_secret  VARCHAR(100) NOT NULL,
    scopes         TEXT         NOT NULL,
    status         VARCHAR(16)  NOT NULL,
    created_by     VARCHAR(128) NOT NULL,
    created_ms     BIGINT       NOT NULL,
    connected_ms   BIGINT,
    PRIMARY KEY (tenant_id, connection_id)
);

CREATE TABLE IF NOT EXISTS wayang_oauth_state (
    state          VARCHAR(100) PRIMARY KEY,
    tenant_id      VARCHAR(128) NOT NULL,
    connection_id  VARCHAR(64)  NOT NULL,
    verifier       VARCHAR(200) NOT NULL,
    expires_ms     BIGINT       NOT NULL
);

CREATE TABLE IF NOT EXISTS wayang_mcp_server (
    tenant_id   VARCHAR(128) NOT NULL,
    server_id   VARCHAR(64)  NOT NULL,
    definition  TEXT         NOT NULL,
    created_ms  BIGINT       NOT NULL,
    PRIMARY KEY (tenant_id, server_id)
);

-- Append-only: the application has no UPDATE or DELETE for this table. Grant the app role INSERT and SELECT only.
CREATE TABLE IF NOT EXISTS wayang_audit (
    tenant_id  VARCHAR(128) NOT NULL,
    at_ms      BIGINT       NOT NULL,
    entry      TEXT         NOT NULL
);
CREATE INDEX IF NOT EXISTS wayang_audit_tenant_time ON wayang_audit (tenant_id, at_ms DESC);

-- Feedback (ratings, comments, bug and error reports). Append-only like the audit log; text is scrubbed of credentials before it gets here.
CREATE TABLE IF NOT EXISTS wayang_feedback (
    tenant_id  VARCHAR(128) NOT NULL,
    at_ms      BIGINT       NOT NULL,
    kind       VARCHAR(16)  NOT NULL,
    entry      TEXT         NOT NULL
);
CREATE INDEX IF NOT EXISTS wayang_feedback_tenant_time ON wayang_feedback (tenant_id, at_ms DESC);
-- Feedback entries are never edited; the ones an admin marked done have a row here (open = no row).
CREATE TABLE IF NOT EXISTS wayang_feedback_status (
    tenant_id VARCHAR(128) NOT NULL,
    id        VARCHAR(40)  NOT NULL,
    status    VARCHAR(16)  NOT NULL,
    PRIMARY KEY (tenant_id, id)
);

-- EIP state. Claim checks are keyed by namespace and token with an expiry, aggregation groups are append-only rows closed by DELETE.
CREATE TABLE IF NOT EXISTS wayang_eip_claim (
    ns          VARCHAR(512) NOT NULL,
    token       VARCHAR(64)  NOT NULL,
    expires_ms  BIGINT       NOT NULL,
    body        TEXT         NOT NULL,
    PRIMARY KEY (ns, token)
);
CREATE INDEX IF NOT EXISTS wayang_eip_claim_expiry ON wayang_eip_claim (expires_ms);

CREATE TABLE IF NOT EXISTS wayang_eip_agg (
    seq         BIGSERIAL PRIMARY KEY,
    ns          VARCHAR(512) NOT NULL,
    k           VARCHAR(256) NOT NULL,
    created_ms  BIGINT       NOT NULL,
    msg         TEXT         NOT NULL
);
CREATE INDEX IF NOT EXISTS wayang_eip_agg_group ON wayang_eip_agg (ns, k, seq);
CREATE INDEX IF NOT EXISTS wayang_eip_agg_created ON wayang_eip_agg (created_ms);

-- Idempotent receiver: keys are stored as SHA-256 hex so they have a fixed size whatever the message key was.
CREATE TABLE IF NOT EXISTS wayang_eip_seen (
    k        CHAR(64) PRIMARY KEY,
    seen_ms  BIGINT   NOT NULL
);
CREATE INDEX IF NOT EXISTS wayang_eip_seen_time ON wayang_eip_seen (seen_ms);

-- Plain settings (${config:NAME}). scope '' is the tenant, otherwise a project id. Compare-and-set on version, done in SQL.
CREATE TABLE IF NOT EXISTS wayang_config (
    tenant_id   VARCHAR(128) NOT NULL,
    scope       VARCHAR(128) NOT NULL,
    k           VARCHAR(100) NOT NULL,
    v           TEXT         NOT NULL,
    version     INT          NOT NULL,
    updated_ms  BIGINT       NOT NULL,
    updated_by  VARCHAR(128) NOT NULL,
    PRIMARY KEY (tenant_id, scope, k)
);

-- What an agent remembers of one conversation (options.memory): one row per exchange, trimmed to the agent's turn limit on append.
CREATE TABLE IF NOT EXISTS wayang_agent_memory (
    seq            BIGSERIAL PRIMARY KEY,
    ns             VARCHAR(512) NOT NULL,
    k              VARCHAR(256) NOT NULL,
    at_ms          BIGINT       NOT NULL,
    user_msg       TEXT         NOT NULL,
    assistant_msg  TEXT         NOT NULL
);
CREATE INDEX IF NOT EXISTS wayang_agent_memory_conv ON wayang_agent_memory (ns, k, seq);
CREATE INDEX IF NOT EXISTS wayang_agent_memory_time ON wayang_agent_memory (at_ms);

-- Rate-limit journal: one row per allowed event so trigger limits survive a restart. The key (a chat id, say) is stored as a SHA-256 hash.
CREATE TABLE IF NOT EXISTS wayang_rate_events (
    scope   VARCHAR(300) NOT NULL,
    key_hash CHAR(64)    NOT NULL,
    at_ms   BIGINT       NOT NULL
);
CREATE INDEX IF NOT EXISTS wayang_rate_events_lookup ON wayang_rate_events (scope, key_hash, at_ms);
CREATE INDEX IF NOT EXISTS wayang_rate_events_time ON wayang_rate_events (at_ms);
