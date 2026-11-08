# Component and Plugin Contracts

> Status: contract foundation; execution placement and dynamic loading are not implemented by this increment.

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

## Execution modes

| Mode | Meaning |
|---|---|
| `EMBEDDED` | Same process as the Wayang runtime |
| `ISOLATED_LOCAL` | Separate local process or container |
| `REMOTE` | Independently registered worker service |
| `CLOUD_NATIVE` | Managed worker pool or orchestrated workload |

These are capability declarations, not placement commands. A future deployment compiler/router must
intersect declared modes with policy, worker health, runtime compatibility, resource availability,
security zone, and data locality.

## Execution state

| Model | Meaning |
|---|---|
| `STATELESS` | No component-owned state across invocations |
| `EXTERNALIZED` | State is persisted outside the worker |
| `AFFINE` | State is local to an execution slot; placement requires a declared affinity key |

## Current Java contract

`ComponentExecutionCapabilities` is an additive descriptor associated with an exact component ID/version.
Keeping it separate avoids changing the existing `ComponentDescriptor` record constructor and preserves
source compatibility for existing extensions.

It declares supported modes, state behavior, payload portability, cancellation and idempotency declarations,
required worker capabilities, resource hints, and optional affinity key. Remote/cloud modes require portable
payloads; affine state requires an affinity key.

`ComponentExecutionCapabilityRegistry` rejects unknown components, duplicate declarations, and version
mismatches. Components without a declaration retain legacy embedded behavior; absence of metadata never
implies remote support.

## Plugin discovery and lifecycle boundary

The existing `WayangExtension` + `ServiceLoader` mechanism is startup/classpath discovery. It remains supported
for build-time and packaged JVM extensions. The extension SPI now has an additive default method for declaring
component execution capabilities; existing implementations need not change.

Runtime installation, hot upgrade, and hot uninstall require a separate lifecycle manager and must not be inferred
from this mechanism. Dynamic class loading is a JVM-only optional strategy; Quarkus native/build-time extensions
use build-time inclusion or remote workers. Safe unload requires draining active invocations and closing
plugin-owned resources.

## Remote execution requirements (future contract)

Before a component can execute remotely, its protocol contract must define versioned language-neutral serialization,
invocation/run/correlation identifiers and trace context, deadlines, cancellation, bounded payload size, idempotency
and duplicate-delivery behavior, error taxonomy and retry safety, worker registration/health/capability negotiation,
graceful drain, state externalization or affinity, authentication, authorization, encryption, and audit.

Component routing must not become a second workflow engine. Gamelan (or the selected engine) remains responsible
for durable workflow lifecycle, retries, and run state.

## Example declaration

```java
var capabilities = new ComponentExecutionCapabilities(
    "wayang.extension.document-extractor", "1.2.0",
    Set.of(ComponentExecutionMode.EMBEDDED, ComponentExecutionMode.REMOTE),
    ComponentStateModel.STATELESS, true, true, false, null,
    Set.of("document.extract"), Map.of("memory", "512Mi"));
```

Illustrative plugin manifest (future package format; not parsed by the runtime yet):

```yaml
apiVersion: wayang.ai/v1
kind: Plugin
metadata:
  id: wayang.plugin.document
  version: 1.2.0
spec:
  provides:
    components:
      - id: wayang.extension.document-extractor
        version: 1.2.0
  execution:
    modes: [embedded, remote]
    state: stateless
    portablePayloads: true
```
