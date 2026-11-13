# Wayang Platform Roadmap

> Status: architecture and implementation plan. This document distinguishes existing, partially verified capabilities from proposed work. It is not a claim that every target below is implemented.

## 1. Product direction

Wayang is the definition, composition, validation, compilation, packaging, and user-facing automation platform for EIP, BPMN, DMN, agentic workflows, and integrations. It builds on:

- **Gamelan** for durable workflow execution and scheduling.
- **Andalus** for agent execution and agent capabilities.
- **Wayang** for workflow definitions, semantic validation, component contracts, compilation, release packaging, and Studio.

Wayang must not grow a competing durable workflow engine or duplicate Andalus agent-runtime responsibilities.

The platform has three planes and three corresponding product experiences:

| Plane | Product experience | Responsibility |
|---|---|---|
| Design plane | Wayang Studio / Compose | Compose, inspect, validate, test, version, and publish definitions |
| Execution plane | Wayang Runtime | Execute published definitions using a selected engine and selected components |
| Management plane | Wayang Console (separate repository) | Register, discover, deploy, observe, govern, and manage runtime fleets |

These experiences may be deployed independently. A local installation may combine them, but no runtime may require the central Console to continue executing.

## 2. Core architectural principles

1. **One definition model, multiple execution topologies.** A workflow definition should remain portable while its deployment plan determines component placement.
2. **Engine neutrality at the Wayang boundary.** Execution plans and component contracts must not leak Gamelan- or Andalus-specific implementation types.
3. **Gamelan owns durable workflow lifecycle.** Wayang adapters translate definitions/plans and component invocations; they do not reimplement durable scheduling, retries, persistence, or run history.
4. **Andalus owns agent execution.** Wayang exposes agent tasks and adapters, not a second agent runtime.
5. **Embedded by default, distributed when justified.** Avoid turning every node into a service. Use remote workers for isolation, language diversity, independent scaling, data locality, or resource specialization.
6. **Optional management.** Runtime operation must survive Console disconnection or absence.
7. **Explicit capability and compatibility.** Components declare API compatibility, dependencies, execution modes, state, resource needs, and security requirements.
8. **Secure extension lifecycle.** Plugin installation, approval, activation, execution, upgrade, and removal are separate controlled actions.
9. **Honest verification.** Documentation and release notes must distinguish unit-tested, contract-tested, integration-tested, and production-verified behavior.

## 3. Current baseline (from repository documentation)

The following capabilities are present in the repository documentation and source/module inventory. Their verification limits are recorded in [Architecture](ARCHITECTURE.md), [Profiles and extensions](PROFILES.md), and [Release](RELEASE.md).

- Definition and validation modules for expressions, BPMN subset, DMN, EIP, agents, and runtime handlers.
- Engine-neutral execution plan and Gamelan worker/run adapters.
- Andalus agent adapter and node catalog integration (integration build/runtime remains unverified as documented).
- Runtime profiles: `full`, `automation`, `integration`, `agentic`, and custom feature lists.
- `WayangExtension` discovered with Java `ServiceLoader`, currently contributing connectors, trigger pollers, webhook providers, and custom executor handlers.
- Release generation for standalone, HTTP service, and Gamelan targets, with profile filtering and a component dependency lock.
- A Flutter `studio/` app currently described by consolidation docs as combining former Studio and Console screens.
- Optional Numu identity/tenancy/authz adapter; the real Numu gRPC integration is not yet verified as described in [Identity](IDENTITY.md).

Important gaps and constraints:

- Existing release component lock is a component-contract lock, not a complete resolved Maven/JAR artifact closure.
- Existing profiles filter feature families; they are not yet a complete plugin package manager or per-component placement scheduler.
- Existing `ServiceLoader` extensions are startup/build classpath extensions, not a general hot-install/hot-uninstall system.
- Standalone/service reference-engine runs are in-memory and single-replica; durable releases should use the Gamelan target.
- Existing distributed Gamelan executor types provide a starting point for worker-pool separation, but live Gamelan routing has not been verified.
- The current consolidated Flutter app and API do not yet constitute the proposed independent fleet-management Console.

## 4. Target architecture

### 4.1 Compose / Design mode

Compose provides the full authoring catalog and tools for:

- EIP integration flows.
- BPMN processes.
- DMN decisions.
- Agent tasks and agent-oriented compositions.
- Data transformation and pipelines.
- Human tasks, triggers, connectors, and event-driven flows.
- Mixed compositions preserving each paradigm's semantics.

