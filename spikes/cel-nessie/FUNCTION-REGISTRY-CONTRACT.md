# Wayang CEL function registry contract

Status: design contract plus executable metadata/registration probes. Not production-qualified.

## 1. Purpose and boundary

Wayang owns the expression capability catalog. The CEL provider only adapts that
catalog into its checker declarations and runtime dispatch table.

The contract is deliberately provider-neutral. A future CEL implementation must
consume the same descriptor semantics; provider-specific types and registration
objects must not leak into Wayang's public function identity.

This does not change the existing FEEL expression implementation. CEL remains an
opt-in profile and does not add CEL behavior to Andalus core.

## 2. Function identity

A callable overload is identified by the tuple:

`(functionId, functionVersion, overloadId, implementationVersion)`

- `functionId`: stable Wayang capability identity, namespaced by owner/domain.
- `functionName`: CEL source-level function name.
- `functionVersion`: semantic contract version, changed when behavior or accepted
  meaning changes.
- `overloadId`: stable identity for one exact argument/result signature.
- `argumentTypes` / `resultType`: canonical CEL type names, ordered as declared.
- `effect`: PURE, READ_ONLY, or EXTERNAL_SIDE_EFFECT.
- `determinism`: DETERMINISTIC, CONTEXTUAL, or NON_DETERMINISTIC.
- `implementationVersion`: version of the concrete implementation, independently
  of the semantic function contract.

The descriptor is immutable and validates identifier/version shape at construction.
Its SHA-256 compatibility fingerprint covers all semantic fields and implementation
version. It deliberately excludes Java class names, registration order, classloader
identity, and mutable state.

Changing implementation version changes the fingerprint even if the declared
signature remains unchanged. This is intentional: durable compiled artifacts must
not silently recover against a different implementation.

## 3. Eligibility profiles

The default expression-safe profile admits only:

- `effect = PURE`
- `determinism = DETERMINISTIC`

READ_ONLY is not automatically safe: a read can vary with tenant data, authorization,
time, external state, or transaction snapshot. CONTEXTUAL and NON_DETERMINISTIC
functions are excluded from this profile.

Future profiles may allow contextual functions only when the evaluation context is
explicit, immutable, versioned, and included in the evaluation fingerprint. Such
support requires a separate security and recovery decision; it must not be inferred
from this metadata model.

External side effects must be represented as workflow nodes/tools, not expression
functions. No function should receive a service locator, dependency-injection
container, workflow engine, tenant resolver, filesystem, network client, clock,
random source, or mutable global registry.

## 4. Registration and fail-closed behavior

The registry is an explicit allowlist, not reflection scanning.

- Duplicate overload IDs are rejected.
- Unknown overload lookup is rejected.
- The builder freezes after build.
- An expression-safe profile rejects READ_ONLY, EXTERNAL_SIDE_EFFECT,
  CONTEXTUAL, and NON_DETERMINISTIC descriptors.
- Registration order must not affect the registry fingerprint.
- No default or "best effort" overload is selected when a version/signature is absent.

The CEL adapter must install compile-time declarations and runtime overloads from
the same contribution set. CEL-Java's `Library` abstraction is a suitable adapter
boundary because it composes EnvOptions and ProgramOptions, but the Wayang descriptor
remains the authority for identity and policy.

The spike now includes `CelFunctionBinding` and `GovernedCelLibrary`. The latter
requires exactly one binding for every registry descriptor, rejects unregistered or
mismatched descriptors, and exposes immutable compile/program option lists.

A source inspection of CEL-Java's `Dispatcher` and `InterpretablePlanner`
revealed two important details:

1. `Dispatcher.add` indexes each runtime `Overload` by `Overload.operator`, while
   checked calls first resolve using the checker reference's overload ID.
   `CelFunctionBinding` therefore carries an explicit `runtimeOverloadId` and
   rejects a mismatch before options are installed.
2. The planner selects an arity-specific implementation slot. A one-argument
   function must use `Overload.unary(overloadId, UnaryOp)`; using
   `Overload.function(overloadId, FunctionOp)` does not populate the unary slot
   and fails when the planner creates the call. Binary/ternary/etc. functions must
   use their matching factories; generic FunctionOp is for the generic varargs path.

The ProgramOption itself remains opaque after construction, so metadata validation
cannot prove that arbitrary caller-supplied options contain the claimed overload.
The production adapter should construct ProgramOptions internally from typed
arity-specific runtime bindings rather than accept arbitrary opaque options.

## 5. Declaration/runtime parity

For each descriptor, qualification must compare:

| Contract field | Checker declaration | Runtime implementation |
|---|---|---|
| Function name | exact | exact dispatch name |
| Overload ID | exact | dispatch binding must resolve to that overload |
| Arity | exact ordered count | reject any mismatch |
| Argument types | exact | validate CEL Val type before conversion |
| Result type | exact | return the declared CEL Val type |
| Function version | descriptor | implementation binding metadata |
| Implementation version | compatibility metadata | exact binding version |

