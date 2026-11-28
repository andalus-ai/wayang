# Knowledge schema registry

**Status:** the provider-neutral process-local registry and additive compatibility checker are implemented in `wayang-rag`. This increment adds a separate tenant-scoped registry contract and PostgreSQL adapter in `wayang-store-jdbc`. The legacy `KnowledgeSchemaRegistry` remains unchanged for compatibility; it is not a tenant authorization boundary.

## Purpose

A `KnowledgeSchema` is an explicit allow-list for entity types and directed relationship predicates. A schema is identified by `schemaId + version`. Once registered, that exact version is immutable.

The registry does not infer ontologies, select an active version, migrate graph data, or silently upgrade a workflow. Callers must select an exact schema version and pass it to the candidate validator.

## Contracts

- `KnowledgeSchemaRegistry`: existing provider-neutral process-local register/find/list-versions SPI.
- `InMemoryKnowledgeSchemaRegistry`: bounded process-local implementation for tests and development; default capacity is 1,000 versions.
- `TenantKnowledgeSchemaRegistry`: tenant-explicit SPI. Every operation requires tenant ID; it has no global lookup or implicit latest operation.
- `JdbcKnowledgeSchemaRegistry`: PostgreSQL implementation of the tenant-scoped SPI, supplied with a caller-managed `DataSource`.
- `KnowledgeSchemaCompatibility`: conservative additive-compatibility check between versions of the same schema.

The JDBC adapter stores canonical schema JSON and a SHA-256 content digest. Its primary key is `(tenant_id, schema_id, schema_version)`. Registration uses an atomic insert-on-conflict operation, then compares the persisted digest. Identical concurrent registrations are idempotent; conflicting content for the same tenant/key is rejected. The same schema ID/version may contain different definitions in different tenants.

## PostgreSQL setup

`JdbcSchema.apply(dataSource)` now also applies `wayang/schema-knowledge-schema-registry.sql`. The migration is idempotent and creates the immutable version table and lookup index. The JDBC module does not bundle a production PostgreSQL driver; deployments supply one.

Integration tests use the existing `WAYANG_TEST_JDBC_URL` convention. When that variable is absent, the tests return without opening a database. Run them against an isolated PostgreSQL database before production use.

## Compatibility policy

A candidate version is considered additive when it retains every previous entity type, relationship predicate, and allowed source/target endpoint type. New types and predicates may be added. Removing or narrowing existing definitions is breaking. The checker does not infer semantic equivalence, validate graph-data migrations, or interpret version strings as SemVer.

A compatible result is not automatic approval to activate a schema. Deployments must still review policy, data migration, extraction behavior and operational impact.

## Security and operational boundaries

- The JDBC adapter requires an explicit tenant on every operation; blank or missing tenant IDs are rejected.
- Tenant identity must come from the authenticated server-side execution context. Never trust a workflow input or user-editable node field as the tenant authority.
- The adapter is a storage boundary, not an authorization system. The caller must authorize the tenant before invoking it; database credentials and row-level security should be configured according to deployment policy.
- The legacy unscoped `KnowledgeSchemaRegistry` must not be used for cross-tenant data.
- No implicit "latest" lookup or active-version pointer exists. Pin exact schema versions in workflow/deployment configuration.
- Schema activation, migration plans, rollback, audit/provenance and tenant-aware connector wiring remain separate control-plane work.
- Do not store secrets or executable expressions in schema definitions.
