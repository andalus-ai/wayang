# Component Registry and Runtime Selection

## Status

This document describes the first implementation of the runtime component metadata SPI.
It is not yet a full release compiler, marketplace, plugin sandbox, or Studio catalog API.

## Design boundary

Wayang already discovers executable extensions through `ServiceLoader<WayangExtension>`.
That contract remains the executable extension boundary. Component descriptors add a
metadata-only catalog alongside it:

- `WayangExtension.components()` declares the components contributed by an extension.
- `Extensions.components()` collects metadata from extensions already accepted by the
  selected runtime profile.
- `ComponentRegistry` provides an immutable catalog and dependency-closure resolver.
- `DomainHandler` and existing connector/poller/webhook interfaces remain responsible
  for execution.

A descriptor does not load a class, instantiate a handler, fetch an artifact, or run
untrusted code. The registry is therefore safe to use for catalog inspection and
preflight validation, but it is not a plugin sandbox.

## Component descriptor

Each component has a stable namespaced ID, an exact version, a kind, an executor type,
a description, required feature families, exact component dependencies, an isolation
classification, and capability labels.

Example (Java):

```java
new ComponentDescriptor(
    "wayang.eip.http-trigger",
    "1.3.0",
    ComponentDescriptor.Kind.TRIGGER,
    "wayang-eip",
    "HTTP ingress trigger",
    Set.of(Profile.Feature.EIP),
    List.of(new ComponentDescriptor.Dependency("wayang.connector.http", "2.1.0")),
    ComponentDescriptor.IsolationLevel.IN_PROCESS,
    Set.of("workflow.trigger", "http.ingress"));
```

Dependency versions are exact in this first increment. This is intentional: range
parsing and semantic-version conflict resolution belong in the release compiler and
must not be approximated by string comparison.

## Profile behavior

The registry filters descriptors whose required feature families are not enabled in
the target `Profile`. This complements the existing profile checks; it does not replace
them. A workflow still needs execution-plan validation against the profile.

## Resolution behavior

`resolve(ids)` returns a deterministic dependency-first list. It fails closed for:

- unknown or profile-disabled component IDs;
- missing dependencies;
- dependency version mismatches;
- dependency cycles;
- duplicate component IDs in one registry.

The returned set is suitable as an input to a later package compiler. It is not yet
a Maven/Quarkus artifact dependency resolver and does not calculate transitive JAR
coordinates.

## Security and deployment implications

`IN_PROCESS` is metadata, not enforcement. It must only be used for trusted code.
Third-party or untrusted extensions need a separate process/container/remote execution
boundary with authenticated RPC, resource limits, and explicit permission scopes.
The runtime must never interpret a component descriptor as permission to execute
arbitrary shell commands or dynamically download code.

## Next increments

1. Add descriptors to built-in EIP, DMN, BPMN, connector, and agent providers.
2. Add JSON Schema and Studio presentation metadata without coupling runtime contracts
   to Flutter.
3. Add release-time artifact coordinates, compatibility constraints, lockfiles, SBOM,
   signatures, and reproducible packaging in `wayang-publish`.
4. Expose a read-only catalog API for Studio and the standalone Console.
5. Add process/container/remote plugin execution adapters; do not claim that the
   current isolation enum provides sandboxing.
