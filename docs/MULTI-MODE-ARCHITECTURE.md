# Multi-Mode Platform Architecture

> Decision status: product direction agreed; implementation is incremental. This document defines the target architecture. It does not claim that every mode, engine adapter, deployment target, or edition entitlement is implemented.

## 1. Product definition

Wayang is one extensible low-code platform for authoring, composing, validating, compiling, packaging, and operating intelligent automation and integration solutions.

Wayang offers specialized modes that tailor the authoring experience to a domain. Modes are not separate products, codebases, workflow engines, or deployment topologies.

| Mode | Purpose | Primary authoring experience |
|---|---|---|
| Agent Builder | Build agents, tools, knowledge/RAG, multi-agent and event-driven AI workflows | Agent-oriented visual graph |
| Integration / ESB | Build enterprise integrations and Enterprise Integration Patterns (EIP) | Endpoint, route, processor and message-flow designer |
| Business Automation | Model governed business processes and decisions | BPMN process modeler and DMN decision editor |
| Intelligent Business Automation | Compose agents, business processes, decisions, data and integrations into end-to-end business solutions | Cross-domain orchestration workspace |
| Creative Content | Future generative image, audio, video and multimodal pipelines | Media graph and preview-oriented designer |

The fourth mode is Intelligent Business Automation, not traditional Business Intelligence. It combines reasoning, process governance and enterprise integration. A future analytics/BI experience may be added separately if it has a distinct product scope.

Creative Content is a future extension, not a launch dependency.

## 2. Architectural invariants

1. One platform, multiple modes: shared foundations and specialized domain experiences.
2. Neutral core: the core understands generic authoring and extension contracts, not every domain's business semantics.
3. Domain fidelity: BPMN remains BPMN, DMN remains DMN, EIP remains EIP, and agent/media graphs retain their own semantics. Never flatten a domain document if that loses meaning.
4. Specialized designers: reuse editor infrastructure where useful, but provide mode-specific designers, inspectors, palettes, validation and previews.
5. Capability Packs: domain functionality is contributed by versioned packs containing schemas, components/nodes, connectors, validators, editor contributions, runtime requirements and adapters.
6. Independent engines: execution engines are selected behind stable adapters. Wayang coordinates definitions and composition; it does not reimplement every engine.
7. Portable definitions: a project authored in Community can be released to a compatible Pro/Enterprise runtime without redesigning its logical definition.
8. Compile before deploy: deployment artifacts are immutable, versioned, dependency-resolved and reproducible.
9. Minimal runtime: a generated runtime contains only the engines, packs, connectors and dependencies required by its artifact.
10. Management is not execution: runtime execution must not depend on Studio or the management plane being available.
11. Honest status: distinguish proposed architecture, partial implementation and verified behavior.

## 3. Modes, capability packs and deployment profiles

Do not conflate these concepts:

- Mode answers: what is the user building, and which authoring experience should Studio present?
- Capability Pack answers: which domain components, schemas, editors, validators, connectors and engine adapters are available?
- Deployment Profile answers: how is the compiled solution packaged and operated?

Examples: Agent Builder is a mode; the Agent capability pack contributes agent components. Kafka and HTTP packs contribute integration capabilities. BPMN and DMN are domain packs with native document semantics and dedicated editors. A local standalone runtime is a deployment target, not a mode.

A project may use more than one capability pack. Intelligent Business Automation is a composition experience over those packs, not a separate execution engine.

## 4. Platform layers and ownership

### 4.1 Studio and mode experiences

Wayang Studio owns project authoring and the mode manager. It provides mode discovery and enablement, mode-specific navigation/palettes/inspectors/designers, templates, capability-pack selection, validation, previews, testing, versioning, cross-domain composition and publish/release workflows.

A single canvas is not required for all domains. Reusable editor infrastructure is encouraged, but each domain must preserve its own document model and semantics.

### 4.2 Neutral Wayang core

The core provides reusable contracts and services: project and artifact metadata; node/component/port and typed input/output contracts; schema/configuration metadata; registry and capability discovery; validation and compatibility negotiation; expression/mapping integration points; extension lifecycle and permissions; document versioning/migration hooks; compilation/packaging; execution adapter and invocation contracts.

