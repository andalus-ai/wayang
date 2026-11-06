# ADR: CEL engine selection and provider qualification

- Status: Proposed; engine selection intentionally deferred
- Date: 2026-11-06
- Scope: `wayang-cel` provider implementation
- Decision owner: Wayang maintainers

## Context

Wayang needs a Java CEL implementation behind `CelCompilerProvider` and
`CelEvaluatorProvider`. The engine must not leak vendor parser, AST, type,
activation, or runtime classes into Wayang's public contracts.

The repository targets Java 21. Existing `wayang-expression` is a separate
FEEL-flavoured language and remains the default for existing definitions.
Selecting a dependency based only on API convenience would be premature:
the engine must satisfy language semantics, deterministic compilation,
bounded evaluation, function isolation, operational cancellation and the
repository's distribution constraints.

## Decision

Do not select or add a concrete CEL engine in this increment.

Instead, require every candidate to pass the qualification gates below and
run the same Wayang-owned conformance suite. A candidate's advertised CEL
support or passing its own upstream tests is not sufficient evidence of
Wayang compatibility.

## Mandatory qualification gates

A candidate is ineligible if any mandatory gate fails.

1. **Java/runtime fit**
   - Supports Java 21 and the repository's supported Maven toolchain.
   - Does not require an incompatible runtime, agent, or classloader model.
   - Native-image support is documented as a separate compatibility dimension;
     it is not assumed from ordinary JVM compatibility.
2. **CEL semantics**
   - Provides parse and static type-check phases, not evaluation-only parsing.
   - Supports the required CEL language version and conformance behavior.
   - Distinguishes compile diagnostics, missing variables, explicit null,
     unknown values (if enabled), type errors and evaluation errors.
   - Does not coerce non-Boolean condition results or errors into false.
3. **Function isolation**
   - Supports explicit function registration and overload control.
   - Does not expose arbitrary Java reflection, class lookup or ambient host APIs.
   - Allows Wayang to make deterministic/pure functions the default and keep
     external effects in workflow nodes.
4. **Resource governance**
   - Provides enforceable evaluation cost/instruction limits or a defensible
     equivalent.
   - Supports bounded input/result sizes and interruption/cancellation.
   - Does not rely only on an HTTP/request timeout around unbounded execution.
5. **Determinism and artifact lifecycle**
   - Compilation can produce a stable, serializable or reproducibly rebuildable
     artifact with explicit engine/version metadata.
   - Runtime can evaluate the exact provider version pinned by the artifact.
   - Engine upgrades cannot silently reinterpret or recompile deployed artifacts.
6. **Security and maintenance**
   - License is compatible with Wayang's intended distribution.
   - Dependency tree, vulnerability response, release cadence and maintenance
     ownership are acceptable.
   - No unsupported claims about sandboxing are accepted without tests.

## Candidate evidence: Project Nessie cel-java

