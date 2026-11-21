# RAG foundation (`wayang-rag`)

Slice 1 of retrieval-augmented agents: turn files into searchable knowledge, with several selectable methods. JDK only, no new dependencies.
Slice 2 (`wayang-knowledge`) exposes it as connectors, below. A release serves a file upload API (see "From outside the release"); Studio ingest UI is not built, by design.

## Formats (text extraction)

| Format | Notes |
|---|---|
| txt, md, log, json, yaml, xml, rst | decoded with BOM/UTF-16 detection |
| html | script/style/comments dropped; headings become `#`, list items `- ` |
| csv | RFC 4180, delimiter sniffed (`, ; tab |`); each row becomes `header=value; ...` |
| docx | headings (Heading N/Title), paragraphs, tables as `a \| b` rows |
| xlsx | every sheet, shared/inline strings; rows as `header=value; ...` |
| pptx | `## Slide N` in slide order; speaker notes are not read |
| pdf | **optional module `wayang-rag-pdf`** (Apache PDFBox 3.0.3): text layer only, `## Page N` per page, at most 500 pages; a page without a text layer (a scan) is skipped, or read with OCR when the operator turned it on (see **OCR for scanned PDFs and pictures**); a PDF that is only scans and has no OCR, and password-protected files, are rejected with a clear message. Add the module as a dependency and `rag-ingest` picks it up through ServiceLoader. Without it a PDF returns "PDF needs the PDF extractor, which is not enabled in this build". Generated releases add it automatically when `rag-ingest` is used (this brings PDFBox into the release); a release that only searches does not get it |
| png, jpg, jpeg, gif, tif, tiff | needs OCR turned on (see **OCR for scanned PDFs and pictures**, same settings): the picture is checked (header first, at most 16 megapixels), a transparent one is flattened onto white, and a PNG made here is read by tesseract; the text comes back as plain text. Without OCR the file is refused with the way to enable it. Only the first frame of an animation is read |
| legacy doc/xls/ppt | **not supported**: save as docx/xlsx/pptx |

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

- `HashingEmbedder` is dependency-free and **lexical, not semantic** (feature hashing of words and word pairs). For real semantic search use the `openai`-compatible embedder in `wayang-knowledge`.
- The knowledge base is bounded (default 1000 documents / 20000 chunks). It is held in memory and, **when file storage is configured, its sources are also saved to disk and the base is rebuilt after a restart** (see Persistence). Without a storage directory a restart empties it.
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

In Studio's agent form, **Add knowledge search** inserts this tool with the `query` parameter already declared. Publishing checks that a `rag-search`/`rag-ingest` tool names its knowledge base and that `rag-search` declares a `query` parameter.

Ingest first with a `rag-ingest` node (`{knowledge: contracts, chunking: {type: clause}}`). Ingest and search must repeat the same `chunking` and `embedder` settings, otherwise the call fails instead of silently reconfiguring the base; `retrieval` may differ per node.

- The knowledge **name comes from node configuration, never from model input**, so an agent only reaches knowledge its author wired to it.
- Bases live in the process and are **kept per tenant**: the engine passes the run's tenant (never the author or the model), so two tenants using the same name get separate bases (at most 20 per tenant, 200 overall). This holds for workflow nodes and for an agent's tool calls (the agent node passes the run's tenant to its tools; a tool's `config` can never set it). Only an EIP component, which has no tenant, uses the shared namespace, which is correct in a single-tenant release but not isolation in a multi-tenant host. Durable only with a storage directory (see Persistence).
- `embedder` is `none` (default, keyword search only), `{type: hashing, dimensions}` (lexical, see limits) or `{type: openai, model, dimensions, baseUrl, apiKey}`: **semantic** embeddings from any OpenAI-compatible `/embeddings` endpoint (OpenAI, Ollama, vLLM...). `dimensions` must be the model's vector size; a mismatch fails the call instead of corrupting the index. Requests are batched (32), time- and size-limited and pass the SSRF guard (private hosts need `WAYANG_CONNECTORS_ALLOW_PRIVATE=true` or an allow-list). `apiKey` is `${secret:NAME}`. **Document text is sent to that endpoint**, so pick it like any data processor. Asking for `vector`/`hybrid` without an embedder is an error at ingest time.
```yaml
config: {knowledge: contracts, chunking: {type: clause}, embedder: {type: openai, model: text-embedding-3-small, dimensions: 1536, apiKey: "${secret:OPENAI_API_KEY}"}, retrieval: {method: hybrid, mmr: true}}
```
- That an agent runtime passes an agent tool of type `rag-search` to the Wayang connector is the existing tool contract; it was exercised here only at the connector level, not through a live agent run.