The shared model must not flatten BPMN, EIP, DMN, and agent semantics into indistinguishable generic nodes. Each construct retains its domain metadata and validation rules while participating in a common composition and compilation pipeline.

### 4.2 Deployment mode

The deployment compiler consumes published definitions and produces a reproducible deployment plan/package:

1. Resolve referenced component IDs and versions.
2. Validate component and runtime API compatibility.
3. Compute transitive component and artifact dependencies.
4. Resolve connector, provider, and secret requirements without embedding secret values.
5. Validate requested placement and execution-mode constraints.
6. Produce a component lock, artifact/SBOM metadata, deployment manifest, and immutable definition digest.
7. Package only required runtime artifacts for the selected target.
8. Sign and verify packages where configured.

Targets:

- Embedded standalone application.
- Standalone HTTP service.
- Microservice per application/workflow or bounded workload.
- Gamelan-backed durable distributed execution.
- Isolated local worker processes/containers.
- Remote worker pools and cloud-native deployments.
- Edge and hybrid deployments.

### 4.3 Management mode

The independent `andalus-ai/wayang-console` repository owns the central management plane:

- Organization/environment inventory and runtime registry.
- Runtime registration, identity, heartbeat, and capability inventory.
- Discovery and topology.
- Deployment orchestration and desired/observed state reconciliation.
- Workflow inventory, remote definition inspection, preview, and version comparison.
- Plugin catalog, distribution policy, approvals, and fleet lifecycle operations.
- Digital twin, drift detection, and dependency graph.
- Metrics, logs, traces, events, execution monitoring, and audit.
- Governance and administrative controls.

Wayang Runtime owns its local Management Agent implementation and protocol client. The agent may be embedded, a separate process, or a sidecar. It must not become a second workflow engine.

## 5. Extensible component and plugin architecture

### 5.1 Terminology

- **Component:** Functional capability invoked by a workflow (processor, trigger, connector, decision handler, agent task, etc.).
- **Node:** Workflow-facing typed representation of a component, including inputs, outputs, configuration schema, and visual metadata.
- **Connector:** Integration boundary to an external system or protocol.
- **Plugin / extension:** Versioned package that contributes one or more components, connectors, providers, handlers, schemas, UI metadata, and dependencies.
- **Worker:** Process/container/service that hosts one or more remotely executable components.
- **Execution placement:** Runtime decision to invoke a component embedded, isolated locally, remotely, or in a cloud worker pool.

A plugin is a packaging, trust, compatibility, and lifecycle boundary. It is not synonymous with one node.

### 5.2 Execution modes

| Mode | Description | Suitable use |
|---|---|---|
| Embedded | Component executes in the runtime process | Lightweight, trusted, low-latency Java components |
| Isolated local | Component executes in a separate local process/container | Python/native dependencies, untrusted code, conflicting dependencies |
| Remote | Component executes in an independently deployed worker service | Independent scaling, language diversity, specialized resources |
| Cloud-native | Component executes in managed worker pools or scheduled workloads | Elastic, multi-node, cloud/edge deployments |

A component may advertise only the modes it actually supports. Remote execution requires a serializable, versioned input/output contract and explicit timeout, idempotency, cancellation, state, and retry semantics. Stateful components may require affinity or externalized state.

### 5.3 Plugin installation strategies

| Strategy | Lifecycle | Target |
|---|---|---|
| Build-time inclusion | Included and validated during packaging/Quarkus augmentation | Production, native images, minimal releases |
| Dynamic JVM plugin | Loaded through a supported isolated classloader in a JVM runtime | Long-lived JVM deployments requiring hot extension |
| Remote plugin | Deployed as a worker and registered through the worker protocol | Multi-language, isolated, distributed, cloud-native deployments |

Dynamic JVM loading is not promised for Quarkus native executables or arbitrary Quarkus build-time extensions. A plugin classloader closing does not prove safe unloading: active calls must drain, threads/resources must stop, and classloader references must be released. Remote plugins are the preferred isolation boundary for arbitrary or untrusted code.

### 5.4 Plugin package and manifest

Define a versioned Wayang Plugin Package (WPP) with, at minimum:

