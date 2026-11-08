# Shared Core boundary

## Status

Architecture decision and migration plan. This document does not claim that Core artifacts have been published or that Wayang has migrated to them.

## Repository ownership

| Repository | Owns |
|---|---|
| [wayang-core](https://github.com/andalus-ai/wayang-core) | Shared component/plugin contracts, portable schemas, versioning, management/worker protocols, SDK/testkit foundations |
| This repository | Workflow model and semantics, Studio/compiler, validation, release packaging, runtime integration, component execution, local plugin lifecycle and runtime Management Agent |
| [wayang-console](https://github.com/andalus-ai/wayang-console) | Standalone fleet/control plane, runtime registry, central plugin catalog/approval/distribution, deployment orchestration, audit, digital twin and Console UI |

Wayang Core is a library and contract source, not a runtime service. Wayang must remain fully usable without Console. Console must remain fully usable without Wayang's workflow execution libraries.

## What should move to Core

Move only contracts with multiple consumers or external SDK value:
- Component identity, kind, descriptor, ports, dependencies, capability and compatibility metadata.
- Plugin identity, manifest model/schema, contribution inventory, permission declarations and API compatibility.
- Public plugin authoring interfaces that are independent of a particular host implementation.
- Language-neutral management protocol and worker invocation schemas.
- Shared error codes, versioning rules, golden fixtures and contract testkit.

## What must stay in Wayang

- BPMN, DMN, EIP, agent and human-task workflow semantics.
- Workflow graph, validation, compiler, profile selection and release compiler.
- Component registry implementation and execution router.
- Local plugin discovery/loading, process/container worker adapters and runtime lifecycle.
- Quarkus integration and runtime Management Agent.
- Studio-specific authoring APIs and UI.

## What must stay in Console

- Runtime enrollment services, fleet registry and persistence entities.
- Plugin catalog persistence, approval policy, distribution/rollout orchestration.
- Deployment state machine, command queue/delivery, reconciliation and audit.
- OIDC/operator authorization, Console REST resources and Console UI.

Console may consume Core descriptors and schemas, but it must not import Wayang's execution engine or load plugin classes.

## Current state found during repository review

Wayang already has:
- ComponentDescriptor, ComponentRegistry, ComponentExecutor and related catalog abstractions.
- WayangExtension and ServiceLoader startup discovery.
- Component/plugin contract documentation and a manifest JSON Schema in the runtime resources.
- Component execution capability metadata and profile-aware component resolution.

The existing contract documentation explicitly says runtime package parsing, signature verification, dependency installation, placement, and dynamic loading are not yet implemented. ServiceLoader is startup/classpath discovery, not a hot-install manager.

Wayang Console already has:
- Its own runtime registry, capability/workflow inventory, deployment and command domain/application/persistence slices.
- A runtime management protobuf currently located at contracts/proto/wayang/management/v1/management.proto.
- A standalone backend that does not include the Wayang workflow execution engine.

These are the baseline to extract from, not reasons to create duplicate APIs in Core.

## Migration sequence

1. Add characterization tests and golden JSON/protobuf fixtures for current Wayang and Console contracts.
2. Inventory each type and classify it as shared contract, Wayang implementation, Console implementation, or obsolete.
3. Extract stable component/plugin model and schemas into Core first.
4. Publish experimental versioned artifacts and a BOM; do not make consumers depend on unversioned source branches.
5. Migrate Wayang first, retaining deprecated compatibility facades/package adapters where needed.
6. Move the canonical management protobuf source to Core and have Console and Wayang generate/consume from the same versioned contract.
7. Add cross-repository contract compatibility tests and a release matrix.
8. Migrate Console to the shared model/protocol without moving Console persistence or application services.
9. Remove duplicated definitions only after both consumers have migrated and compatibility is proven.

## Important compatibility rule

Do not wholesale move classes or rename Java packages before tests capture current serialization and API behavior. A type being used in both repositories does not automatically mean its entire implementation belongs in Core. Keep server entities, REST DTOs and internal domain aggregates local unless they are explicitly part of a public cross-process contract.

See [Wayang Core architecture](https://github.com/andalus-ai/wayang-core/blob/main/docs/ARCHITECTURE.md), [module strategy](https://github.com/andalus-ai/wayang-core/blob/main/docs/MODULE-STRATEGY.md), and [Core roadmap](https://github.com/andalus-ai/wayang-core/blob/main/docs/ROADMAP.md).
