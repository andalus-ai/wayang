# Knowledge graph and hybrid retrieval

**Status:** graph foundation, extraction SPIs, schema validation, conservative alias resolution, source-aware ingestion, evidence lifecycle and graph-aware retrieval contracts are implemented. Atomic graph batch writes are part of the GraphStore contract and are implemented by the in-memory reference adapter. A durable database adapter remains planned; no database has yet been selected or qualified.

## Goals

Wayang's graph capability complements existing document RAG; it does not replace it. Existing BM25, vector and hybrid retrieval continue to work when graph support is absent. The public graph contract is vendor-neutral and exposes no Cypher, Gremlin, SQL or provider-specific types.

## Canonical data model

- KnowledgeEntity: tenant/collection-scoped canonical entity, aliases, descriptive properties and evidence.
- KnowledgeRelationship: directed typed edge with endpoint IDs, properties, confidence and evidence.
- KnowledgeEvidence: source/version/chunk and optional character offsets supporting a fact.
- GraphQuery: bounded traversal request with tenant, collection, direction, predicates and hard limits.
- GraphStore: provider-neutral persistence port.
- InMemoryGraphStore: bounded process-local reference adapter for tests and development.

All graph reads and writes enforce tenant and collection scope. Graph traversal is not document authorization: a production evidence lookup must independently enforce source ACLs, source version and chunk identity before returning passages.

## Extraction and resolution

Extraction APIs return chunk-local entity and relationship candidates. Candidate validation checks a versioned allow-list schema, offsets and endpoint references. Entity resolution is conservative: exact in-scope matches only; ambiguous mentions are not silently merged. These components do not write to storage.

## Ingestion and evidence lifecycle

KnowledgeGraphIngestionService applies validated candidates and explicit resolution decisions. Canonical IDs are deterministic within tenant and collection. Each extracted fact carries source ID, exact source version, chunk ID, extractor, offsets and confidence.

Evidence upserts are idempotent. Retracting one source version removes only that version's evidence in that tenant/collection. A relationship is removed only when its final evidence is removed. Canonical entities are retained even when no evidence remains; curated-vs-extracted ownership and entity garbage collection require a separate contract.

For source replacement, ingest and verify the new source version before retracting the old one. This ordering avoids a deliberate evidence gap, but the two operations are not one cross-version transaction.

## Atomic graph mutations

GraphStore.upsertGraph(entities, relationships) is the atomic ingestion boundary for one chunk mutation:

1. Validate every entity identity and merge rule.
2. Allow relationship endpoints to refer to existing entities or entities in the same batch.
3. Validate relationship identity collisions and capacity constraints.
4. Commit all entity and relationship changes together, or commit none.
5. Merge evidence idempotently.

Persistent adapters must implement this operation with a single database transaction and must not expose partially applied entity/relationship batches. A failed transaction must roll back all rows. Retraction must likewise be transactional within its tenant/collection/source-version scope.

The in-memory adapter stages mutations in private copies and updates its maps only after validation. This provides atomicity within one process, not durability or multi-process coordination.

## Traversal limits

The current query abstraction caps seeds at 100, depth at 3, nodes at 1,000 and edges at 2,000. Implementations must honor these limits and set the result truncation flag when a cap prevents complete traversal. Traversal ordering should be deterministic for equivalent stored data.

## Durable adapter qualification

No database-specific graph provider is included in this phase. The existing wayang-store-jdbc module provides plain-JDBC infrastructure, but selecting it for graph storage requires an explicit schema, transaction and migration design plus integration tests against a supported database. A future provider must qualify:

- tenant/collection isolation in every query and mutation;
- atomic upsertGraph behavior, including injected failures between entity and relationship writes;
- idempotent evidence merge and exact source-version retraction;
- identity collision rejection and endpoint integrity;
- bounded traversal and deterministic truncation;
- process restart durability and concurrent writer behavior;
- schema migration, backup and recovery procedures.

Until a provider passes those checks, InMemoryGraphStore is for tests and development only.
