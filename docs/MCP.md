# MCP: calling tools, and turning an OpenAPI spec into an MCP server

Three pieces, all framework-free Java (`wayang-connectors`, `wayang-mcp`) with thin REST wiring on top.

| Piece | What it does |
|---|---|
| connector `mcp` | A workflow node that calls a tool on **any** MCP server (Streamable HTTP). Input and output mappers. |
| `OpenApiToMcp` | Swagger 2.0 / OpenAPI 3.x (JSON or YAML, pasted or by URL) to MCP tool definitions. |
| `OpenApiExecutor` + `McpServerCore` | The generated adapter: a working MCP server whose tools call the REST API. |

Andalus ships its own MCP client and an OpenAPI tool generator (`andalus-tool-mcp`, `andalus-tool-parser`); both are bound
to Quarkus, Hibernate and a database. Wayang's versions stand alone, and the MCP endpoint they expose is standard, so an
Andalus agent (or Claude Desktop, or anything else that speaks MCP over HTTP) can use it.

## The `mcp` connector (workflow node)

A `SERVICE_TASK` with `connector: "mcp"`:

```json
{ "id": "lookup", "type": "SERVICE_TASK", "config": {
    "connector": "mcp",
    "url": "https://wayang.example.com/api/v1/mcp/servers/pets/mcp",
    "headers": { "Authorization": "Bearer ${secret:WAYANG_PAT}" },
    "tool": "getPet",
    "inputs":    { "petId": "order.petId" },                      // INPUT mapper: argument -> expression over run variables
    "outputMap": { "petName": "result.name", "city": "result.owner.address.city" }   // OUTPUT mapper: variable <- path
} }
```

* **Input mapper**: `inputs` are the tool arguments, each an expression evaluated against the run's variables (the same
  mechanism every service node uses).
* **Output mapper**: `outputMap` reads paths (`a.b[0].c`) from `{result, text, isError, content}`. Those four are always
  returned too. `result` is the tool's `structuredContent`, else its single text block parsed as JSON when it is JSON, else
  the text. A path that does not exist gives null.
* `operation: "listTools"` returns `tools` (name, description, inputSchema, ...): use it to build a picker.
* A tool that reports `isError` fails the node (not retryable) unless `failOnToolError: false`.
* Each call is `initialize`, `notifications/initialized`, the call, and a best-effort session `DELETE`. Plain JSON and short
  `text/event-stream` answers are understood; long-lived streams, server-initiated requests and sampling are not.
* Credentials go in `headers` as `${secret:NAME}`; the URL guard (no private/loopback targets unless allowed), size and time
  limits, and "never follow redirects" apply as for the `http` connector.

## OpenAPI to MCP

`OpenApiToMcp.generate(specText, options)` returns tools, warnings and the declared security schemes.

* **Input**: path, query and header parameters become top-level arguments, the request body one argument `body`
  (JSON, or `application/x-www-form-urlencoded`). `additionalProperties` is false, so unknown arguments are refused.
* **Schemas**: local `$ref`s are inlined (recursive ones are cut off), `allOf` object parts merged, `nullable` becomes a
  type union, read-only properties are dropped from requests. The first 2xx JSON object response becomes `outputSchema`.
* **Naming**: `operationId`, else `method_path`; sanitised, de-duplicated, prefixable.
* **Hints**: `readOnlyHint` for GET/HEAD, `destructiveHint` for DELETE, `idempotentHint` for GET/HEAD/PUT/DELETE.
* **Safe by default**: only GET/HEAD are exposed; deprecated operations skipped; at most 100 tools; filters by method, tag
  and path regex. Not supported (listed in `warnings`, never silently dropped): multipart/file uploads, non-JSON bodies,
  cookie parameters, external `$ref`s, XML.
* **Specs are untrusted text that a model will read.** Descriptions are cut to 1000 characters and stripped of control
  characters, but a hostile spec can still try to steer an agent. Import specs you trust. YAML alias bombs and specs over
  5 MB are refused.

### Execution

`OpenApiExecutor` validates the arguments (type, required, enum, bounds, unknown names), then builds the request:

