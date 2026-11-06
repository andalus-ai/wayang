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
| `wayang-cel` | Additive, engine-neutral CEL contracts and deterministic typed test primitives; does not replace FEEL (see `docs/CEL.md`) | JDK only |
| `wayang-dmn` | Decision tables (UNIQUE/FIRST/ANY/COLLECT/RULE ORDER, aggregators), decision graphs with inferred dependencies, Map loader | none |
| `wayang-business-process` | BPMN subset (events, XOR/AND/OR gateways, service/rule/agent/EIP/user tasks, timers, message waits): model, validation, compiler to an acyclic plan, dead-path-elimination runtime, BPMN 2.0 XML import/export (supported subset; unsupported elements are rejected, not ignored) | none |
| `wayang-integration` | EIP + mapper: declarative mapper, JSON/CSV/XML conversion, filter, splitter, router/choice, recipient list, multicast/scatter-gather, dead-letter guard, call (enrich/wire tap), aggregator, resequencer, idempotent receiver, validator, claim check, loop, routing slip, switch/normalizer, load balancer, retry, circuit breaker, throttler, delayer, batch, lookup, durable aggregator, delivery guarantees, parallel multicast, Studio step builder (see `docs/INTEGRATION.md` and `docs/EIP.md`) | none |
| `wayang-agent` | Declarative agent specs (YAML/Map), `AgentTemplate` agent factory (`${param}` -> concrete specs), prompt templates, `AgentInvoker` port | snakeyaml |
| `wayang-runtime` | `NodeRunner`, domain handlers, wait registry, in-memory **reference engine** | the modules above |
| `wayang-connectors` | Real outbound connectors for service tasks: `http` behind an SSRF guard | JDK only |
| `wayang-store-jdbc` | Durable `WaitRegistry` and `DefinitionStore` over plain JDBC + schemas | JDK `java.sql` only |
| `wayang-api-core` | Transport-neutral API: tenant-scoped definitions, publish-time validation, run/task/message/timer operations, RBAC and event bus | runtime |
| `wayang-api-rest` | Quarkus shell: REST resources, WebSocket, gRPC service, OIDC/JWT principal and timer ticker | Quarkus |
| `wayang-gamelan-worker` | Plan -> Gamelan workflow definition, executors, wait parking and durable release engine | Gamelan, Mutiny, CDI |
| `wayang-adapter-gamelan` | Gamelan run engine for the design-time server | Gamelan, Mutiny, CDI |
| `wayang-adapter-andalus` | AgentInvoker and node catalog adapter | Andalus |
| `wayang-mcp` + connector `mcp` | MCP client/server integration | - |
| `studio` | One Flutter app (Riverpod): process/decision designers, agents & pipelines editor, runs, task inbox, projects and secrets | Wayang API |
| `wayang-examples` | Loan approval: DMN + XOR + human review + agent + EIP splitter | - |

For the full connector, trigger, workspace, release and profile inventory, see the corresponding docs.

## How BPMN runs on a dependency-only scheduler

Gamelan starts a node when all `dependsOn` nodes are COMPLETED and does not evaluate branch
conditions. Wayang therefore compiles every process to a DAG and uses **dead-path elimination**:
every node is scheduled; a node on a path that was not taken completes immediately and marks its
outgoing flows dead (`<node>.flow.<flowId> = false`). Gateways decide which outgoing flows are live.
See `docs/ARCHITECTURE.md`.

## Design rules

- Existing FEEL expressions remain bounded and deterministic: no reflection, I/O, regex, randomness; limits on source length, nesting and evaluation steps. Missing data yields `null` (FEEL semantics) unless strict.
- CEL is opt-in and additive; its own semantics, artifacts and provider compatibility are isolated in `wayang-cel`.
- Invalid definitions fail at **deploy time** (every expression, rule and pipeline is parsed up front).
- Cycles are rejected, with guidance (model repetition with an EIP splitter or retry policy).
- Delivery guarantees are explicit; `EFFECTIVELY_ONCE` requires an idempotent step.

## Status and honest limitations

Read `docs/ARCHITECTURE.md` section "Verification status" before relying on it. See `docs/CONSOLIDATION.md` for imported-module decisions.
The repository's current verification report documents which modules and integrations have been tested and which remain unverified. Do not infer that a Maven build or live Gamelan/Andalus deployment has been verified.

## Build

```
mvn -q verify
```
(Requires JDK 21+. Gamelan and Andalus artifacts must be resolvable.)