- Plugin identity, publisher, semantic version, and API compatibility.
- Component and connector descriptors.
- Configuration and input/output schemas.
- Runtime artifact references and dependency constraints.
- Supported execution modes and resource/state requirements.
- Required capabilities/permissions.
- UI metadata and documentation.
- License, SBOM, content digest, and publisher signature.
- Upgrade/migration and lifecycle metadata.

Manifest declarations are claims to validate, not authority to execute. The runtime must verify signatures, compatibility, dependency resolution, and policy before activation.

### 5.5 Local registry and lifecycle

The local plugin manager should support discovery, validation, signature verification, dependency resolution, install, activation, health, deactivation, draining, upgrade, rollback, and uninstall.

Use explicit lifecycle states such as:

`DISCOVERED -> VALIDATING -> RESOLVED -> INSTALLING -> INSTALLED -> STARTING -> ACTIVE -> DRAINING -> STOPPED -> UNINSTALLED`

Failures must be observable and recoverable. A registry entry must not imply that a plugin is active or trusted.

### 5.6 Component execution router

Introduce a placement/invocation subsystem that resolves a component invocation to an eligible local handler or worker. Inputs include:

- Workflow/deployment placement policy.
- Component-supported modes and API compatibility.
- Worker capabilities, health, and resource availability.
- Latency, data locality, security zone, and cost constraints.
- State affinity and execution semantics.

The router is not a workflow engine. Gamelan (or the explicitly selected engine) owns control flow, durable lifecycle, retries, and run state. The router owns component placement and invocation. Fallback is allowed only when it preserves the component's declared semantics and security requirements.

### 5.7 Remote worker protocol

Define language-neutral, versioned contracts for:

- Worker registration and heartbeat.
- Capability advertisement and component inventory.
- Invocation request/response and streaming where required.
- Correlation, trace context, deadline, cancellation, and idempotency.
- Health, drain, and lifecycle commands.
- Error classification and retry safety.
- Schema/API negotiation.

Use gRPC/Protocol Buffers as the initial transport candidate; keep transport behind an interface so alternative transports can be added. Propagate OpenTelemetry context across RPC and messaging boundaries.

## 6. Repository boundaries and Numou reuse

### Wayang — `andalus-ai/wayang`

Owns workflow definitions, composition contracts, validation, compilation, component/plugin APIs, local registry and execution integration, deployment compiler/package generation, runtime profiles, adapters to Gamelan/Andalus, and the local Management Agent.

### Wayang Console — `andalus-ai/wayang-console`

Owns the standalone management application, control plane, fleet registry, deployment orchestration, digital twin, management UI, observability aggregation, governance, and remote plugin lifecycle across instances.

### Numou — `andalus-ai/numu`

Remains an independent platform. Wayang may integrate through optional, versioned adapters for identity, tenancy, authorization, secrets, audit, notification, or event capabilities only where stable contracts exist. Wayang Community must remain usable without Numou. Do not import Numou industry/business domain modules into Wayang or conflate Numou application installation with Wayang workflow deployment.

Avoid circular dependencies: Console depends on Wayang management contracts, not Wayang implementation internals; Wayang does not depend on Console server code.

## 7. Phased roadmap

Priority meanings: **P0** foundational correctness; **P1** production/distributed capability; **P2** ecosystem and advanced operations. Phases may overlap after their contracts stabilize.

### Phase 0 — Baseline, boundaries, and verification (P0)

- [ ] Reconcile current source, module graph, docs, and actual Maven build.
- [ ] Keep verification claims accurate; add a reproducible CI build/test baseline.
- [ ] Separate Studio authoring responsibilities from the independent Console product boundary.
- [ ] Inventory existing extension points, connector implementations, executor handlers, profiles, and release packaging.
- [ ] Document unsupported BPMN constructs and runtime durability limits.

**Exit criteria:** A clean architecture/module map, known verification baseline, and no ambiguous ownership between Wayang, Gamelan, Andalus, Console, and Numou.

### Phase 1 — Component and plugin contracts (P0)

- [ ] Define stable component, node, connector, trigger, provider, and extension descriptors.
- [ ] Define plugin manifest, semantic versioning, API compatibility, dependency and permission metadata.
- [ ] Define configuration and typed input/output schema rules.
- [ ] Preserve existing `WayangExtension` compatibility through an adapter/deprecation path.
- [ ] Add contract fixtures and compatibility tests.

**Exit criteria:** Built-in and third-party components can be described and validated without depending on a specific runtime engine.

