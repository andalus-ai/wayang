# Knowledge schema registry

**Status:** the provider-neutral registry contract, bounded in-memory implementation and additive compatibility checker are implemented in `wayang-rag`. Persistent providers, tenant-scoped registry authorization, schema publication workflows and graph-ingestion integration are not implemented.

## Purpose

A `KnowledgeSchema` is an explicit allow-list for entity types and directed relationship predicates. A schema is identified by the pair `schemaId + version`. Once registered, that exact version is immutable.

The registry deliberately does not infer ontologies, select an active version, migrate graph data, or silently upgrade an ingestion workflow. Callers must select an exact schema version and pass it to the existing `KnowledgeCandidateValidator`.

## Contracts

- `KnowledgeSchemaRegistry`: provider-neutral register/find/list-versions SPI.
- `InMemoryKnowledgeSchemaRegistry`: bounded process-local implementation for tests and development; default capacity is 1,000 versions.
- `KnowledgeSchemaCompatibility`: conservative additive-compatibility check between two versions of the same schema.
- `KnowledgeSchemaCompatibilityResult`: explicit compatibility outcome and breaking-change diagnostics.

Registering the same schema version with identical content is idempotent. Registering different content under an existing schema ID and version is rejected. Capacity exhaustion is explicit; there is no silent eviction.

## Compatibility policy

A candidate version is considered additive when it:

1. retains every entity type from the previous version;
2. retains every relationship predicate from the previous version; and
3. retains every previously allowed source and target entity type for each retained predicate.

New entity types, predicates and endpoint types may be added. Removing or narrowing existing definitions is reported as breaking. The checker does not infer semantic equivalence, validate graph-data migrations, or interpret version strings as SemVer.

A compatible result is not an automatic approval to activate the schema. Deployments must still review policy, data migration, extraction behavior and operational impact.

## Intended usage

```java
KnowledgeSchemaRegistry registry = new InMemoryKnowledgeSchemaRegistry();
registry.register(schema);

KnowledgeSchema selected = registry.find("acme.graph", "1.0")
        .orElseThrow(() -> new IllegalStateException("schema version not found"));

ValidatedKnowledgeCandidates candidates = validator.validate(
        extractionInput, entities, relationships, selected);
```

The example is illustrative. The registry is not yet wired into `graph-ingest`; the current connector continues to require an explicitly injected `KnowledgeSchema`.

## Security and operational boundaries

- The in-memory implementation is not durable, distributed, tenant-isolated or an authorization boundary.
- A future persistent adapter must preserve immutable version semantics under concurrent registration.
- Tenant and collection authorization remains the responsibility of the caller and graph storage boundary; schema validation does not grant access to graph data.
- Do not store secrets or executable expressions in schema properties.
- No implicit "latest" lookup is provided. Pin exact schema versions in workflow/deployment configuration.
- Schema activation, migration plans, rollback and provenance should be explicit future control-plane workflows.
