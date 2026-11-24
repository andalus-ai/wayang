# Repository Agent Instructions

These instructions apply to the entire `andalus-ai/wayang` repository. Follow them in addition to any more specific `AGENTS.md` files in subdirectories.

## Product and ownership boundaries

- Wayang owns workflow definitions, composition, validation, compilation, component contracts, packaging, Studio-facing APIs, local runtime integration, and its Management Agent.
- Gamelan owns durable workflow execution, scheduling, retries, persistence, and run lifecycle. Do not implement a competing durable workflow engine in Wayang.
- Andalus owns agent execution, models, tools, memory, and agent lifecycle. Integrate through adapters and stable contracts.
- Wayang Console is a separate product/repository: `andalus-ai/wayang-console`. Do not fold its central control-plane server or fleet-management UI into this repository.
- Numou (`andalus-ai/numu`) remains an independent platform. Use optional, versioned adapters only for capabilities with verified contracts. Wayang must remain usable without Numou.

## Editions and runtime deployment topologies

Wayang is one multi-mode Studio product with two runtime operating topologies from one codebase (`docs/EDITIONS.md` is the reference). Do not confuse Studio product modes (Agent Builder, Integration / ESB, Business Automation, Intelligent Business Automation, future Creative Content) with `WAYANG_MODE=community|design`, which controls server operating behavior.

- **Community / development (all-in-one):** Studio, design server and execution in one runtime, with no required external service.
- **Pro / Enterprise (separated):** a design-time Studio for production that never serves production traffic; **generated runtimes** as standalone services (agent runtime, or workflow runtime from BPMN, DMN, EIP or common workflows; targets in `docs/RELEASE.md`); and **Wayang Console** (separate repository) to manage all services.

Rules for contributors: a definition means the same across Studio product modes and both runtime topologies; a generated runtime never depends on Studio or the Console to execute and carries only what its workflows use; the all-in-one mode stays usable with no external service; the design service is never a production execution path in the separated mode; do not put workflow semantics in the Console or fleet management in this repository. `WAYANG_MODE=design` (design-only server) exists; mark anything not yet built (the all-in-one distribution, an agent-runtime generator, edition entitlements) as proposed, as `docs/EDITIONS.md` does.

## Architecture principles

- Preserve engine-neutral workflow and component contracts. Do not leak Gamelan/Andalus implementation types into core definition modules.
- Preserve BPMN, DMN, EIP, and agent semantics; do not flatten domain-specific constructs into generic nodes that lose meaning.
- Prefer embedded component execution for simple trusted components. Support isolated and remote workers through explicit, versioned invocation contracts when required.
- A plugin is a package/lifecycle boundary; a component is an executable capability; a node is its workflow-facing representation; a connector integrates an external system.
- Distinguish build-time inclusion, dynamic JVM plugins, and remote workers. Do not claim arbitrary hot loading works in Quarkus native images or for build-time extensions.
- Keep plugin permissions least-privilege. Verify publisher/signature, compatibility, dependencies, and policy before activation. Never execute an untrusted plugin in-process as a security boundary.
- Remote invocation contracts must define serialization, API compatibility, deadlines, cancellation, idempotency, error mapping, and retry safety.
- Runtime execution must continue safely if Wayang Console is unavailable. Management is optional and must not become an execution dependency.
- Keep management protocol contracts independent from Console server implementation details.

## Implementation workflow

1. Inspect the relevant module, tests, Maven POMs, and existing documentation before editing.
2. Reuse existing abstractions and compatibility seams. Avoid duplicate modules or parallel APIs.
3. Make the smallest coherent change that advances an agreed roadmap phase.
4. Add or update tests for behavior, compatibility, security, and failure paths.
5. Update relevant docs when contracts, supported behavior, or verification status changes.
6. Do not silently change public contracts, serialized formats, plugin IDs, component IDs, or workflow semantics. Document migration/compatibility when changes are necessary.
7. Do not add dependencies or new Maven modules without explaining why existing dependencies/modules are insufficient.
8. Do not mark a roadmap item complete merely because a type, stub, or interface exists.

## Verification and honesty

- Run the narrowest relevant tests first, then the full Maven verification when the environment permits.
- Record exact commands and outcomes. Distinguish source inspection, compilation, unit tests, contract tests, integration tests against fakes, tests against real services, and production verification.
- If JDK, Maven, Quarkus code generation, external artifacts, services, or Flutter tooling are unavailable, state that limitation; do not imply the build or test passed.
- Preserve the detailed verification caveats in `docs/ARCHITECTURE.md` and related module docs.
- Treat `docs/ROADMAP.md` as a plan. Update status only with evidence from implementation and tests.

## Java and module conventions

