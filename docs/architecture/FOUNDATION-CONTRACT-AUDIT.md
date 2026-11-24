# M1 — Foundation Contract Audit

> Status: **audit / proposed alignment**. This document records the current repository contracts inspected on 2026-11-24 and the changes recommended before expanding the Studio into multiple product modes. It does not claim that proposed contracts are implemented.

## 1. Purpose

Wayang is one low-code product with multiple authoring experiences:

- Agent Builder
- Integration / ESB
- Business Automation (BPMN / DMN)
- Intelligent Business Automation (cross-domain composition)
- Creative Content (future)

The modes must reuse a neutral project, document, schema, component, artifact and deployment foundation without forcing all domains into one graph language or execution engine.

Keep three independent concepts separate:

1. **Studio product mode** — which designer and domain tools the user is working with.
2. **Server operating mode** — `WAYANG_MODE=community|design`; whether the design server may execute.
3. **Runtime deployment topology** — Community all-in-one; Pro standalone/local and microservice; Enterprise distributed/cloud-native.

Neither `WAYANG_MODE` nor `WAYANG_PROFILE` is the Studio product-mode selector. A runtime profile selects enabled feature families, not the semantic type of a project.

## 2. Repository baseline inspected

The audit inspected the current `main` source and the existing architecture/release/profile documentation.

| Area | Existing implementation | Assessment |
|---|---|---|
| Project | `Project` and tenant-scoped `ProjectStore` exist | Useful workspace root, but definition ownership is indirect |
| Definition documents | `StoredDefinition`, `DefinitionStore`, draft revision CAS, immutable published version numbers | Good versioning foundation; document envelope and deep immutability need strengthening |
| Definition kinds | Fixed enum: `PROCESS`, `DECISION`, `AGENT`, `PIPELINE` | Supports current domains but is not an extension-friendly type registry |
| Studio metadata | `studio.projectId`, layout, tags, UI metadata are embedded in each document and stripped by `Studio.executable` | UI/execution separation is valuable; project association should become first-class |
| Process graph | `ProcessModel`, `Element`, `SequenceFlow`, `ProcessCompiler` | A BPMN-oriented acyclic graph with explicit validation; must not become the universal graph model |
| Node palette | Static `NodeCatalog` keyed by BPMN `ElementType`; schemas describe config and connection counts | Current palette is process-centric, not a general multi-mode node/port registry |
| Runtime components | `ComponentDescriptor`, `ComponentRegistry`, plugin manifests, component config schema and UI hints | Strong runtime component catalog foundation |
| Process-to-component mapping | `ComponentRequirements` maps BPMN `ElementType` values to component IDs | Useful adapter seam, but mapping is still process-specific |
| Execution capabilities | `ComponentExecutionCapabilities` declares modes, state model, portability, cancellation, idempotency, affinity and resource hints | Metadata contract exists; it explicitly does not schedule, authorize, load or route execution |
| Runtime profiles | `WAYANG_PROFILE` and feature gates support full, automation, integration, agentic and custom profiles | Useful packaging/runtime filter; not a Studio mode registry |
| Release pipeline | Published definitions, component lock, artifact lock, target-specific generated projects and selective connector wiring | A real Phase 2 foundation; generated targets and their deployment guarantees still require target-specific verification |
| Domain engines | BPMN/process, DMN, agent, EIP and Gamelan/Andalus adapters are separate modules | Correct direction: preserve domain and engine ownership |

Relevant implementation locations:

- `wayang-api-core/.../api/project/Project.java`, `ProjectStore.java`
- `wayang-api-core/.../api/definition/StoredDefinition.java`, `DefinitionStore.java`, `DefinitionKind.java`, `Studio.java`, `NodeCatalog.java`
- `wayang-business-process/.../bpmn/ProcessModel.java`, `Element.java`, `ProcessCompiler.java`
- `wayang-runtime/.../runtime/ComponentDescriptor.java`, `ComponentRequirements.java`, `ComponentExecutionCapabilities.java`, `ComponentExecutionCapabilityRegistry.java`
- `docs/ARCHITECTURE.md`, `docs/EDITIONS.md`, `docs/PROFILES.md`, `docs/RELEASE.md`

## 3. Main gaps and risks

### 3.1 Project ownership is implicit

Today a definition associates to a project through `studio.projectId` inside the document. `StoredDefinition` and the `DefinitionStore` operations do not expose a first-class project reference. This creates avoidable risks:

- project deletion/rename and definition membership are not governed by one explicit contract;
- listing and authorization by project require interpreting document metadata;
- imports, exports and future cross-project references need extra conventions;
- a document can be saved without a project association.

**Direction:** retain the UI-only `studio` metadata for layout and editor preferences, but make tenant/project ownership part of definition metadata and store operations. Enforce tenant + project membership at the persistence boundary. Define explicit behavior for project deletion (reject while non-empty, archive, or cascade only by explicit operation).

### 3.2 Definition kind is closed

The enum is a reasonable built-in vocabulary, but new capability packs cannot safely contribute a new document kind without changing the core enum and branching code.