Initial upstream review on 2026-11-06 identified [Project Nessie cel-java](https://github.com/projectnessie/cel-java) as the strongest candidate found so far. The upstream README documents Maven coordinates through the `org.projectnessie.cel` BOM, currently showing version `0.8.0`, and recommends `cel-tools` with exactly one generated protobuf runtime. It describes:

- Script construction with declarations and a build/check phase.
- Custom function libraries and overload declarations.
- A lower-level custom environment API for hosts requiring a narrower function/macro set.
- A CEL-Spec conformance test suite.
- Java 21+ to build and Java 17+ to run.
- Apache-2.0 licensing.

Primary evidence: [upstream README](https://github.com/projectnessie/cel-java), [upstream repository/license](https://github.com/projectnessie/cel-java).

Important limitations from the same upstream documentation and review:
- `ScriptHost` enables the standard library by default; stricter environments require lower-level environment construction.
- Arbitrary Java classes are documented as unsupported, but Wayang must still test its exact environment and function surface.
- Native-image/package compatibility must be tested in the consuming application.
- CEL's non-Turing-complete design is useful but is not equivalent to a host-enforced hard instruction/cost budget. The reviewed Java README does not establish a Wayang-usable hard evaluation cost budget, hard result-size enforcement or cancellation contract. Those mandatory gates remain unverified.
- A reusable in-memory `Script` is documented; a stable serialized artifact format and cross-process/version compatibility are not established by the reviewed README.

### Preliminary gate assessment

| Gate | Preliminary status | Reason |
|---|---|---|
| Java 21 build / Java 17+ runtime | Evidence found | Upstream build/runtime requirements documented |
| Parse and static checking | Evidence found | Script builder uses declarations and a build/check phase |
| Custom function registration | Evidence found | Library and overload APIs documented |
| Restricting standard functions | Partial | Lower-level custom environment exists; default ScriptHost includes standard library |
| No arbitrary Java class access | Evidence found | Explicitly documented upstream |
| CEL-Spec conformance | Evidence found, needs Wayang rerun | Upstream maintains conformance suite; Wayang must run its required profile |
| Hard cost / instruction budget | Unverified | No adequate enforcement evidence in reviewed README |
| Cancellation / interruption | Unverified | No supported cancellation contract established |
| Stable serialized artifact compatibility | Unverified | Reusable Script documented; durable artifact format not established |
| Apache-2.0 | Evidence found | Upstream repository identifies Apache-2.0 |
| Quarkus native-image | Unverified | Consumer-side verification required |

**Decision: do not select yet.** This is a promising JVM adapter candidate, but mandatory resource-governance evidence is missing. A separate qualification capability contract has now been added to the Wayang SPI: compiler/evaluator providers default to unqualified, and production resolution can fail closed unless required gates are declared. These declarations must be backed by tests, not treated as proof. The next step remains a time-boxed adapter spike and adversarial tests. If hard limits and cancellation cannot be enforced, the candidate must remain non-production or be rejected.

## Candidate scorecard

The scorecard must be completed with primary evidence before selecting a
provider. Use 0 (fails), 1 (partial/workaround), or 2 (verified), and attach
links to upstream documentation, source, license and reproducible tests.

| Dimension | Weight | Evidence required |
|---|---:|---|
| CEL language/conformance | Mandatory | Version, conformance suite results, edge-case behavior |
| Static type checking | Mandatory | Type environment and diagnostic tests |
| Function allowlisting | Mandatory | Custom functions, overloads, no ambient Java access |
| Cost and resource limits | Mandatory | Runtime-enforced budget tests |
| Java 21 compatibility | Mandatory | Build and runtime CI on Java 21 |
| Artifact/version pinning | High | Serialization, version metadata, compatibility behavior |
| Cancellation | High | Interrupt/cancel tests under expensive expressions |
| Native-image compatibility | Medium | Separate native build and execution proof |
| License/distribution | Mandatory | Exact license and transitive dependency review |
| Maintenance/security | High | Release history, advisories, active maintenance |

No score is assigned in this ADR because candidate-specific evidence has not
been collected and verified in this repository change. This avoids presenting
an unsupported ranking as an engineering decision.

## Wayang-owned conformance requirements

The provider qualification job must include:

- Boolean true and false as distinct successful values.
- Explicit null as a successful `NullValue`; missing variables as a distinct
  failure status.
- Type mismatch and unknown behavior according to the selected semantic
  profile.
- Nested list/map values, decimal numeric equality and byte-array equality.
- Exact provider id/version matching for the artifact.
- Exact fixture scope and artifact fingerprint matching.
- Function allowlist and overload resolution.
- Budget exhaustion, cancellation and result-size limits.
- Stable diagnostic codes; no source snippets, credentials or fixture secrets
  in operational telemetry.
- Repeated evaluation determinism for the deterministic function profile.

The shared runners cover evaluator result matching, provider pinning, artifact fingerprint and fixture scope checks, plus compiler outcome and artifact-provenance checks. The registry now exposes fail-closed qualified resolution based on explicit provider capability declarations. These are qualification controls, not a substitute for provider-specific proof. Resource enforcement and cancellation remain to be demonstrated by a real adapter.

## Consequences

- `wayang-cel` remains free of a mandatory third-party CEL runtime dependency.
- Engine-specific dependencies belong in a dedicated provider module, not in
  the engine-neutral API module.
- Provider module metadata and deployment manifests must pin exact engine
  versions.
- A provider is not production-qualified until all mandatory gates and
  Wayang-owned conformance tests pass.
- Existing FEEL definitions and runtime behavior are unaffected.

## Follow-up

1. Build an isolated spike against Project Nessie cel-java 0.8.0 using lower-level custom environment APIs.
2. Prove enforceable evaluation budgets, cancellation, bounded results and deterministic function allowlisting; do not infer these from CEL's non-Turing-complete property.
3. Verify artifact persistence/version compatibility and dependency/native-image behavior in the actual Wayang/Quarkus build.
4. Only after the gates pass, implement a dedicated provider module with no vendor types in public Wayang APIs and declare only tested capabilities.
5. Run the shared compiler/evaluator suites plus engine-specific adversarial/resource tests in CI.