- Follow the Java/JDK baseline and style established by the module being changed; do not casually raise the project-wide baseline.
- Prefer Java records for immutable data where consistent with the current baseline. Avoid Lombok unless the existing module already requires it.
- Keep core modules free of transport, Quarkus, persistence, and vendor-specific dependencies unless their responsibility explicitly requires them.
- Use SPIs/adapters for persistence, transports, identity, secrets, and external engines.
- Validate inputs at trust boundaries; bound payload sizes, recursion, expression evaluation, and resource usage.
- Never log secret values, access tokens, raw credentials, or sensitive workflow payloads.

## Plugin and runtime safety

- Treat plugin manifests and remote worker responses as untrusted input.
- Reject duplicate component/plugin IDs and incompatible versions deterministically.
- Ensure plugin shutdown drains active work and closes owned resources. Do not promise safe classloader unloading without tests proving it.
- Do not silently fall back to a different placement when doing so changes data residency, security, consistency, or execution semantics.
- Keep Gamelan responsible for durable workflow retries and run state. Component routing must not introduce an independent retry loop that multiplies delivery attempts.
- Preserve idempotency and correlation metadata across worker boundaries.

## Documentation

- Mark planned behavior as proposed/planned and implemented behavior as implemented, with verification status.
- Keep examples consistent with actual supported schemas and APIs. Label illustrative manifests and pseudocode as examples.
- When changing a public contract, update its documentation and compatibility/conformance fixtures.
- Keep `docs/guide/` (architecture, sequences, features, developer guide; Mermaid diagrams) in step with the code: a change to node types, execution flow, extension points or release behavior updates the matching guide page.
- Keep root README concise and link to focused documents such as `docs/ROADMAP.md`, `docs/ARCHITECTURE.md`, and `docs/PROFILES.md`.


## Shared Wayang Core boundary

- Shared, versioned contracts intended for both Wayang and Wayang Console belong in `andalus-ai/wayang-core`.
- Wayang and Console depend on Core; Core must never depend on either application.
- Before introducing a shared DTO, enum, plugin descriptor, manifest, protocol message, or compatibility utility, check Core first and avoid creating a second source of truth.
- Keep workflow semantics, compiler, runtime execution, placement router, local plugin manager, Quarkus integration, and Management Agent implementation in this repository.
- Keep Console persistence, fleet registry, approval/distribution services, command orchestration, audit, and Console UI in `andalus-ai/wayang-console`.
- Do not move application services, JPA entities, REST resources, Quarkus bootstrap, or execution engine implementations into Core.
- Extract existing public types incrementally. Add characterization/serialization tests and compatibility facades before changing package names or wire formats.
- The Core repository's roadmap is a plan, not proof that artifacts or APIs are already implemented/released. Do not add Core dependencies until the relevant artifact has been published and compatibility is verified.
- Canonical management and worker protocol sources should eventually live in Core; server and runtime adapters remain in their owning repositories.


## Multi-mode product architecture (authoritative)

Read and follow [docs/MULTI-MODE-ARCHITECTURE.md](docs/MULTI-MODE-ARCHITECTURE.md) before making product, Studio, schema, plugin, compiler, runtime, or deployment changes. It is the architectural source of truth for the multi-mode product direction.

Wayang is **one extensible low-code platform with specialized modes**, not a collection of separate products or separate codebases:

- **Agent Builder:** agents, tools, knowledge/RAG, multi-agent and event-driven AI workflows.
- **Integration / ESB:** endpoints, routes, processors, messaging and EIP.
- **Business Automation:** BPMN process modeling and DMN decisions, preserving native domain semantics.
- **Intelligent Business Automation:** composition of agents, BPMN/DMN, EIP, data and human work; it is not a separate execution engine.
- **Creative Content:** future media-generation and multimodal pipeline mode; do not treat it as an initial-release dependency.

Traditional BI/analytics is not implied by the Intelligent Business Automation mode; treat a future BI experience as a separately scoped mode if approved.

### Mode, capability pack and deployment target

- A **mode** selects the user experience and authoring workflow.
- A **capability pack** contributes versioned domain components, schemas, designers/editors, validators, connectors, engine adapters and runtime dependency metadata.
- A **deployment target/profile** determines how a compiled solution is packaged and operated.
- Never conflate these three axes or create a separate runtime merely because a new Studio mode exists.
- Provide specialized designers and domain document models. Shared graph/editor infrastructure is encouraged, but do not force BPMN, DMN, EIP, agent and future media models into one lossy generic graph.
- Intelligent Business Automation composes existing capabilities through explicit typed and versioned boundaries; it does not justify a duplicate workflow engine.

### Core and engine boundaries

