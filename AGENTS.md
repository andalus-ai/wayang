# Repository Agent Instructions

These instructions apply to the entire `andalus-ai/wayang` repository. Follow them in addition to any more specific `AGENTS.md` files in subdirectories.

## Product and ownership boundaries

- Wayang owns workflow definitions, composition, validation, compilation, component contracts, packaging, Studio-facing APIs, local runtime integration, and its Management Agent.
- Gamelan owns durable workflow execution, scheduling, retries, persistence, and run lifecycle. Do not implement a competing durable workflow engine in Wayang.
- Andalus owns agent execution, models, tools, memory, and agent lifecycle. Integrate through adapters and stable contracts.
- Wayang Console is a separate product/repository: `andalus-ai/wayang-console`. Do not fold its central control-plane server or fleet-management UI into this repository.
- Numou (`andalus-ai/numu`) remains an independent platform. Use optional, versioned adapters only for capabilities with verified contracts. Wayang must remain usable without Numou.

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
- Keep root README concise and link to focused documents such as `docs/ROADMAP.md`, `docs/ARCHITECTURE.md`, and `docs/PROFILES.md`.
