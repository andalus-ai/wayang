# Editions and runtime deployment topologies

Wayang is one multi-mode product and one codebase. This document distinguishes the Studio product modes from the two runtime operating topologies and records what is built today versus planned. Status words are literal:
**implemented** (code exists and is tested at the level stated elsewhere in `docs/`), **partial**, **proposed** (a plan, not code).

## Runtime operating topologies (not Studio product modes)

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
| **Mode switch** `WAYANG_MODE=community|design` on the design server (`Mode`, `WayangApi`) | implemented and unit-tested in `wayang-api-core`; see below. Not exercised through the Quarkus REST shell (it compiles against stubs only) |
| One all-in-one distribution (one image or jar that serves Studio, API and execution) | **partly**: `wayang.auth.mode=local` lets the API run with no identity service (`docs/IDENTITY.md`); `wayang.studio.dir` serves the Studio web build from the API process (`StudioFiles` unit-tested; the Quarkus route compiles on CI but was never started, and Studio was not built here); `deploy/Dockerfile.all-in-one` and `deploy/docker-compose.all-in-one.yml` exist but were **never built or run** (no Docker daemon, Flutter SDK or Maven Central here); data is in memory unless a database is configured |
| A dedicated agent-runtime generator (an agent definition straight to a service, without a wrapping workflow) | **implemented** as an agent-only release with `POST /v1/agents/{id}/chat` (`docs/RELEASE.md`); runtime path tested end to end locally, generated project compiled on CI only; not on the Gamelan target, no streaming |
| Design-only server (no run execution) | implemented as `WAYANG_MODE=design`; serving Studio separately from the API is **proposed** |
| Pro / Enterprise licensing and which features sit in which edition | **not decided**; this document describes topology, not entitlements |

## `WAYANG_MODE`

| Value | Meaning |
|---|---|
| unset, `community` (also `all-in-one`, `development`) | design and execute in one server |
| `design` (also `design-only`) | the server designs but never executes: starting a run, completing a task, sending a message and firing timers answer **409** ("this server runs in design mode"). Definitions, validation, versions, projects, secrets, templates and releases work as usual |

Any other value is an error at start-up (never a silent fall-back to executing). Use `design` for the production Studio of the separated edition and ship runs through generated runtimes. Do not configure triggers or chat channels on a design server: they start runs, which it refuses. `GET /api/v1/mode` returns `{mode, executes}`; Studio hides the Runs and Tasks screens when `executes` is false (an older server without the endpoint is treated as executing). Listing and reading runs still works (it is empty unless the server previously ran in community mode).

## Rules that keep the two runtime topologies one product

- Studio product modes select domain-specific authoring experiences; runtime operating topology changes where things run, never what a definition means.
- `WAYANG_MODE=community|design` is a server operating-mode switch, not the Agent Builder / Integration / BPMN / Intelligent Business Studio mode selector.
- A runtime never depends on Studio or the Console to execute. The Console never contains workflow semantics; it manages what the compiler produced.
- The all-in-one mode must stay usable with no external service (no Numu, no Console, no database required).
- In the separated mode the design service must not be a production execution path, and a generated runtime must carry only the dependencies its workflows use.
- No second durable workflow engine: durable execution is Gamelan (or the in-memory reference engine for development), agent execution is Andalus where the agent adapter is used.
- Secrets stay `${secret:NAME}` references in every bundle, release and report.

## Next steps (proposed, in order)

1. All-in-one packaging so Community is one command; document it as the quick start.
2. ~~Test that a production release has no route back to the design server.~~ Done: `PublishTest.aReleaseCarriesNoCodeOrRouteBackToTheDesignServer` checks, for every target, that the pom and artifact lock hold none of the design modules (api, stores, identity and management adapters, publisher, REST shell, OIDC, database) and that no generated file references the design API package, `WAYANG_MODE`/`WAYANG_STUDIO` or `/api/v1`.
3. ~~An agent-runtime generator~~ Done as an agent-only release with a chat endpoint (`docs/RELEASE.md`); a dedicated agent-oriented profile remains open.
4. Release signing and an artifact registry hand-off the Console can pull from (`docs/architecture/release-artifact-resolution.md`).
5. Console integration tests against a real Console once it publishes a stable management protocol (`wayang-core`).

## All-in-one on one machine

```
flutter build web --release                          # in studio/
export WAYANG_AUTH_MODE=local WAYANG_AUTH_LOCAL_API_KEY=<24+ random characters>
export QUARKUS_OIDC_ENABLED=false QUARKUS_HTTP_AUTH_PERMISSION_API_POLICY=permit
export WAYANG_STUDIO_DIR=$PWD/studio/build/web
java -jar wayang-api-rest/target/quarkus-app/quarkus-run.jar
# open http://localhost:8080 and enter the key in Studio's connection settings
```

Studio is served by the same process; only GET/HEAD outside `/api/`, `/ws/`, `/hooks/`, `/oauth/`, `/admin/`, `/openapi`, `/q/` is answered by it. Unverified end to end: the recipe above has not been run.

### Container (unbuilt)

```
export WAYANG_AUTH_LOCAL_API_KEY=$(openssl rand -hex 24)
docker compose -f deploy/docker-compose.all-in-one.yml up --build
```

The compose file publishes on `127.0.0.1` only, refuses to start without the key, and runs as a non-root user. The store is in memory by default: stopping the container loses everything; set `WAYANG_STORE=jdbc` and a datasource for durability. Studio is built with `WAYANG_API=http://localhost:8080`; build with `--build-arg WAYANG_API=...` if the server is reached under another address.


## Agreed multi-mode packaging direction

The multi-mode product architecture is defined in [MULTI-MODE-ARCHITECTURE.md](MULTI-MODE-ARCHITECTURE.md). The edition boundary is:

| Edition | Authoring and execution shape | Deployment entitlement |
|---|---|---|
| **Community** | Studio, design server and execution together in one all-in-one runtime | Phase 1 authoring plus integrated local execution. No separated production runtime deployment, distributed scaling or HA orchestration. |
| **Pro** | Studio/design environment separated from generated runtime artifacts | Phase 2 standalone/local runtimes and production microservice deployments, with the supported Pro operational capabilities. |
| **Enterprise** | Separated Studio, runtime and management/control-plane deployment | Phase 2 distributed/cloud-native deployment, advanced service discovery, horizontal scaling, HA, governance and enterprise operations. |

These are the agreed product and packaging constraints. They do not change the status table above: implementation and verification status must be assessed independently for each distribution, target and capability. In particular, a release generator or a design-only server existing in source does not by itself prove that the complete Pro/Enterprise distribution, service discovery, distributed execution, HA or edition enforcement is delivered.

All editions share the same authoring definitions, schemas and core contracts. Community is not a reduced definition language; it is the all-in-one product shape. Pro and Enterprise add independent runtime deployment and operational scale. A Community project should be portable to compatible Pro/Enterprise runtimes without redesign.

Phase 2 targets are standalone local runtime, microservice runtime, and cloud-native runtime. Service discovery is necessary for many distributed deployments but is not sufficient for HA: durable state where needed, health/heartbeats, idempotency/fencing, graceful drain, recovery, observability and explicit retry ownership are also required.

The exact commercial licensing, entitlements and release packaging remain separate product decisions and must not be inferred from architecture documentation.