- Keep Wayang core domain-neutral. Domain semantics belong in capability packs and their engine adapters.
- Preserve the ownership boundaries above: Gamelan owns durable workflow execution/lifecycle; Andalus owns agent execution. Use adapters and stable contracts.
- Use a mature integration engine/component ecosystem for EIP where appropriate; Apache Camel may be evaluated as a reference/candidate, not assumed to be an existing dependency.
- Use a BPMN-compatible engine/adapter for BPMN execution when required; do not casually expand a partial in-house BPMN subset into a claim of full BPMN conformance.
- DMN evaluation must state its supported conformance and semantics.
- Creative/media execution requires a future specialized graph/worker path for GPU scheduling, model residency, large artifacts and streaming. Do not force those concerns into BPMN or the generic workflow engine.
- Cross-engine calls must define typed payloads, identity/tenant propagation, correlation, deadlines, cancellation, idempotency, error mapping, security and observability.

### Two-phase lifecycle and edition constraints

- **Phase 1 — Studio authoring:** design, validate, test, version and publish authoring projects.
- **Phase 2 — deployment/execution:** compile published projects into immutable, reproducible, dependency-resolved artifacts and deploy independent runtimes.
- A Phase 2 runtime must execute without Studio or Wayang Console and include only dependencies required by its artifact.
- Phase 2 deployment targets include standalone local runtime, microservices, and cloud-native deployment. Microservice/cloud-native scaling requires discovery, health, durable state where needed, idempotency/fencing, graceful drain, recovery and explicit retry ownership; service discovery alone is not HA.
- **Community:** all-in-one Studio + design server + execution. No separated production runtime deployment, distributed scaling or HA orchestration entitlement. Keep it complete and useful without external infrastructure.
- **Pro:** separated Studio and generated standalone/local or production microservice runtimes.
- **Enterprise:** distributed/cloud-native deployment, advanced discovery, horizontal scaling, HA, governance and enterprise operations.
- These are agreed product-direction constraints, not proof that each distribution or entitlement is implemented. Keep status and verification honest in EDITIONS.md and RELEASE.md.
- Same definitions and core contracts across editions; edition differences are deployment/operations, not forked workflow semantics or incompatible schemas.

### Required architecture review

For every significant feature/PR, identify the mode(s), neutral-core versus pack ownership, domain document model, designer contribution, execution engine owner, adapter contract, artifact dependency impact, Community behavior, Pro/Enterprise deployment needs, security/reliability requirements, and implementation/verification status. Update the architecture docs when any of these boundaries change.


## Detailed architecture diagrams

Before implementing or changing a mode, runtime deployment, service discovery, scaling, retry, recovery or engine adapter, read the relevant diagrams and mode architecture:
- Platform sequences and topology: `docs/architecture/DIAGRAMS.md`
- Distributed runtime, registration, routing, HA and backpressure: `docs/architecture/DISTRIBUTED-RUNTIME.md`
- Agent Builder: `docs/architecture/modes/AGENT-BUILDER.md`
- Integration / ESB: `docs/architecture/modes/INTEGRATION-ESB.md`
- BPMN / DMN Business Automation: `docs/architecture/modes/BUSINESS-AUTOMATION.md`
- Cross-domain Intelligent Business Automation: `docs/architecture/modes/INTELLIGENT-BUSINESS.md`
- Future Creative Content: `docs/architecture/modes/CREATIVE-CONTENT.md`

Treat these as target architecture, not proof of implementation. Preserve the ownership of execution state, retries, timers, transactions, idempotency and compensation at each engine boundary. Any sequence crossing engine/service boundaries must define identity and tenant propagation, correlation/causation, deadlines, cancellation, idempotency, typed errors and observability. Any distributed design must cover readiness versus liveness, lease expiry, fencing/ownership, backpressure, graceful drain, recovery and failure testing. Service discovery by itself is never an HA design.

Update the relevant diagrams when implementation changes a contract or ownership boundary. If the code differs from the target, document the gap and status rather than silently treating the diagram as implemented.


## Foundation contract alignment

Before changing project/document persistence, document kinds, Studio node catalogs, component descriptors, capability packs, or artifact dependency resolution, read `docs/architecture/FOUNDATION-CONTRACT-AUDIT.md`. It records the current implementation baseline and the proposed M1 contract direction. In particular:
- Keep BPMN `NodeCatalog` BPMN-specific; do not turn its element enum into the universal multi-mode palette.
- Keep graph-node/port descriptors distinct from runtime component descriptors.
- Keep configuration schemas distinct from typed data ports. Missing mandatory data inputs are advisory diagnostics by default; only explicit hard preconditions block publish or execution.
- Keep tenant/project ownership explicit and preserve legacy `studio.projectId` documents through migration.
- Deeply freeze/copy stored and published JSON documents; shallow map wrappers are not sufficient for immutable snapshots.
- Treat execution-mode declarations as metadata, not as proof of remote routing, worker registration, plugin installation or HA.

The audit is a proposed alignment, not a claim that these contracts are already implemented. Update it as M1 implementation and tests land.