## OCR for scanned PDFs and pictures

Off by default. When a PDF page has no text layer, `wayang-rag-pdf` can render that page to an image (200 dpi, lowered for a very large page to stay under 30 million pixels) and read it with a **local `tesseract`**. Nothing is sent over the network, so scanned legal or personal documents stay on the machine.

| Setting (environment of the release or server) | Meaning |
|---|---|
| `WAYANG_OCR` | `tesseract` turns it on; unset or `off` leaves scans skipped. Any other value is an error, so a typo is never silent |
| `WAYANG_OCR_COMMAND` | the executable, default `tesseract` (started directly, no shell) |
| `WAYANG_OCR_LANGS` | languages joined by `+`, default `eng`, for example `eng+deu` (the language data must be installed) |
| `WAYANG_OCR_MAX_PAGES` | pages OCR'd per document, default 30, at most 100; further scanned pages are skipped |

Limits: 60 seconds per page and 5 minutes per document; text-layer pages are never sent to OCR, so a mixed PDF is read page by page. Recognised text can contain mistakes (that is true of every OCR), and the extracted pages look the same as text pages (`## Page N`). A run is one core per page. The generated release image does **not** install tesseract: add it yourself, for example `RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-deu` in the Dockerfile's runtime stage, then set `WAYANG_OCR=tesseract`. A scan with OCR on but no tesseract installed fails with a message that says so. The same engine reads picture files (png, jpg, gif, tiff) as documents.

## Retention (`ttlDays`)

A document can have a lifetime. Set `ttlDays` (whole days, 1-3650) on a document of `rag-ingest`, or on the node as a default for documents without their own; the document's value wins. After that time the document is not searchable and its stored copy is deleted from disk (also if the process was down meanwhile: it is dropped when the base loads). Re-ingesting a document with the same id restarts its lifetime, or clears it when none is given. The output lists `expiring: [{name, expiresAt}]`.

- `WAYANG_KNOWLEDGE_MAX_TTL_DAYS` is an operator ceiling: it caps every `ttlDays` and gives documents without one that lifetime.
- Expired documents are removed whenever the base is used. To clear a base nobody searches, run `rag-forget` with no ids on a schedule: it removes nothing else and reports `expired`.
- An invalid `ttlDays` skips only that document, with a reason.

## Persistence

The stored layout carries a format version (`format` in `definition.json`, currently 1; files without one count as 1). A base written by a newer version is refused with a clear error rather than misread.

