# Editions and deployment modes

Wayang is one codebase with two ways to run it. This document is the reference for both and for what is built today versus planned. Status words are literal:
**implemented** (code exists and is tested at the level stated elsewhere in `docs/`), **partial**, **proposed** (a plan, not code).

## The two modes

| | **Community / development: all-in-one** | **Pro / Enterprise: separated** |
|---|---|---|
| Idea | One runtime is Studio, design server and execution together. Nothing else to install | Design and production are different things, run and scaled separately |
| Design (compose, validate, test, version, publish) | Studio + design server (`wayang-api-rest` over `wayang-api-core`) | **Studio for production**: a design-time service; it never serves production traffic |
| Execution | In the same process: the reference engine, or Gamelan when configured | **Generated runtimes**, each a standalone service built from published versions only (below) |
| Management | The Studio's own screens: runs, task inbox, secrets, projects (one instance) | **Wayang Console** (separate repository): inventory, deploy, observe, govern every service |
| Dependencies at run time | None beyond the process (a database is optional) | A runtime needs only what its workflows use; it keeps running without the Console |
| Use it for | Local development, evaluation, small self-hosted automations, community | Production, many teams, separation of duties, scaling and isolation per workload |

The same definitions work in both: a project built in the all-in-one mode is released unchanged into the separated mode. A definition is portable, the topology is a deployment choice.

## Pro / Enterprise: three kinds of component

1. **Studio for production** (design plane). Authoring, validation, versioning, publishing, release generation. Production runtimes do not call it at run time.
2. **Generated runtimes** (execution plane), each a standalone service, one per application or workload:
   - **Agent runtime**: a service generated from an agent definition (an agent, its tools, memory and knowledge, served over HTTP). Today an agent service is a release whose workflow has an agent step (the `knowledge-assistant` template is the worked example, see `docs/RAG.md`).
   - **Workflow runtime**: a service generated from BPMN, DMN, EIP or common workflow definitions. Targets (`docs/RELEASE.md`): `STANDALONE` (command line), `SERVICE` (HTTP microservice, embedded engine), `GAMELAN` (Quarkus service with durable runs on Gamelan).
   - Profiles (`docs/PROFILES.md`) limit what a runtime can serve, so an agent runtime can refuse BPMN-only nodes and the reverse.
3. **Wayang Console** (management plane, `andalus-ai/wayang-console`). Registers runtimes, deploys and rolls back, shows desired versus observed state, collects metrics, logs and audit, governs plugins and approvals. Each runtime carries a local Management Agent (opt-in, off by default) that reports and takes commands. The Console is optional at run time: a runtime keeps executing if it is unreachable.

```
  all-in-one (Community)                    separated (Pro / Enterprise)

  +------------------------+      publish    +-----------------+   generate   +-----------------------+
  | Studio + design server |   ---------->   | Studio (design) |  ---------->  | Agent runtime (svc)   |
  | + execution (in-proc)  |                 +-----------------+   release     | Workflow runtime (svc)|
  +------------------------+                                                    +-----------+-----------+
                                            +-----------------+   heartbeat,               |
                                            | Wayang Console  | <----------------- Management Agent
                                            +-----------------+   commands (opt-in)
```

## What exists today

| Piece | Status |
|---|---|
| Studio (Flutter) and design server (definitions, validation, versions, runs, tasks, secrets, RBAC, WebSocket) | implemented in this repository; the REST shell is not compiled against real Quarkus here (`docs/API.md`) |
| Execution inside the design server (reference engine; Gamelan adapter) | implemented; the Gamelan path has not been run against a live Gamelan from here |
| Release pipeline: published versions only, no secret values, deterministic zip, component lock, `STANDALONE` / `SERVICE` / `GAMELAN` targets, Dockerfile and Kubernetes manifest | implemented (`docs/RELEASE.md`) |
| Agent steps in a release (`LlmAgentInvoker`, tools, knowledge connectors, tenant isolation, file API) | implemented; nothing run against a live model or deployed release |
| Profiles and extensions (`WAYANG_PROFILE`, `WayangExtension`) | implemented (`docs/PROFILES.md`) |
| Deployment lifecycle ledger, reconciliation, receipts | implemented as engine-neutral modules (`docs/deployment/`) |
| Management Agent: heartbeat, inventory, command polling with handler gating, artifact download | implemented in `wayang-management-client` (`docs/architecture/`); opt-in; verified only against test servers |
| Wayang Console (fleet registry, orchestration, digital twin, UI) | **separate repository**, not part of this one |
| **A single switch for the mode** (for example `WAYANG_MODE=community|design`) and one all-in-one distribution (one image or jar that serves Studio, API and execution) | **proposed**: today the pieces exist but are assembled by hand |
| A dedicated agent-runtime generator (an agent definition straight to a service, without a wrapping workflow) | **proposed** |
| Design server hardened for production use (disabling run execution so it only designs, split Studio hosting) | **proposed** |
| Pro / Enterprise licensing and which features sit in which edition | **not decided**; this document describes topology, not entitlements |

## Rules that keep the two modes one product

- One definition model; the mode changes where things run, never what a definition means.
- A runtime never depends on Studio or the Console to execute. The Console never contains workflow semantics; it manages what the compiler produced.
- The all-in-one mode must stay usable with no external service (no Numu, no Console, no database required).
- In the separated mode the design service must not be a production execution path, and a generated runtime must carry only the dependencies its workflows use.
- No second durable workflow engine: durable execution is Gamelan (or the in-memory reference engine for development), agent execution is Andalus where the agent adapter is used.
- Secrets stay `${secret:NAME}` references in every bundle, release and report.

## Next steps (proposed, in order)

1. A mode switch and an all-in-one packaging so Community is one command; document it as the quick start.
2. A design-only setting for the server (no run execution) for the separated mode; test that a production release has no route back to it.
3. An agent-runtime generator and an agent-oriented release profile (HTTP chat/invoke API, memory, knowledge, tools) as a first-class release kind.
4. Release signing and an artifact registry hand-off the Console can pull from (`docs/architecture/release-artifact-resolution.md`).
5. Console integration tests against a real Console once it publishes a stable management protocol (`wayang-core`).