The core must not hard-code agent personas, Kafka topics, BPMN gateways, DMN hit policies, ERP concepts, diffusion models or GPU scheduling as universal primitives. Those belong to domain packs or their engines.

### 4.3 Capability Packs

A capability pack is a versioned distribution and compatibility boundary. Depending on its domain, it may contribute component/node descriptors and schemas; designer palette, inspectors and visualizations; domain parsers, validators and compilers; connectors, triggers and providers; engine adapters and executor requirements; runtime dependency metadata and resource requirements; examples, tests, migrations and documentation.

A node is the authoring representation; a component is an executable capability; a connector integrates an external system; a pack groups and versions related contributions. These terms are not interchangeable.

Prefer build-time inclusion for Quarkus/native-compatible deployments. Dynamic JVM plugins and remote workers are separate capabilities with explicit trust, compatibility and lifecycle rules. Never promise arbitrary hot loading in native images.

### 4.4 Domain engines and adapters

Wayang preserves domain semantics and delegates execution to appropriate engines through versioned adapters.

| Domain | Responsibility | Direction |
|---|---|---|
| Agent execution | Model calls, tools, memory, agent lifecycle and guardrails | Andalus adapter |
| Durable workflow orchestration | Durable runs, scheduling, retries, persistence and run lifecycle | Gamelan adapter |
| EIP / integration | Routes, endpoints, messaging, transformation and integration patterns | Reuse mature integration components where they fit; Apache Camel is a reference/candidate, not an assumed completed dependency |
| BPMN | BPMN process semantics and execution | Dedicated BPMN-compatible engine/adapter; evaluate conformance before selecting |
| DMN | Decision models and decision-table evaluation | Dedicated DMN implementation/adapter with explicit conformance |
| Creative media | Media graph execution, model loading and GPU/resource scheduling | Future specialized media engine/worker adapter |

Wayang owns authoring models, boundary validation, cross-domain composition, artifact generation and deployment integration. It must not create a competing durable workflow engine where Gamelan owns that responsibility.

Do not assume one engine must execute an entire mixed-mode project. Composition can cross engine boundaries through explicit invocation, event, data and lifecycle contracts.

## 5. Cross-mode composition

Intelligent Business Automation is a first-class Studio experience composing existing domain capabilities. It is not a separate generic runtime.

Example procurement flow:
1. An integration endpoint receives an ERP purchase request.
2. A BPMN process starts and owns the governed business lifecycle.
3. An agent evaluates unstructured context and returns a typed recommendation.
4. DMN evaluates deterministic approval policy.
5. A human task obtains required approval.
6. An integration route updates the ERP and emits an outcome.
7. The process records the decision and completion.

Cross-domain boundaries specify typed inputs/outputs, identity and tenant context, correlation, deadlines, cancellation, idempotency, error mapping, security policy and observability propagation. Avoid implicit shared in-memory state between engines.

## 6. Two-phase product lifecycle

Wayang has two distinct phases.

### Phase 1 — Studio authoring

Users design and manage projects in Wayang Studio. Authoring includes project metadata, domain documents, schemas, capability-pack dependencies, configuration references, validation, tests, drafts and published versions.

Phase 1 produces an authoring project and, after publish/compile, a deployment artifact.

### Phase 2 — Deployment and execution

A published project is compiled into an immutable, reproducible artifact. The compiler resolves exact pack/component/engine dependencies, validates compatibility, emits a manifest and dependency lock, records checksums/provenance, and includes configuration/secret references (never secret values).

Phase 2 runtimes must not require Studio to execute. A runtime receives only the capabilities required by its artifact.

| Target | Meaning |
|---|---|
| Standalone local runtime | Minimal runtime package/process for a local machine or edge host |
| Microservice runtime | Independently deployable services, with service discovery and horizontal scaling where supported |
| Cloud-native runtime | Container/orchestrated deployment, including Kubernetes where supported, with workload scaling and operational integration |

These are deployment targets, not different workflow semantics. The same published definition retains its meaning across compatible targets.

A deployment manifest records artifact identity/digest, project/document versions, Wayang compatibility, required packs and engine adapters, dependency lock, configuration schema, secret references, resource requirements, topology requirements and provenance.

## 7. Editions and deployment entitlements

The agreed packaging direction is:

