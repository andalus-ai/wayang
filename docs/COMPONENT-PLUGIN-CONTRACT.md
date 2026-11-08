# Component and Plugin Contracts

> Status: contract foundation. Runtime package parsing, signature verification, dependency installation, placement, and dynamic loading remain future work.

## Existing foundation

Wayang already has `ComponentDescriptor`, `ComponentRegistry`, `ComponentExecutor`,
`ComponentExecutorCatalog`, `WayangExtension`, and `Extensions`. This contract extends
those abstractions; it does not replace them or claim that `ServiceLoader` supports hot installation.

## Vocabulary

- **Component:** versioned functional capability used by a workflow.
- **Node:** workflow-facing typed/configurable representation of a component.
- **Connector:** integration implementation for an external system or protocol.
- **Plugin/extension:** package and lifecycle boundary contributing one or more components, connectors, handlers, triggers, schemas, or UI metadata.
- **Worker:** process, container, or service hosting executable components.
- **Execution mode:** location in which a component implementation can execute.

A plugin is not synonymous with one node. One plugin can contribute many components.

## Execution modes and state

| Mode | Meaning |
|---|---|
| `EMBEDDED` | Same process as the Wayang runtime |
| `ISOLATED_LOCAL` | Separate local process or container |
| `REMOTE` | Independently registered worker service |
| `CLOUD_NATIVE` | Managed worker pool or orchestrated workload |

| State model | Meaning |
|---|---|
| `STATELESS` | No component-owned state across invocations |
| `EXTERNALIZED` | State is persisted outside the worker |
| `AFFINE` | State is local to an execution slot; placement requires a declared affinity key |

These are capability declarations, not placement commands. A future deployment compiler/router must intersect declared modes with policy, worker health, runtime compatibility, resources, security zone, and data locality.

## Execution capability contract

`ComponentExecutionCapabilities` is an additive descriptor associated with an exact component ID/version. It declares supported modes, state behavior, payload portability, cancellation and idempotency declarations, required worker capabilities, resource hints, and optional affinity key.

Remote/cloud modes require portable payloads; affine state requires an affinity key. `ComponentExecutionCapabilityRegistry` rejects unknown components, duplicate declarations, and version mismatches. Components without a declaration retain legacy embedded behavior; absence of metadata never implies remote support.

The `WayangExtension.executionCapabilities()` default method lets existing extensions remain source-compatible while new extensions contribute declarations.

## Plugin manifest contract

`PluginManifest` is the validated in-memory model for plugin identity, semantic version, plugin API version, publisher, runtime compatibility expression, provided component inventory, plugin dependencies, permissions, and license metadata.

`PluginManifestValidator` checks that the descriptors supplied by an extension/package exactly match the manifest's declared component IDs and versions. It does not parse YAML/JSON, evaluate dependency version ranges, authenticate publishers, or verify signatures. Signature and artifact provenance verification remain a separate trust-boundary responsibility.

The JSON Schema at `wayang-runtime/src/main/resources/schema/wayang-plugin-manifest.schema.json` defines the initial interchange shape. The schema and Java model are contract foundations; package installation is not implemented.

## Plugin discovery and lifecycle boundary

The existing `WayangExtension` + `ServiceLoader` mechanism is startup/classpath discovery. It remains supported for build-time and packaged JVM extensions.

Runtime installation, hot upgrade, and hot uninstall require a separate lifecycle manager. Dynamic class loading is a JVM-only optional strategy; Quarkus native/build-time extensions use build-time inclusion or remote workers. Safe unload requires draining active invocations and closing plugin-owned resources.

## Remote execution requirements (future contract)

Before remote execution, define versioned language-neutral serialization, invocation/run/correlation identifiers and trace context, deadlines, cancellation, bounded payload size, idempotency and duplicate-delivery behavior, error taxonomy and retry safety, worker registration/health/capability negotiation, graceful drain, state externalization or affinity, authentication, authorization, encryption, and audit.

Component routing must not become a second workflow engine. Gamelan (or the selected engine) remains responsible for durable workflow lifecycle, retries, and run state.
