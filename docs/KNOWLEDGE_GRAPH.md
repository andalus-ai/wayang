# Knowledge graph and hybrid retrieval

**Status: graph foundation, extraction SPIs, schema validation, conservative alias resolution an in-memory evidence lifecycle and graph-aware retrieval contracts are implemented.** Durable persistence, workflow nodes and graph-aware retrieval remain planned. This document distinguishes implemented contracts from the intended architecture. A type or SPI is not evidence that a complete graph pipeline exists.

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

This branch adds unit tests for idempotent evidence merge, multi-source provenance, exact source-version retraction, relationship cleanup after the last evidence, ambiguous/missing resolution behavior, and tenant/collection/source/version scoping. Together with the existing graph-store, extraction and schema tests, these tests are authored but have not been executed in this change session. CI must run the module tests and the repository's standard Maven verification before merge.


## Graph-aware retrieval

GraphAwareRetriever is an opt-in composition layer over existing BM25/vector/hybrid document results. It does not change KnowledgeBase.search or RetrievalConfig. Applications invoke graph expansion only when query understanding supplies tenant- and collection-scoped seed entity IDs.

The retrieval sequence is:
1. Run existing document retrieval and retain its Hit list.
2. Resolve query mentions to canonical entity IDs in the application query-understanding layer.
3. Submit bounded traversal limits and optional predicate filters to GraphStore.
4. Collect and deduplicate evidence references from traversed entities and relationships, subject to a maximum evidence count.
5. Resolve references to source passages through GraphEvidenceLookup.
6. Fuse document and graph-derived passage rankings with reciprocal-rank fusion. BM25/vector scores are not compared directly with graph scores.

GraphEvidenceLookup is a security boundary. Each adapter MUST enforce tenant and collection scope, document ACLs, source version and chunk identity before returning a passage. Graph traversal is not authorization. The retriever returns source-backed Hit values, never graph properties or generated graph summaries as standalone factual passages.

Traversal depth, node count, edge count, evidence count and top-K are bounded. Truncation is surfaced in the result. Empty seed sets return the original document hits without invoking graph or evidence lookup.

The first implementation does not provide automatic query entity linking, a reranking model, graph database adapters, or changes to existing RAG defaults. Applications opt in and supply the query entity IDs and an ACL-aware evidence lookup adapter.

## Evidence lookup contract

GraphEvidenceReference carries tenant, collection, entity/relationship identity and exact KnowledgeEvidence provenance. A production lookup should verify that the referenced source version is still current and accessible and that the caller may read the document. If a source was deleted, superseded or access was revoked, the lookup must omit it. Implementations must never widen scope on a failed lookup.

## Limits and compatibility

Existing KnowledgeBase.search and RetrievalConfig APIs are unchanged. The existing GraphQuery traversal bounds remain enforced. Persistent storage and remote evidence lookup implementations can be added behind the existing ports without introducing graph-vendor dependencies into wayang-rag.