| Edition | Product shape | Deployment entitlement |
|---|---|---|
| Community | Studio and execution together in one all-in-one runtime | Phase 1 plus integrated local execution; no separated production runtime deployment, distributed scaling or HA orchestration |
| Pro | Studio/design environment separated from generated runtime artifacts | Phase 2 standalone/local and production microservice deployment, with supported Pro operational capabilities |
| Enterprise | Separated Studio, runtime and management/control-plane deployment | Phase 2 distributed/cloud-native deployment, advanced service discovery, horizontal scaling, HA, governance and enterprise operations |

Community remains useful without an external control plane, service discovery, cluster or paid infrastructure. It can support complete solutions within its all-in-one execution model. Do not artificially cripple its core authoring, schemas, node contracts or plugin APIs.

Pro and Enterprise use the same authoring definitions and core contracts as Community. Their value is production packaging, separation, operations, distribution and scale—not a forked definition language.

The edition direction is a product/architecture decision. Exact licensing, feature entitlements, packaging boundaries and delivery status are tracked separately in EDITIONS.md. Do not mark proposed capabilities implemented merely because this target architecture describes them.

## 8. Control plane, execution plane and scaling

For separated deployments, keep responsibilities explicit:

- Design plane: Studio, project authoring, validation, publishing and artifact compilation.
- Management/control plane: runtime inventory, desired/observed deployment state, service discovery integration, configuration/policy distribution, approvals, governance and fleet operations. Wayang Console remains a separate repository/product.
- Execution plane: generated runtimes, engine adapters and workers.

The management plane may deploy, observe and govern runtimes, but is not in the synchronous execution path. A Console/control-plane outage must not stop an already-running runtime.

Distributed execution requires more than service discovery: durable state where required, capability-aware placement, health/heartbeats, backpressure, idempotency, fencing/coordination, graceful drain, retry ownership, cancellation, recovery, tracing and explicit data-residency/security policies.

Gamelan owns durable workflow lifecycle and retries for workflows delegated to it. A component router or worker transport must not introduce a second uncontrolled retry loop.

## 9. Future Creative Content mode

Creative Content is intentionally future-facing. It may add image, audio, video, diffusion and multimodal pipeline authoring, asset management, model catalogs, media previews and GPU-aware execution.

It reuses neutral project, pack, schema, artifact and execution contracts, while introducing its own media graph model, designer and specialized runtime/worker requirements. GPU scheduling, model residency, large binary artifacts and streaming outputs must not be forced into BPMN or the generic workflow engine.

No Creative Content feature is a prerequisite for the initial modes.

## 10. Delivery sequence

1. Foundation: reconcile contracts; stabilize project/artifact and pack contracts; define mode metadata and document-model boundaries; keep compiler/runtime seams compatible.
2. Agent Builder: complete the first end-to-end authoring experience using agent, RAG, storage, trigger and runtime capabilities.
3. Integration / ESB: deliver the integration designer and EIP/connector coverage through reusable components.
4. Business Automation: deliver BPMN/DMN authoring and compatible engine adapters with explicit conformance scope.
5. Intelligent Business Automation: prove cross-domain composition, typed boundaries, governance and end-to-end observability.
6. Phase 2 packaging: harden immutable artifacts and minimal standalone runtimes for Pro; then distributed/cloud-native execution and HA for Enterprise.
7. Creative Content: explore later as a domain pack, designer and specialized execution ecosystem.

Build Phase 2 foundations during Phase 1; do not wait until Studio is complete to discover its project format cannot compile into an independent runtime.

## 11. Architecture review checklist

Every proposed feature or PR should answer:
- Which mode(s) does it serve?
- Is it neutral core functionality or a domain-specific capability pack?
- Does it preserve native domain semantics and document models?
- Which designer/editor contribution is required?
- Which engine owns execution, durable state, retries and lifecycle?
- What adapter and versioned contracts cross the boundary?
- What dependencies are included in a generated runtime?
- Does the artifact run without Studio or Console?
- Does the feature work in Community's all-in-one model?
- What additional Pro/Enterprise deployment capabilities does it require?
- What are its security, idempotency, recovery and observability requirements?
- What is implemented, proposed and actually verified?

If these questions cannot be answered, the architecture is not ready for implementation.