**Direction:** use a namespaced, versioned document type identifier (for example `wayang.process/v1`, `wayang.agent/v1`, `wayang.integration.pipeline/v1`) resolved through a document-kind/schema registry. Keep built-in constants and compatibility parsing for existing values. A pack may register schemas, validators, migrations and compiler adapters; it must not silently register arbitrary executable code from an uploaded document.

### 3.3 One palette currently describes BPMN elements

`NodeCatalog` is keyed by `ElementType` and describes BPMN config schemas and incoming/outgoing connection counts. That is right for the current process editor, but it is not enough for:

- agent nodes with tools, memory, model and structured input/output;
- integration nodes with endpoint, message, delivery and EIP semantics;
- data/storage nodes reusable across workflows and RAG pipelines;
- media nodes with large asset inputs, model and GPU requirements;
- business decision and human-task nodes with their own contracts.

**Direction:** keep the BPMN `NodeCatalog` as a BPMN-specific catalog. Add a neutral, extension-contributed **Node/Port Descriptor** registry for designer palettes and graph validation. Domain packs provide their own descriptors and graph semantics.

### 3.4 Config schema is not a data-port contract

`ComponentDescriptor.configSchema` describes configuration fields. It does not define typed graph inputs/outputs, connection compatibility, cardinality, nullability, streaming behavior or the distinction between an absent value and a configured value.

Do not overload JSON Schema `required` for both configuration and graph data inputs.

**Required input behavior (important):** a node input marked mandatory should normally produce a visible diagnostic when it is unconnected or resolves to null; it must not automatically prevent the user from saving, testing or publishing the entire workflow. Each port needs an explicit validation/presence policy. A true execution precondition may be blocking, but advisory missing-input diagnostics are the default for ordinary data ports. At runtime, the component may return a typed missing-input outcome if it cannot proceed.

### 3.5 Component descriptors and graph-node descriptors are different

A runtime component is an implementation/deployable unit. A graph node is a domain-level authoring construct. They may map one-to-one, one-to-many, or be implemented by a composite.

**Direction:** do not make the component catalog the only Studio palette. A node descriptor references one or more implementation requirements, while retaining its own domain semantics, ports, editor hints and validation behavior.

### 3.6 Execution capability metadata is not distributed execution

The execution-capability registry validates declarations for exact component versions. The code comments explicitly state that this metadata does not choose workers or load plugins. The profile documentation also says there is no placement router, worker protocol or dynamic plugin installer yet.

**Direction:** treat `EMBEDDED`, `ISOLATED_LOCAL`, `REMOTE` and `CLOUD_NATIVE` as declarations only. Before Enterprise routing is implemented, add a versioned invocation protocol, capability advertisement, authenticated registration, lease/fencing, readiness, bounded dispatch, cancellation, backpressure and graceful drain. Do not infer remote execution from a descriptor.

### 3.7 Published document immutability needs a deep boundary

`StoredDefinition` wraps the top-level document map as unmodifiable. Nested maps/lists may still be mutable if callers retain references. Published versions are described as immutable, so the persistence boundary must defensively copy or canonicalize the complete JSON tree on write and on read.

**Direction:** add deep JSON immutability/copying and tests that mutate nested source and returned values. This should be addressed before content-addressed artifacts, signatures or cross-service caching rely on document immutability.

## 4. Target contract model

The following is the recommended direction, not an assertion about current code.

### 4.1 Project / workspace

A project is the durable workspace boundary:

- `tenantId`, `projectId`, name, description, owner/audit metadata;
- optional default Studio experience and enabled authoring capabilities;
- references to one or more domain documents;
- project-level variables, environment/configuration references and policy bindings;
- explicit lifecycle (active, archived) and deletion rules.

A project may contain multiple document kinds and participate in Intelligent Business composition. Do not constrain a project to exactly one product mode.

### 4.2 Versioned domain document envelope

Use a stable envelope with a domain-owned `spec`:

```json
{
  "apiVersion": "wayang.agent/v1",
  "kind": "Agent",
  "metadata": {
    "tenantId": "tenant-a",
    "projectId": "customer-support",
    "id": "support-agent",
    "name": "Support Agent",
    "labels": {}
  },
  "spec": {},
  "studio": {
    "layout": {},
    "tags": []
  }
}
```

The example is illustrative. Preserve backward compatibility for existing `PROCESS`, `DECISION`, `AGENT` and `PIPELINE` documents through explicit adapters/migrations. Domain `spec` schemas are registered by packs. Studio metadata remains excluded from executable compilation.

### 4.3 Node and port descriptor

A neutral descriptor should include:

- stable namespaced node type and descriptor version;
- owning domain/mode and category;
- display metadata and editor/UI hints;
- typed input and output ports (schema/type reference, cardinality, nullability, streaming/batch semantics);
- explicit presence/validation policy per port;
- configuration schema, separate from data ports;
- graph constraints and domain validator identifier;
- implementation requirement(s), component version range and execution capabilities;
- resource hints, security classification and documentation.

Illustrative shape:

