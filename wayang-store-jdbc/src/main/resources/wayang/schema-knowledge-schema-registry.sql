-- Tenant-scoped immutable knowledge schema versions.
-- No global namespace or implicit active/latest version is represented here.
CREATE TABLE IF NOT EXISTS wayang_kg_schema_version (
    tenant_id       VARCHAR(200) NOT NULL,
    schema_id       VARCHAR(200) NOT NULL,
    schema_version  VARCHAR(100) NOT NULL,
    schema_json     TEXT NOT NULL,
    content_sha256  CHAR(64) NOT NULL,
    created_at_ms   BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, schema_id, schema_version)
);

CREATE INDEX IF NOT EXISTS wayang_kg_schema_version_lookup
    ON wayang_kg_schema_version (tenant_id, schema_id, schema_version);