A declaration without a runtime implementation is not executable. A runtime
implementation without a checker declaration is not callable from a checked
expression. Both failure modes must be covered by tests. The executable spike now
uses the overload ID as the CEL-Java dispatcher key and rejects a descriptor/runtime
key mismatch at binding construction.

The current CEL-Java API's generic function dispatcher may resolve by function name
and argument types. The adapter must prove how overload IDs are associated with
runtime implementations; it must not assume that matching names alone prove parity.

## 6. Evaluation result semantics

The spike now includes `ConditionOutcome`, which maps CEL values into distinct
TRUE, FALSE, ERROR, UNKNOWN, NULL, NON_BOOLEAN, and HOST_FAILURE outcomes. Only
TRUE/FALSE carry a Boolean decision. Host failure diagnostics use a stable code and
do not expose exception messages.

The adapter must preserve distinctions among:

- successful Boolean false;
- successful Boolean true;
- CEL error value;
- unknown value;
- null value;
- host-level evaluation failure.

For conditions, only a CEL Boolean is a valid condition result. Error, unknown,
null, and non-Boolean values must be handled by an explicit policy at the Wayang
condition boundary. They must never be coerced to false.

Function implementations should return CEL values. Host exceptions must be mapped
to a stable, sanitized CEL/runtime error boundary without leaking secrets or internal
implementation details. The mapping policy must preserve diagnostic correlation IDs
outside user-visible expression values.

## 7. Fingerprints and durable compatibility

A compiled expression artifact must eventually record at least:

- normalized source and source digest;
- expected result type and semantic profile;
- CEL provider ID and exact provider version;
- function registry compatibility fingerprint;
- per-function semantic and implementation versions;
- environment and variable-schema fingerprint;
- compile/checker options and macro profile;
- relevant evaluation policy version.

Recovery must require exact compatibility or an explicit migration. It must not
silently recompile against a newer function implementation and continue a durable
workflow as though semantics were unchanged.

The registry fingerprint is not an authorization token, signature, or proof of
artifact integrity. Authentication, authorization, artifact signing, revocation,
and compatibility are separate controls.

## 8. Resource and security constraints

Purity and determinism do not imply bounded execution. Every function also needs a
resource contract before production use:

- maximum argument size and collection cardinality;
- maximum output size and materialization cost;
- maximum work/instruction estimate, if measurable;
- allocation behavior and recursion limits;
- cancellation and deadline semantics;
- concurrency and per-tenant admission limits.

A wall-clock timeout around a thread is not proof that CPU or allocation work has
stopped. CEL-Java's cost estimate is advisory unless a hard enforcement mechanism
is demonstrated. The current spike does not qualify hard CPU/memory budgets,
preemptive cancellation, bounded results, or durable recovery.

## 9. Versioning policy

- Patch: implementation correction with proven unchanged semantic contract; still
  changes implementationVersion and therefore the artifact fingerprint.
- Minor: additive overload or backward-compatible function capability; new overload
  receives a new overloadId.
- Major: changed meaning, argument/result contract, error behavior, or determinism/effect
  classification.
- Removed functions remain resolvable for old artifacts only while the corresponding
  compatibility bundle is retained. Otherwise recovery fails closed.

## 10. Current implementation in this spike

- `FunctionDescriptor`: immutable typed metadata and stable per-overload fingerprint.
- `FunctionRegistry`: explicit immutable allowlist, duplicate rejection, safe-profile
  gate, deterministic registry fingerprint.
- `CelFunctionBinding` / `GovernedCelLibrary`: exact descriptor-to-provider-binding
  composition, complete binding coverage, dispatcher-key validation, and immutable
  CEL-Java option lists. The executable one-argument fixture uses the arity-specific
  unary dispatch API.
- `ConditionOutcome`: strict condition-result classification that never collapses
  error/unknown/null/non-Boolean/host failure into Boolean false.
- `NessieCustomFunctionTest`: verifies missing declaration, declaration without
  implementation, typed runtime behavior, wrong argument type rejection, and repeatability.
- `FunctionRegistryTest`: verifies unknown/duplicate rejection, safe-profile rejection,
  fingerprint stability/version sensitivity, registration-order independence, zero-arity
  descriptor support, and builder freeze.
- `GovernedCelLibraryTest`: verifies missing binding, descriptor mismatch, and
  runtime dispatcher-key mismatch rejection.
- `ConditionOutcomeTest`: verifies true/false distinction, CEL error/unknown/null,
  non-Boolean and host-failure outcomes, and sanitized diagnostic codes.

These are executable probes, not production qualification. The adapter still needs
stronger proof that every runtime overload maps to the exact descriptor overload ID
and signature, plus error, resource, recovery, concurrency, and native-image tests.

## 11. Next qualification work

1. Build a provider adapter that consumes descriptor + implementation binding as one
   immutable contribution.
2. Verify declaration/runtime overload parity, including multiple overloads sharing
   a CEL function name.
3. Verify CEL error, unknown, null, and non-Boolean condition handling.
4. Add bounded host-side input/output validation.
5. Measure concurrency and isolate per-evaluation state.
6. Record exact provider/function fingerprints in the compiled artifact envelope.
7. Keep hard cancellation, hard budgets, and durable recovery blocked until proven.
