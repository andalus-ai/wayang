# Knowledge graph and hybrid retrieval

**Status: graph foundation, extraction, schema validation, conservative resolution, in-memory evidence lifecycle and graph-aware retrieval contracts are implemented.** Durable graph persistence, workflow nodes and production query-entity resolution remain planned.

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
