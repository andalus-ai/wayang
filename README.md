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
| `wayang-agent` | Declarative agent specs (YAML/Map), `AgentTemplate` agent factory (${param} -> concrete specs), prompt templates, `AgentInvoker` port | snakeyaml |
| `wayang-runtime` | `NodeRunner`, domain handlers, wait registry, in-memory **reference engine** | the modules above |
| `wayang-connectors` | Real outbound connectors for service tasks behind an SSRF guard | JDK only |
| `wayang-store-jdbc` | Durable `WaitRegistry` and `DefinitionStore` over plain JDBC + schemas | JDK `java.sql` only |
| `wayang-deployment` | Engine-neutral deployment lifecycle ledger, JDBC persistence, trusted target SPI, and audited activation/health orchestration (`docs/deployment/`) | JDK `java.sql`; H2 tests |
| `wayang-api-core` | Transport-neutral API: tenant-scoped definition store, publish-time validation, run/task/message/timer operations, RBAC, event bus, WebSocket session protocol | runtime |
| `wayang-api-rest` | Quarkus shell: REST resources, WebSocket, gRPC service, OIDC/JWT principal | Quarkus |
| `wayang-gamelan-worker` | Plan -> Gamelan WorkflowDefinition; executors; wait parking; deploy facade; durable generated-release runs | Gamelan, Mutiny, CDI |
| `wayang-adapter-gamelan` | Gamelan-backed API run engine | Gamelan, Mutiny, CDI |
| `wayang-adapter-andalus` | AgentInvoker over Andalus ExecutionService; NodeProvider publishing Wayang nodes to the Andalus catalog | Andalus |
| `wayang-mcp` + connector `mcp` | Call MCP tools from workflows; generate an MCP server from Swagger/OpenAPI | - |
| Workspace bundles, configuration, vault | Project bundles, tenant/project configuration, Vault integration and encrypted secrets archive: `docs/WORKSPACE.md` | - |
| Release (design phase to production) | Publish project versions as standalone CLI, HTTP service, or Quarkus service with durable runs: `docs/RELEASE.md` | `wayang-embed`, `wayang-publish` |
| Profiles and extensions | `WAYANG_PROFILE` and `WayangExtension` ServiceLoader extension points: `docs/PROFILES.md` | `wayang-runtime` |
| `wayang-adapter-numu` | Identity/tenancy/authz through Numu AuthCheckService: `docs/IDENTITY.md` | Numu |
| `studio` | Flutter app for process/decision designers, agents, runs, task inbox, projects, secrets | Wayang API |
| `wayang-examples` | Loan approval: DMN + XOR + human review + agent + EIP splitter | - |

Try it without any infrastructure: run `tech.kayys.wayang.examples.LoanApproval`.

## How BPMN runs on a dependency-only scheduler

Gamelan starts a node when all `dependsOn` nodes are COMPLETED and does not evaluate branch
conditions. Wayang therefore compiles every process to a DAG and uses **dead-path elimination**:
every node is scheduled; a node on a path that was not taken completes immediately and marks its
outgoing flows dead (`<node>.flow.<flowId> = false`). Gateways decide which outgoing flows are live.
See `docs/ARCHITECTURE.md`.

## Design rules

- Existing expressions are bounded and deterministic: no reflection, I/O, regex, randomness; limits on source length, nesting and evaluation steps.
- CEL is additive and opt-in; it does not change existing FEEL expression semantics.
- Invalid definitions fail at deploy time.
- Cycles are rejected; model repetition with an EIP splitter or retry policy.
- Delivery guarantees are explicit; EFFECTIVELY_ONCE requires an idempotent step.

## Status and honest limitations

Read `docs/ARCHITECTURE.md` section "Verification status" before relying on this. Pure modules are unit-tested; Gamelan integration has been checked against Gamelan's WorkflowRun scheduler. A live Gamelan or Andalus deployment and the full Maven build have not been executed in this repository workflow.

## Build

```
mvn -q verify
```
(Requires JDK 21+. Gamelan 0.1.1 and Andalus 0.0.1 artifacts must be resolvable.)

## Shared contracts

The shared, versioned contracts for Wayang and the standalone Wayang Console are being established in [Wayang Core](https://github.com/andalus-ai/wayang-core). Workflow semantics, compilation, runtime execution and local plugin lifecycle remain in this repository; Console fleet management and central plugin governance remain in [wayang-console](https://github.com/andalus-ai/wayang-console). See [the shared Core boundary](docs/SHARED-CORE.md). Existing contracts remain authoritative until the documented compatibility-preserving migration is completed.