```json
{
  "type": "wayang.rag.retrieve",
  "version": "1.0.0",
  "domain": "agent",
  "inputs": [
    {
      "id": "query",
      "type": "text",
      "required": true,
      "presencePolicy": "ADVISORY"
    },
    {
      "id": "knowledge",
      "type": "wayang.knowledge.collection",
      "required": true,
      "presencePolicy": "ADVISORY"
    }
  ],
  "outputs": [
    { "id": "documents", "type": "wayang.document.list" }
  ],
  "configSchema": {
    "type": "object",
    "properties": {}
  },
  "implementation": {
    "component": "wayang.rag.retrieve",
    "version": "^1.0"
  }
}
```

A missing mandatory input should be shown in the editor diagnostics. It is blocking only when the descriptor explicitly says `BLOCKING` or a domain validator establishes a hard execution precondition.

### 4.4 Capability pack and designer extension

A capability pack should be independently versioned and declare:

- pack ID/version and compatible Wayang contract/API versions;
- supported document kinds and schema versions;
- node descriptors, validators and migrations;
- designer contributions (palette groups, forms, inspectors, specialized editors);
- runtime component requirements and dependency closure;
- engine adapters and supported execution modes;
- permissions, secrets/configuration requirements and resource needs;
- compatibility and deprecation policy.

The design-time contribution must not require loading runtime implementation code into the Studio server. The runtime artifact includes only the selected pack's required execution dependencies.

## 5. Contract ownership boundaries

| Contract | Owner | Must not own |
|---|---|---|
| Project and document envelope | Wayang neutral core | BPMN/agent/EIP semantics |
| Domain schema and graph validation | Domain capability pack | Generic runtime placement |
| Node/port descriptor | Wayang descriptor SPI + domain pack | Concrete engine state |
| Component descriptor and implementation | Runtime component SPI | Studio project lifecycle |
| Execution capability declaration | Runtime SPI | Actual worker scheduling |
| Artifact/dependency lock | Publisher/compiler | Runtime execution state |
| Durable process state/retries/timers | Selected workflow engine (Gamelan for delegated durable workflows) | Duplicate retry loops in adapters |
| Agent execution | Andalus adapter or explicitly selected agent invoker | BPMN process lifecycle |
| Endpoint/EIP delivery | Integration engine/connector | Agent memory or BPMN state |
| Studio product mode | Studio mode registry | `WAYANG_MODE` server operating switch |

## 6. Recommended M1 implementation sequence

Do not replace all current models in one migration. Introduce compatible seams in this order:

1. **Document immutability and envelope compatibility**
   - deep-copy/canonicalize JSON documents;
   - add schema/API version metadata with a legacy adapter;
   - test immutable published snapshots.
2. **First-class project association**
   - add project reference to stored definition metadata and persistence SPI;
   - migrate from `studio.projectId`;
   - enforce tenant/project membership and define delete semantics.
3. **Extensible document-kind registry**
   - preserve existing built-in kinds;
   - register schema, validator, migration and compiler adapter per kind;
   - reject unknown kinds with a precise diagnostic.
4. **Neutral node/port descriptor SPI**
   - retain `NodeCatalog` as the BPMN catalog;
   - add descriptor registry and typed ports;
   - implement advisory versus blocking missing-input diagnostics.
5. **Capability-pack and designer contribution contract**
   - define pack manifest, version compatibility and Studio extension metadata;
   - do not yet implement dynamic remote plugin installation.
6. **Artifact contract alignment**
   - bind project/document versions, pack versions, component versions and dependency closure into a deterministic manifest/lock;
   - preserve Community all-in-one behavior and Pro/Enterprise target-specific packaging.

## 7. Acceptance criteria for the foundation

- A project can contain a BPMN process, DMN decision, agent, integration pipeline and reusable storage/knowledge resources without conflating their document schemas.
- Existing published documents remain readable and executable through a compatibility adapter.
- A document cannot be attached to a project in another tenant.
- A project deletion cannot silently orphan definitions.
- Nested mutation cannot alter a stored or published document.
- The BPMN designer continues to use BPMN semantics and its existing catalog.
- A capability pack can contribute a new node descriptor without adding a new constant to a central BPMN enum.
- A node can declare typed required inputs; missing input is visible as an advisory diagnostic by default and only blocks when explicitly configured as a hard precondition.
- A component config schema is validated separately from its data ports.
- A component's remote/cloud capability declaration is not treated as proof that a worker router exists.
- Community still runs the integrated all-in-one path; Pro/Enterprise releases remain independently deployable and include only the dependencies resolved for that release.
- Every capability is labelled implemented, partial, proposed or verified with the evidence level stated.

## 8. Out of scope for this M1 audit

- Selecting a final BPMN engine or claiming full BPMN 2.0 conformance.
- Implementing Enterprise service discovery, worker placement or HA.
- Dynamic installation of arbitrary third-party code.
- Forcing all graph types into `ProcessModel` or `ExecutionPlan`.
- Moving Gamelan, Andalus or Iket responsibilities into Wayang's neutral core.
- Changing Community edition into a distributed deployment.

The next implementation slice should be **M1.1 — immutable, versioned document envelope**, followed by **M1.2 — first-class project/document ownership**. These changes are prerequisites for safe multi-mode authoring and deterministic Phase 2 artifact generation.