With `WAYANG_FILES_DIR` (or `WAYANG_STATE_DIR`) set, `rag-ingest` also saves each document's text and metadata, and the base's settings, under `<dir>/knowledge/<tenant>/<name>/`. After a restart the base is rebuilt the first time a node uses it (chunking runs again; a model embedder's vectors come back from a disk cache, so nothing is embedded or paid for again). Replacing a document or removing it with **`rag-forget`** (`documentIds`) updates the stored copy too. Details:

- **Sources are stored, chunks are rebuilt** (chunking is deterministic and free). With an `openai` embedder the vectors are kept in `vectors.bin` next to the documents, keyed by the SHA-256 of each chunk's text and scoped to the embedder's settings without the key (another model, size or endpoint never reuses them). So a restart makes no embedding calls for stored documents (the first question still embeds the question), and editing a document embeds only its changed chunks. It is only a cache: a missing, damaged, torn or foreign file is ignored and rebuilt (costing a re-embed, never an error), the oldest vectors are dropped past the base's chunk limit when the file is next opened, and no new vectors are appended once the file would pass twice that. Questions are never stored. `hashing` embedders are free and are not cached. The file is numbers derived from your text: as sensitive as the stored documents beside it.
- **Secrets are never stored.** The embedder's `apiKey` is dropped; after a restart the first node that touches the base supplies it from its own configuration (state `embedder: {type: openai, ..., apiKey: ${secret:NAME}}` on that node). Keyless local endpoints need nothing.
- Settings are saved with the first successfully ingested document, so a failed first ingest leaves nothing behind and the same name can be retried with corrected settings. After that, a node stating different `chunking`/`embedder` is still refused.
- `persist: false` on the creating `rag-ingest` keeps a base in memory only (scratch knowledge).
- Bounded: 200 MiB of stored documents per base; beyond that a document is searchable now but reported in `unsaved` (lost on restart). A damaged stored file makes the base fail to load with a clear error instead of silently dropping documents.
- Per tenant on disk, same as in memory. Put the directory on a volume that is backed up; it holds the full text of your documents.

## File storage steps and mandatory inputs

Files are stored once by a **file storage node** and used by id anywhere after: `file-put` (`fileName` + `text` or `contentBase64` -> `fileId`, `size`, `sha256`), `file-get` (`fileId` -> metadata and `contentBase64` up to 8 MiB), `file-list`, `file-delete`. Files are separated by tenant, addressed only by an opaque id (a name never becomes a path), and bounded to 20 MiB each and 1000 per tenant. Storage is **durable** on disk under `WAYANG_FILES_DIR` (or `WAYANG_STATE_DIR/files`); with neither set the steps fail with a clear message rather than writing somewhere temporary. In a container, mount a volume there.

`rag-ingest` takes those files directly: `documents: [{fileId: "..."}]` (the file's own name tells the extractor the format, so pdf/docx/xlsx/csv/pptx just work). Unknown ids, or ids of another tenant, are skipped with "no such file".

**From outside the release (HTTP).** A release that uses file steps also serves a small file API on its own HTTP service, behind the same API key as `/v1`: `POST /v1/files?name=contract.pdf[&ttlDays=30]` with the raw bytes as the body returns the `fileId`; `GET /v1/files` lists; `GET /v1/files/{id}` downloads the bytes as an attachment; `DELETE /v1/files/{id}` removes. It uses the release's own file steps (same storage, limits and retention) for the release's one tenant, and is limited to **8 MiB** per upload or download (larger files can still be stored by a process step, up to 20 MiB). Without file steps in the release, or without a storage directory, it answers with a clear error. Only the embedded runtime serves it today; the Gamelan worker does not. Pass the returned `fileId` to a process as input, then to `rag-ingest`.

**Retention.** `file-put` takes an optional `ttlDays` (1-3650). `WAYANG_FILES_TTL_DAYS` sets the default for files stored without one, and `WAYANG_FILES_MAX_TTL_DAYS` is a ceiling no file may exceed (with a ceiling and no default, "keep forever" is not possible either). An expired file is unreadable for everyone at once (`found: false`; ingest says "no such file") and its bytes are deleted by the next `file-put`/`file-list` of that tenant, or by a `file-purge` step, which is what to put on a scheduled process for idle tenants. `expiresAt` is returned by put, get and list (`null` = never). With neither setting and no `ttlDays`, files are kept until deleted.

**Mandatory inputs are advisory.** A connector declares its inputs (name, type, mandatory) and the catalogue exposes them under the component's `uiHints.inputs` so Studio can show them as expected connections: the node inspector has an **Expected inputs** panel (connected, mapped, or empty with an amber hint that the workflow still runs) and the component catalogue lists each component's inputs. A missing mandatory input (null, blank text, empty list or object) **never stops a run or fails a node**: the node gets nothing for it, answers with an empty result (`ingested: 0`, `count: 0`, `found: false`, `stored: false`...) and the engine adds `warnings: ["mandatory input 'documents' was not provided"]` to the node's output, so the gap is visible and the workflow can branch on it. Real configuration errors (unknown knowledge name, bad settings, storage not configured) still fail as before.

```yaml
put:    {connector: file-put,   inputs: {fileName: fileName, contentBase64: upload}}
learn:  {connector: rag-ingest, knowledge: contracts, inputMapping: {documents: [{fileId: put.fileId}]}}
lookup: {connector: rag-search, knowledge: contracts, inputs: {query: question}}
```

## Worked example: a research assistant over your documents

One process, run for real by `RagProcessTest`: the first node ingests the documents the run was started with, the second answers a question from them.

```yaml
# BPMN service tasks (shown as their node configuration)
learn:   {connector: rag-ingest, knowledge: contracts, chunking: {type: clause, maxChars: 1200}, inputs: {documents: documents}}
lookup:  {connector: rag-search, knowledge: contracts, retrieval: {method: bm25, topK: 3, mmr: true}, inputs: {query: question}}
```

Start it with `documents: [{fileName: "msa.docx", contentBase64: "..."}, {fileName: "faq.md", text: "..."}]` and `question: "termination notice period"`. Afterwards `lookup.context` holds the numbered, source-labelled passages and `lookup.hits` the structured list.

To let an *agent* decide when to look things up, give it the tool instead of a fixed `lookup` node (Studio: **Add knowledge search**), and fill the base with a separate ingest process, for example one started by a webhook trigger whenever a document arrives:

```yaml
tools: [{name: search_contracts, type: rag-search, description: "Search the contract library", config: {knowledge: contracts, parameters: {type: object, properties: {query: {type: string}}, required: [query]}}}]
persona: {role: "Contracts analyst", rules: ["Answer only from the retrieved passages", "Quote the source file for every claim"]}
guardrails: {...}
```

Recipes: legal/policy -> `chunking: clause`, `method: hybrid`, filter by `docName`; research papers and manuals -> `heading` or `parent-child` with `expand: parent`; spreadsheets -> `paragraph` over the row lines with `bm25` for exact codes; FAQs -> `paragraph`, `hybrid` with an embedder.

## Starter project: knowledge assistant

Studio's template gallery (and `POST /templates/knowledge-assistant/instantiate`) creates a ready project, published and with **no trigger** (nothing to switch on): one agent `researcher` and two processes over one knowledge base.

- `ingest`: takes `documents` (`[{fileId}]` from `POST /v1/files`, or `{fileName, text}`) and runs `rag-ingest`.
- `ask`: takes `question`; the agent searches with its one tool `search_documents` and answers only from the passages, citing sources.

Parameters: `knowledge` (base name, default `docs`), `purpose` (`general` = paragraph chunks, `legal` = clause chunks and quote-the-text answers without legal advice, `research` = heading chunks and per-source summaries), `model`, `embeddingModel` + `embeddingDimensions` (an OpenAI-compatible model for meaning-based search; empty keeps keyword search; the key is the secret `OPENAI_API_KEY`), `retrieval` (`bm25`, `vector`, `hybrid`; default hybrid with an embedding model, else bm25), `topK` (1-10), `persona`, `name`. Ingest and the search tool state the same chunking and embedder, so they cannot drift apart. After releasing it: upload with `POST /v1/files`, start `ingest` with the returned `fileId`s, then start `ask` with a `question`. The agent's tool calls run in the release's tenant, the same one `ingest` writes to.

## Next slices

1. A Studio knowledge UI is intentionally not built: ingest belongs in a process inside the release, not in the design-time server.
2. OCR, a Wayang embedder that reuses an Andalus-configured model (the Andalus `RagEmbedder` adapter itself is merged, andalus#116), external vector stores.
1. REST upload endpoints, Studio knowledge UI, and a RAG agent template.
2. A vision-model OCR engine as an alternative to tesseract (it would send page images to a model endpoint, so it must be explicit); a Wayang embedder that reuses an Andalus-configured model (the Andalus `RagEmbedder` adapter itself is merged, andalus#116), external vector stores.