### Phase 2 — Registry, profiles, and embedded execution (P0)

- [ ] Unify built-in and extension discovery behind a local component registry.
- [ ] Enforce deterministic duplicate-ID and version-conflict handling.
- [ ] Integrate registry filtering with existing `WAYANG_PROFILE` behavior.
- [ ] Keep embedded Java execution as the default low-overhead path.
- [ ] Ensure disabled or unavailable components fail validation before deployment.
- [ ] Expose catalog metadata for Studio without leaking implementation classes.

**Exit criteria:** Existing built-in components and ServiceLoader extensions resolve deterministically, and profiles/catalog/validation agree.

### Phase 3 — Reproducible deployment compiler (P0)

- [ ] Extend component lock into a full resolved dependency/artifact lock where feasible.
- [ ] Resolve transitive dependencies and detect version conflicts.
- [ ] Generate minimal standalone, service, and Gamelan packages.
- [ ] Emit immutable workflow digest, manifest, SBOM, provenance, and package checksums.
- [ ] Validate secrets/configuration by reference; never package secret values.
- [ ] Add package reproducibility and compatibility tests.

**Exit criteria:** A published workflow produces a repeatable, minimal package with a verifiable dependency and component inventory.

### Phase 4 — Execution placement and local isolation (P1)

- [ ] Define placement policy and component execution requirements.
- [ ] Implement the component execution router behind a stable invocation API.
- [ ] Add local process/container worker adapter.
- [ ] Define deadlines, cancellation, idempotency, error mapping, and backpressure.
- [ ] Add isolation and permission policy for third-party extensions.
- [ ] Preserve workflow-engine ownership of durable retries and run lifecycle.

**Exit criteria:** A workflow can mix embedded and isolated local components without changing its logical definition.

### Phase 5 — Remote worker protocol and distributed execution (P1)

- [ ] Define worker registration, capability discovery, and heartbeat contracts.
- [ ] Define language-neutral invocation and result/error contracts.
- [ ] Implement Java worker SDK and runtime client/server.
- [ ] Add worker health, draining, graceful shutdown, and compatibility negotiation.
- [ ] Propagate trace/correlation context and execution identity.
- [ ] Test worker loss, duplicate delivery, timeout, cancellation, and retry behavior.
- [ ] Add Python SDK as the first non-Java worker implementation.

**Exit criteria:** A workflow can invoke a remote component with documented failure and delivery semantics.

### Phase 6 — Dynamic JVM plugin lifecycle (P1, optional capability)

- [ ] Evaluate PF4J or another maintained classloader implementation behind Wayang-owned interfaces.
- [ ] Define trust policy, signed artifacts, allowlists, and dependency isolation.
- [ ] Implement install/activate/deactivate/drain/uninstall for supported JVM deployments.
- [ ] Reject unsupported Quarkus build-time/native plugin combinations clearly.
- [ ] Prevent upgrade/unload while executions or resource references remain active.
- [ ] Add rollback and lifecycle conformance tests.

**Exit criteria:** Hot installation is supported only for explicitly compatible JVM plugin types, with safe draining and clear limitations.

### Phase 7 — Standalone Wayang Console foundation (P1; separate repository)

- [ ] Define and publish versioned management contracts from Wayang.
- [ ] Implement independent Console backend, persistence, API, and UI in `andalus-ai/wayang-console`.
- [ ] Implement runtime registration, identity, heartbeat, and capability inventory.
- [ ] Implement instance/environment registry and basic fleet views.
- [ ] Implement embedded and sidecar Management Agent deployment options.
- [ ] Verify runtime continues when Console is unavailable.

**Exit criteria:** Multiple independently deployed runtimes can register and be inspected by a standalone Console.

### Phase 8 — Fleet deployment, plugin governance, and digital twin (P1)

- [ ] Implement deployment desired/observed state and safe reconciliation.
- [ ] Add remote workflow inventory and read-only preview from immutable definitions/digests.
- [ ] Add fleet-wide plugin catalog, approval, distribution, activation, and rollback.
- [ ] Implement topology/dependency graph and digital twin.
- [ ] Add drift detection and impact analysis.
- [ ] Enforce tenant/environment boundaries and audited management commands.

**Exit criteria:** Console can manage deployment/plugin state and explain differences between desired and observed fleet state.

### Phase 9 — Observability and operational resilience (P1)

