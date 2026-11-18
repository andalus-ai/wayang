# RAG foundation (`wayang-rag`)

Slice 1 of retrieval-augmented agents: turn files into searchable knowledge, with several selectable methods. JDK only, no new dependencies.
Slice 2 (`wayang-knowledge`) exposes it as connectors, below. REST upload endpoints and Studio are not built yet.

## Formats (text extraction)

| Format | Notes |
|---|---|
| txt, md, log, json, yaml, xml, rst | decoded with BOM/UTF-16 detection |
| html | script/style/comments dropped; headings become `#`, list items `- ` |
| csv | RFC 4180, delimiter sniffed (`, ; tab |`); each row becomes `header=value; ...` |
| docx | headings (Heading N/Title), paragraphs, tables as `a \| b` rows |
| xlsx | every sheet, shared/inline strings; rows as `header=value; ...` |
| pptx | `## Slide N` in slide order; speaker notes are not read |
| **pdf, legacy doc/xls/ppt** | **not supported yet**; a clear error is returned (PDF needs a separate extractor module because it needs a parser library; save legacy files as docx/xlsx/pptx) |

Office files are read from a temp-file `ZipFile` with bounds on entry count and size, path-traversal names rejected, XML parsed with DTDs and external entities off. Limits (`Limits.DEFAULT`): 20 MiB file, 5M characters, 2000 zip entries, 32 MiB per entry, 200k rows. Format is chosen by extension and sniffed from content when it is missing.

## Chunking methods (`chunking.type`)

| Type | Use it for |
|---|---|
| `fixed` (`size`, `overlap`) | uniform, unstructured text |
| `paragraph` (`maxChars`) | prose, FAQs, notes (default) |
| `heading` (`maxChars`) | manuals, wikis, research: each chunk is prefixed with its heading path |
| `clause` (`maxChars`) | legal and policy text: Article/Section/Clause/Chapter/Part/Rule/Pasal/Bab/Bagian/Ayat/Artikel/§ and `1.2.3 Title`; `(a)` sub-clauses keep their parent clause |
| `parent-child` (`childChars`, `parentChars`) | precise match on a small chunk, answer with the wider window |

## Retrieval methods (`retrieval`)

| Setting | Meaning |
|---|---|
| `method: bm25` | keywords and exact identifiers ("clause 14.2"); needs no embedder |
| `method: vector` | meaning-based cosine similarity; needs an embedder |
| `method: hybrid` | BM25 + vector fused with reciprocal-rank fusion (k=60); best general default when an embedder exists |
| `mmr: true` or `{lambda}` | diversify results, drops near-duplicates |
| `expand: parent` | return the parent window of a parent-child chunk |
| `topK` | 1..50 (default 5) |

Plus an exact-match metadata filter (also `docId`, `docName`). Unknown settings are rejected, and `vector`/`hybrid` without an embedder fail with an error rather than silently falling back to keywords.

## Honest limits

- `HashingEmbedder` is dependency-free and **lexical, not semantic** (feature hashing of words and word pairs). It exercises the vector path; for real semantic search plug an `Embedder` backed by a model (Andalus `RagEmbedder` or an endpoint) in a later slice.
- The knowledge base is **in memory, bounded** (default 1000 documents / 20000 chunks) and **not durable**: a restart empties it.
- **Retrieved text is untrusted.** A document can contain instructions; the agent consuming it must treat it as data (use guardrails, keep tools least-privilege).
- Verification: compiled and its tests run on a real JVM locally against stand-in JUnit stubs; real confirmation is the CI `java` job.

## Connectors and agent tools (`wayang-knowledge`)

Optional extension, loaded when the profile has the `KNOWLEDGE` feature (on in `full` and `agentic`). Generated releases add it automatically when a process or an agent tool uses `rag-ingest` or `rag-search`.

**`rag-ingest`** config `{knowledge, chunking, embedder, maxDocuments, maxChunks}`; input `documents` (up to 50) or one document: `fileName` plus `text` or `contentBase64` (at most 8 MiB decoded), optional `id` and `metadata` (short strings). Replaces a document with the same id. Output `ingested`, `chunks`, `documents`, `skipped` (name and reason, never content). No URL fetching.

**`rag-search`** config `{knowledge, retrieval}`; input `query` (at most 2000 characters), optional `filter`. Output `hits`, `count` and `context`: numbered passages labelled with their source and framed as reference data.

```yaml
# a research / legal agent: the author wires the knowledge, the model only supplies the query
tools:
  - {name: search_contracts, type: rag-search, description: "Search the contract library", config: {knowledge: contracts, retrieval: {method: bm25, topK: 5}}}
```

Ingest first with a `rag-ingest` node (`{knowledge: contracts, chunking: {type: clause}}`). Ingest and search must repeat the same `chunking` and `embedder` settings, otherwise the call fails instead of silently reconfiguring the base; `retrieval` may differ per node.

- The knowledge **name comes from node configuration, never from model input**, so an agent only reaches knowledge its author wired to it.
- Bases live in the process, at most 20, and are **shared by every run and tenant in that process**: use distinct names. Not durable.
- `embedder` is `none` (default, keyword search only) or `{type: hashing}` (lexical, see limits). A model-backed embedder is a later slice; asking for `vector`/`hybrid` without one is an error at ingest time.
- That an agent runtime passes an agent tool of type `rag-search` to the Wayang connector is the existing tool contract; it was exercised here only at the connector level, not through a live agent run.

## Next slices

1. REST upload endpoints, Studio knowledge UI, and a RAG agent template.
2. PDF extractor module, real embedder adapter, external vector stores, persistence, per-tenant isolation.
