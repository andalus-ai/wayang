# CEL in Wayang

## Status and relationship to the existing expression language

Wayang already ships wayang-expression, a bounded FEEL-flavoured language used by BPMN conditions, DMN, EIP routing and mappings. It has its own semantics, parser, interpreter, three-valued behavior and tests. CEL is an additional language integration, not a replacement. Existing workflows keep their current language and behavior unless a workflow revision explicitly opts into CEL.

This document consolidates the CEL design series 3B.2.6.1–3B.2.6.21 into one implementation map. The new wayang-cel module begins with engine-neutral contracts. No concrete CEL engine/provider is selected by this change.

## Ownership

- Wayang owns expression references in canonical definitions, expected result contracts, dataflow-derived scope, fixtures, preview/test APIs, regression suites, change impact and publication evidence.
- A CEL provider owns vendor-specific parse/check/evaluate details behind CelCompilerProvider and CelEvaluatorProvider.
- The artifact subsystem owns immutable artifact storage, canonical fingerprints, signature/provenance verification, lifecycle, deployment pins and revocation. This initial module does not pretend to implement that persistence layer.
- Gamelan owns durable workflow execution, retries, timeouts, cancellation, checkpoints and recovery. CEL evaluation returns a typed outcome; it does not schedule retries.
- Andalus remains CEL-agnostic. No CEL-specific API belongs in Andalus core.

## Provider registration and conformance (implementation increment 2)

The provider boundary now includes a deterministic registry:

- Providers are identified by the exact pair `providerId + providerVersion`.
- A provider is usable only when both compiler and evaluator SPIs are registered for that exact identity.
- Duplicate compiler or evaluator identities fail startup/registry construction; classpath ordering never selects a winner.
- A compiler-only or evaluator-only identity fails registry construction and reports the unmatched identities.
- Artifact resolution must request the exact pinned provider id and version. There is no implicit latest-version or fallback behavior.
- Java `ServiceLoader` discovery is available through `CelProviderRegistry.discover(ClassLoader)`; explicit construction remains available for tests and controlled embedding.
- Provider health output exposes only stable id/version/status codes, not implementation class names or configuration.

The registry validates provider wiring, not provider correctness. Each concrete engine adapter still needs a shared semantic conformance suite covering type checking, evaluation semantics, missing/null/unknown handling, diagnostics, resource limits, cancellation, and deterministic behavior. No concrete engine is selected by this increment.

## Evaluator conformance runner (implementation increment 3)

The testing package now provides an evaluator conformance runner and immutable report:

- Each test case is bound to an exact compiled artifact fingerprint.
- Each fixture must use the scope fingerprint pinned by that artifact.
- The evaluator must match the exact provider id/version pinned by the artifact.
- The runner constructs evaluation context from the fixture and artifact metadata; fixtures cannot override environment identity.
- Expected successful values and expected failure statuses remain distinct. A successful Boolean false is not an error and is never conflated with a failed evaluation.
- Provider exceptions and null result objects fail the conformance job instead of being converted into ordinary CEL evaluation failures.
- Reports expose case IDs, pass/fail, stable status names and diagnostic codes.

This is evaluator-side infrastructure only. It does not yet test compilation, parser/type-check conformance, actual cost enforcement, cancellation, custom function isolation or an actual third-party engine. See [CEL engine qualification ADR](CEL-ENGINE-ADR.md); engine selection is deliberately deferred until candidate evidence is collected.

## Module map

| Area | Responsibility | Initial status |
|---|---|---|
| wayang-expression | Existing FEEL-style expression language | Existing; preserve |
| wayang-cel | Engine-neutral CEL value, compiler/evaluator and test contracts | Added in this branch |
| CEL engine provider | Concrete parser/checker/runtime adapter | Not implemented; engine selection required |
| CEL artifact store | Immutable payload/manifest, signing, lifecycle, tenant partitioning | Design specified; persistence not implemented |
| Workflow integration | Language tag, expected type, scope derivation, result mapping | Design specified; IR integration required |
| Preview and simulation | Typed fixtures, isolated evaluation, offline node simulation | Contracts started; service/API/UI not implemented |
| Regression management | Versioned suites, baselines, dependency graph, impact analysis, gates | Design specified; persistence and services not implemented |

## Consolidated capability plan

