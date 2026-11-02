# Wayang

Low-code **agentic factory, BPMN, EIP and DMN** platform, built on top of
**Andalus** (agent runtime) and **Gamelan** (durable workflow engine).

Wayang owns *definitions* and the user-facing model; it never implements a second
persistent workflow engine.

| Concern | Owned by |
|---|---|
| Process / decision / integration / agent *definitions*, validation, compilation | **Wayang** (this repo) |
| Durable workflow execution (scheduling, retries, persistence, run history) | **Gamelan** |
| Agent execution (models, tools, memory, guardrails) | **Andalus** |

## Modules

| Module | What it is | External deps |
|---|---|---|
| `wayang-expression` | Bounded FEEL-style expression language shared by everything below | none |
| `wayang-dmn` | Decision tables (UNIQUE/FIRST/ANY/COLLECT/RULE ORDER, aggregators), decision graphs with inferred dependencies, Map loader | none |
| `wayang-business-process` | BPMN subset (events, XOR/AND/OR gateways, service/rule/agent/EIP/user tasks, timers, message waits): model, validation, compiler to an acyclic plan, dead-path-elimination runtime, BPMN 2.0 XML import/export (supported subset; unsupported elements are rejected, not ignored) | none |
| `wayang-integration` | EIP + mapper: declarative mapper, JSON/CSV/XML conversion, filter, splitter, router/choice, recipient list, multicast/scatter-gather, dead-letter guard, call (enrich/wire tap), aggregator, resequencer, idempotent receiver, validator, claim check, loop, routing slip, switch/normalizer, load balancer, retry, circuit breaker, throttler, delayer, batch, lookup, durable aggregator, delivery guarantees, parallel multicast, Studio step builder (see `docs/INTEGRATION.md` and `docs/EIP.md`) | none |
| `wayang-agent` | Declarative agent specs (YAML/Map), `AgentTemplate` agent factory (`${param}` -> concrete specs), prompt templates, `AgentInvoker` port | snakeyaml |
| `wayang-runtime` | `NodeRunner`, domain handlers, wait registry, in-memory **reference engine** | the modules above |
| `wayang-connectors` | Real outbound connectors for service tasks: `http` (methods, headers, query, JSON body, timeouts, size cap, no redirects, retryable vs permanent failures by status) behind an SSRF guard. **Tested against a real local HTTP server and end to end through a process with a `${secret:}` header** | JDK only |
| `wayang-store-jdbc` | Durable `WaitRegistry` and `DefinitionStore` over plain JDBC + schemas. **Contract-tested on PostgreSQL 16** | JDK `java.sql` only |
| `wayang-api-core` | Transport-neutral API: tenant-scoped definition store (draft CAS + immutable versions), publish-time validation, run/task/message/timer operations, RBAC, event bus, WebSocket session protocol, in-memory reference engine. **Tested (15 tests)** | runtime |
| `wayang-api-rest` | Quarkus shell: REST resources, WebSocket `/ws/events`, gRPC service + `.proto`, OIDC/JWT principal, timer ticker. **Not compiled against real Quarkus/gRPC; see docs/API.md** | Quarkus |
| `wayang-gamelan-worker` | Plan -> Gamelan `WorkflowDefinition`; six `@Executor`s; wait parking; deploy facade; **`GamelanReleaseEngine`** (durable runs for generated releases) | Gamelan, Mutiny, CDI |
| `wayang-adapter-gamelan` | **`GamelanRunEngine`** (API run engine on Gamelan, tenant-qualified definitions) | Gamelan, Mutiny, CDI |
| `wayang-adapter-andalus` | `AgentInvoker` over Andalus `ExecutionService`; `NodeProvider` publishing Wayang nodes to the Andalus catalog | Andalus |
| `wayang-mcp` + connector `mcp` | See `docs/MCP.md`: call MCP tools from workflows (input/output mappers); generate an MCP server from Swagger/OpenAPI | - |
| `wayang-connectors` (Telegram, Slack, WhatsApp, Gmail, SMTP, IMAP, Discord, Teams, GitHub, Notion, Google Calendar and Sheets, Twilio, Resend, Airtable, Todoist, Linear, Trello, SOAP/WSDL, RSS poller, GitHub webhook) + triggers | See `docs/TRIGGERS.md`: webhook and polling triggers, Telegram send/receive; Gmail via OAuth2 in `docs/EMAIL.md`; any other mail server and Discord in `docs/MAIL.md` | - |
| Workspace bundles, configuration, vault | Export/import a project as a bundle (dry run first, no secret values), `${config:NAME}` settings per tenant/project, master keys from a file or HashiCorp Vault, `${secret:vault.NAME}`, encrypted secrets archive: `docs/WORKSPACE.md` | - |
| Release (design phase to production) | Publish a project's *published* versions as a generated Maven project that runs without the server: standalone CLI, HTTP service, or a Quarkus service with durable runs on Gamelan, only the dependencies the workflows use, Dockerfile and Kubernetes manifest: `docs/RELEASE.md` | `wayang-embed`, `wayang-publish` |
| Profiles and extensions | `WAYANG_PROFILE` (full, automation, integration, agentic, custom) limits what a runtime serves; `WayangExtension` (ServiceLoader) adds connectors, pollers, webhook providers and custom executor handlers: `docs/PROFILES.md` | `wayang-runtime` |
| `wayang-adapter-numu` | Identity/tenancy/authz through Numu `AuthCheckService`: PAT/JWT resolution, tenant scoping, permission mapping (`docs/IDENTITY.md`) | Numu |
| `studio` | One Flutter app (Riverpod): process/decision designers, agents & pipelines editor, runs, task inbox, projects, secrets. Consolidates the former Studio and Console | Wayang API |
| `wayang-examples` | Loan approval: DMN + XOR + human review + agent + EIP splitter | - |

Try it without any infrastructure: run `tech.kayys.wayang.examples.LoanApproval`.

## How BPMN runs on a dependency-only scheduler

Gamelan starts a node when all `dependsOn` nodes are COMPLETED and does not evaluate branch
conditions. Wayang therefore compiles every process to a DAG and uses **dead-path elimination**:
every node is scheduled; a node on a path that was not taken completes immediately and marks its
outgoing flows dead (`<node>.flow.<flowId> = false`). Gateways decide which outgoing flows are live.
See `docs/ARCHITECTURE.md`.

## Design rules

- Expressions are bounded and deterministic: no reflection, I/O, regex, randomness; limits on source
  length, nesting and evaluation steps. Missing data yields `null` (FEEL semantics) unless strict.
- Invalid definitions fail at **deploy time** (every expression, rule and pipeline is parsed up front).
- Cycles are rejected, with guidance (model repetition with an EIP splitter or retry policy).
- Delivery guarantees are explicit; `EFFECTIVELY_ONCE` requires an idempotent step.

## Status and honest limitations

Read `docs/ARCHITECTURE.md` section "Verification status" before relying on this. What happened to the modules imported from `Backend/wayang` is in `docs/CONSOLIDATION.md`; unported ones live in `attic/` and are not part of the build.
In short: the pure modules are unit-tested; the Gamelan integration was checked against Gamelan's real
`WorkflowRun` scheduler; **nothing has been run against a live Gamelan or Andalus deployment**, and
the Maven build has not been executed.

## Build

```
mvn -q verify
```
(Requires JDK 21+. Gamelan 0.1.1 and Andalus 0.0.1 artifacts must be resolvable.)