* the **host is fixed** by the server's base URL; path values are percent-encoded per segment (`/`, `?`, `#`, `@` cannot
  escape), `.`/`..` and empty values are refused; query arrays repeat the key;
* credentials come from the server's fixed `headers`/`query` (secrets resolved at call time), never from the caller;
* API errors and bad arguments come back as MCP tool errors (`isError: true`) so the model can correct itself.

### REST (tenant-scoped, `/api/v1/mcp`)

| Call | Needs | Does |
|---|---|---|
| `POST /servers/preview` | DEFINITION_WRITE | Generate from `spec` (text) or `specUrl`, nothing stored: tools, warnings, security. |
| `POST /servers` | DEFINITION_WRITE | Same, then store as server `id` and serve it. |
| `GET /servers`, `GET /servers/{id}` | DEFINITION_READ | List / tools of one. |
| `POST /servers/{id}/refresh` | DEFINITION_WRITE | Re-fetch the spec URL and regenerate. |
| `DELETE /servers/{id}` | DEFINITION_WRITE | Remove. |
| `POST /servers/{id}/mcp` | tools/list: DEFINITION_READ; tools/call: RUN_START | **The MCP endpoint** (JSON-RPC). |

Create body: `{ "id", "spec" | "specUrl", "baseUrl"?, "headers"?: {"Authorization": "Bearer ${secret:PETS_TOKEN}"},
"query"?, "methods"?, "includeTags"?, "excludeTags"?, "includePaths"?, "excludePaths"?, "maxTools"?, "namePrefix"? }`.
Credential-looking headers/query keys (authorization, key, token, secret, password, cookie) must be `${secret:...}`
references; literals are refused. Point any MCP client at `https://<host>/api/v1/mcp/servers/<id>/mcp` with a Wayang bearer
token; it is stateless and answers with plain JSON (no SSE, no sessions).

In the Studio: **MCP servers -> Import OpenAPI** (URL or pasted spec, method/tag/path filters, credential headers) shows a
preview of the generated tools and warnings before **Create**.

## Verification status

Tested (JUnit shim, JDK HTTP servers): generator over OAS 3.0/3.1 and Swagger 2.0 (JSON and YAML), `$ref`/recursion/`allOf`,
filters, naming, hostile specs; executor (encoding, injection attempts, validation, errors); JSON-RPC server (negotiation,
batches, errors, notifications); the `mcp` connector (JSON and SSE replies, sessions, output mapping); an end-to-end run
spec -> MCP server -> `mcp` connector -> fake API. **Not** tested against any real MCP server or client implementation,
a real public OpenAPI document, or Andalus. The REST resources compile only against stubs.

## Wayang processes as MCP tools

`POST /api/v1/mcp/processes` is an MCP server (same transport as generated servers) whose tools are the tenant's
**published** processes that opted in. A process opts in in its document:

```json
"studio": { "mcp": { "expose": true, "description": "Approve a loan", "waitSeconds": 20,
                     "inputSchema": {"type":"object","required":["amount"],"properties":{"amount":{"type":"number"}},"additionalProperties":false} } }
```

- Tool `run_<processId>` starts the process; the arguments become the input variables and are checked against
  `inputSchema` first (invalid calls start no run). It waits up to `waitSeconds` (0..60, default 20) for the run to
  finish. A run parked on a human task or timer returns `runId` and status `WAITING`; `get_run {runId}` reads it later.
  FAILED or CANCELLED runs are tool errors with the reason.
- Permissions: listing needs DEFINITION_READ, `run_*` needs RUN_START, and variables/error are returned only to
  callers who also have RUN_READ (otherwise just `runId` and `status`). Runs and `get_run` are tenant-scoped.
- Drafts are never exposed; republish to change what is exposed. `studio.mcp` is validated strictly on save.
- Verified: 9 service tests plus mutation checks (opt-in, schema check, RUN_READ gating, failure mapping, key
  validation). The REST route compiles against stubs; never run in Quarkus or with a real MCP client. The Studio's process inspector
  has an "MCP tool" section (toggle, description, wait, input schema); it takes effect on the next publish, and Duplicate
  does not copy it, so a copy is never exposed by accident. The Dart side is parse-checked only, with one new Dart test
  (studio.mcp round trip) that has not been run.