- [ ] Standardize metrics, logs, traces, events, and execution correlation.
- [ ] Add bounded local buffering and replay for management telemetry.
- [ ] Add fleet health, worker capacity, component latency, and failure views.
- [ ] Add multi-node persistence/fan-out where required.
- [ ] Test Console outage, network partition, stale heartbeat, and recovery.
- [ ] Keep operational telemetry separate from sensitive workflow payloads.

**Exit criteria:** Operators can diagnose a distributed execution without making Console a runtime dependency.

### Phase 10 — Language SDKs and extension ecosystem (P2)

- [ ] Stabilize Java SDK and conformance test kit.
- [ ] Deliver Python and TypeScript worker SDKs.
- [ ] Provide plugin templates, local dev runner, validator, packager, and examples.
- [ ] Establish plugin signing, publisher identity, provenance, license, and vulnerability policies.
- [ ] Add optional private/public catalogs and marketplace workflows.

**Exit criteria:** External developers can build, test, package, sign, and distribute compatible extensions.

### Phase 11 — Cloud-native and advanced deployment (P2)

- [ ] Add Kubernetes worker deployment/operator integration where justified.
- [ ] Support autoscaling based on queue depth, concurrency, and resource signals.
- [ ] Support placement across clusters/regions with explicit data-residency policies.
- [ ] Add edge/hybrid connectivity and offline-safe operation.
- [ ] Add deployment strategies such as rolling, canary, and blue/green where execution semantics allow.

**Exit criteria:** The same component and workflow contracts support standalone, distributed, cloud-native, and edge topologies.

### Phase 12 — Advanced composition and enterprise operations (P2)

- [ ] Expand BPMN/DMN support based on explicit conformance and interoperability requirements.
- [ ] Add sub-process/call-activity support through Gamelan capabilities rather than a second engine.
- [ ] Add simulation and deployment impact analysis.
- [ ] Add enterprise governance, policy distribution, approval workflows, and advanced fleet operations.
- [ ] Define Community/Pro/Enterprise packaging without fragmenting core contracts.

**Exit criteria:** Advanced enterprise capabilities build on the same open component, workflow, and management contracts.

## 8. Cross-cutting non-functional requirements

- **Security:** signed packages, least-privilege capabilities, tenant isolation, secret references, TLS/mTLS, audit, and explicit trust boundaries.
- **Reliability:** idempotency, bounded retries, deadlines, cancellation, backpressure, drain, crash recovery, and no implicit exactly-once claims.
- **Compatibility:** semantic versions for plugins and APIs, explicit runtime compatibility ranges, schema evolution, and conformance tests.
- **Performance:** embedded path must avoid unnecessary serialization/network overhead; remote path must expose queueing and transport costs.
- **Portability:** definitions and deployment manifests must not embed host-specific secrets or implementation-only Java types.
- **Observability:** correlation across workflow, component, worker, and management operations without leaking payloads or credentials.
- **Native support:** build-time inclusion is the supported default for Quarkus native; dynamic class loading is an explicitly separate JVM capability.
- **Operations:** runtime must continue safely through management-plane outages; management commands must be authenticated, authorized, idempotent, and auditable.

## 9. Initial end-to-end acceptance scenario

The first full vertical slice should prove:

1. Compose a workflow containing an EIP node and an agent task.
2. Publish and compile the definition.
3. Generate a minimal package with a component/dependency lock.
4. Deploy one component embedded and one to a remote worker.
5. Register the runtime and worker with Wayang Console.
6. Discover capabilities and reconstruct the deployed workflow in read-only preview.
7. Execute through the selected workflow engine.
8. Observe correlated execution events in Console.
9. Disconnect Console and prove execution continues.
10. Reconnect and reconcile observed inventory/state.
11. Stop/drain a worker and verify in-flight behavior and failure handling.
12. Reject an incompatible or unsigned plugin before activation.

## 10. Decision log and immediate next steps

The following are proposed architecture decisions, not claims of completed implementation:

- Three-plane product architecture: Design, Execution, Management.
- Separate Studio from the standalone Console product/repository.
- One versioned component contract with multiple execution backends.
- Embedded execution as default; isolated/remote/cloud placement by policy.
- Build-time packaging as the production/native default; dynamic JVM loading as an optional capability.
- Gamelan remains the durable workflow engine; Andalus remains the agent runtime.
- Numou integration remains optional and adapter-based.
- Management Agent can be embedded or separately deployed.
- Console outage must not stop an already-running runtime.

