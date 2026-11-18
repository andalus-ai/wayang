# RAG foundation (`wayang-rag`)

Slice 1 of retrieval-augmented agents: turn files into searchable knowledge, with several selectable methods. JDK only, no new dependencies.
**Not yet wired** into connectors, agent tools, REST or Studio (next slices below).

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

## Next slices

1. Extension module with `rag-ingest` / `rag-retrieve` connectors, component descriptors, an agent tool type `rag`, bounded upload endpoints, per-run knowledge-base lifecycle.
2. PDF extractor module, real embedder adapter, external vector stores, persistence, Studio knowledge UI.