1. API and semantics: recursive type contracts; missing vs explicit null; typed values; stable diagnostics; explicit Boolean condition semantics.
2. Function registry: approved, versioned, capability-scoped functions; pure/deterministic functions by default; external writes remain workflow nodes.
3. Compiler/evaluator providers: vendor-neutral SPIs; backend-specific AST payloads stay private; provider and artifact compatibility are explicit.
4. Artifacts: immutable manifests and payloads; canonical fingerprints; signatures/provenance; tenant isolation; lifecycle and revocation; no silent recompilation during recovery.
5. Definition integration: language-tagged expression references; expected result type; scope built from canonical workflow dataflow; branch/merge/loop availability; typed node result mapping.
6. Preview: same semantics and approved functions as the pinned target artifact; typed synthetic fixtures; strict limits; no production side effects or credentials.
7. Simulation: explicitly supported nodes only; fixture-backed stubs; bounded graph traversal; unsupported/live-only nodes are reported, not faked.
8. Test management: immutable suite revisions; explicit value/failure expectations; pinned fixtures, artifacts and dependency fingerprints.
9. Regression: canonical dependency graph; direct/transitive impact analysis; affected-only and full-suite execution; immutable baselines and reports.
10. Publication: regression evidence is one admission input; it cannot override artifact revocation, security, compatibility or deployment-admission requirements.
11. Operations: bounded concurrency, cancellation/isolation, sanitized telemetry, retention, conformance and release qualification.

## Semantics invariants

- CEL false is not an evaluation error.
- Explicit CEL null is a successful value; missing is a distinct status.
- A failed evaluation never returns a partial value.
- A condition succeeds only when the typed result is Boolean true or false. Non-Boolean, null, unknown (if enabled), and errors follow explicit policy; they are never silently treated as false.
- Function registration is allowlisted and versioned. No arbitrary reflection, Java method access, filesystem, network or secret access.
- Runtime retries and cancellation remain Gamelan responsibilities.
- Preview and tests use trusted server-side identity and authorization, not fixture-provided tenant/principal/capabilities.
- Artifact identity includes semantic and provider dependencies; runtime never silently recompiles a pinned artifact.

## Integration sequence

A. Contracts: add the engine-neutral module and closed value/result/context contracts. Keep dependencies at zero until a concrete CEL backend is selected.

B. Provider and conformance: select an engine after evaluating Java compatibility, CEL conformance, type-checking, custom function controls, resource-cost support, cancellation, native-image constraints and license. Implement provider adapter and shared conformance suite. Vendor types stay private.

C. Artifact lifecycle: add canonical manifest/payload storage, integrity and optional signing, lifecycle transitions, tenant partitioning, deployment pins and revocation. Recovery resolves the exact artifact.

D. Workflow integration: extend expression references with language, expected result type and artifact pin. Build scope from the actual Wayang process/DMN/EIP graph. Preserve FEEL default behavior and reject unsupported CEL node contracts at validation time.

E. Preview and deterministic testing: add typed fixture validation, preview API, test suite runner, deterministic function profile and explicit assertion comparator. Preview cannot invoke live connectors or agents.

F. Simulation and regression: add node simulation capability SPI, fixture-backed stubs, suite revisions, baselines, dependency graph, impact analysis and publication evidence.

G. Studio and release gates: add Flutter fixture/test/preview UX; qualify API, engine, artifact recovery, resource controls, tenant isolation and CI integration.

## Repository alignment decisions

- The repository currently targets Java 21 (maven.compiler.release=21), not Java 17. New code follows the repository's actual Java 21 baseline.
- Existing expression behavior is FEEL-flavoured and already used by workflow modules. Do not globally switch existing definitions to CEL.
- The repository README describes Flutter Studio as consolidated under studio/. Do not introduce a separate CEL console.
- wayang-cel is a separate module, so existing modules do not acquire a mandatory CEL runtime dependency.
- The repository verification report says the Maven build has not been run and Quarkus/Gamelan live integrations are not verified. This work must not overstate those claims.

## Not implemented by this initial consolidation

Concrete engine adapter, artifact database/signatures, workflow IR integration, Quarkus REST endpoints, Flutter screens, test-suite persistence, dependency graph extraction, simulation scheduler and publication gate are follow-on implementation increments. These contracts are a foundation, not a claim that all 21 design phases are production-complete.