Immediate next work should be Phase 0 followed by Phase 1. Do not create all proposed modules at once. First inspect the actual code and Maven graph, establish tests/CI, then implement the smallest contract-first vertical slice.


## Implementation progress (2026-11-08)

### Phase 0 — source-level baseline review

- [x] Inspected the active Maven reactor, runtime module, current extension SPI, component descriptor/registry, executor catalog, profiles, and architecture documentation.
- [ ] Full Maven/CI verification remains outstanding; no build or test execution is claimed from this source review.

### Phase 1 — component execution capability contract (first increment)

- [x] Reuse the existing `ComponentDescriptor`, `ComponentRegistry`, `WayangExtension`, and `Extensions` contracts; do not introduce a parallel component identity/catalog.
- [x] Add explicit execution modes: embedded, isolated-local, remote, and cloud-native.
- [x] Add state models: stateless, externally persisted, and affinity-bound.
- [x] Add exact component ID/version-bound capability declarations for payload portability, cancellation, idempotency, required worker capabilities, and resource hints.
- [x] Add a validating capability registry that rejects unknown components, duplicate declarations, and version mismatches.
- [x] Preserve source compatibility for existing `WayangExtension` implementations through a default empty capability contribution.
- [x] Add unit tests for capability validation and profile-filtered extension contributions.

**Remaining Phase 1 work:** define and validate the versioned plugin manifest/package contract, compatibility ranges, dependency metadata, publisher/signature metadata, and conformance fixtures. Execution capability declarations are metadata only; no placement router, worker transport, dynamic loader, or hot-upgrade behavior is implemented by this increment.

**Verification note:** source and documentation changes are committed in the implementation PR. Tests have been added but have not been executed in this environment. Run `mvn -pl wayang-runtime -am test` with JDK 21 and resolvable reactor dependencies before treating this increment as verified.


### Phase 1 — plugin manifest model (second increment)

- [x] Add an immutable in-memory `PluginManifest` with plugin identity, semantic version, plugin API version, publisher, runtime compatibility expression, provided component inventory, dependencies, permissions, and license metadata.
- [x] Add component inventory validation against contributed `ComponentDescriptor` instances.
- [x] Add an initial JSON Schema for the plugin manifest interchange shape.
- [x] Add tests for semantic-version syntax, duplicate/self dependencies, permissions, and manifest-to-component inventory consistency.

**Remaining:** implement a parser/serializer, semantic version-range evaluation, signature and artifact provenance verification, and package installation policy. The current model does not authenticate publishers or execute/install plugin code.


## Shared Core extraction (cross-repository)
- [ ] Characterize existing component/plugin descriptors, manifest schema and execution-capability contracts with golden fixtures.
- [ ] Extract stable shared model/schema into released wayang-core artifacts.
- [ ] Keep ComponentRegistry, profile filtering, execution routing and local plugin lifecycle implementation in Wayang.
- [ ] Move canonical management/worker protocol sources to Core only after compatibility fixtures exist.
- [ ] Verify Wayang and Console against the same released Core compatibility matrix.

See [Shared Core boundary](SHARED-CORE.md) and https://github.com/andalus-ai/wayang-core.

## Deployment lifecycle increment (2026-11-11)

- [x] Add a dedicated engine-neutral `wayang-deployment` module rather than mixing deployment state with package verification or workflow-run execution.
- [x] Define explicit installed, activating, active, degraded, failed, rolling-back, rolled-back, deactivating, and deactivated states.
- [x] Add immutable deployment snapshots and append-only lifecycle event records with actor, reason, correlation ID, idempotency key, timestamp, and optimistic version.
- [x] Add a synchronized in-memory reference ledger and a state-transition service.
- [x] Add tests for activation/health separation, degraded and unhealthy outcomes, rollback, illegal transitions, stale versions, duplicate registration, and duplicate idempotency keys.

**Remaining:** durable transactional ledger, target adapter SPI and implementations, health-check execution/deadlines, secret/config binding, signed deployment attestation, approval policy, and Console orchestration. This increment records lifecycle facts only; it does not activate or roll back a real target.

**Verification:** tests are committed but not yet executed locally. CI is the verification authority for this PR. The in-memory ledger is not suitable for production or multi-node deployments.
