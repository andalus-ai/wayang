# Phase 4I — Evaluation boundary and compiled-artifact compatibility

Status: isolated implementation and executable probes. This is not a production
runtime API and does not establish hard CPU/memory guarantees.

## 1. Boundary responsibilities

The expression boundary is the only place that should admit source, activate a
compiled artifact, supply variables, evaluate, and validate a result.

Recommended order:

1. Authenticate and authorize the caller outside the expression engine.
2. Resolve tenant and policy from trusted request context, never expression input.
3. Apply source-byte admission before parsing.
4. Resolve the exact provider, environment, function registry, variable schema,
   compile-options, and policy versions.
5. Validate an existing artifact's complete compatibility envelope. On mismatch,
   reject or invoke an explicit migration workflow; never silently recompile during
   durable workflow recovery.
6. Validate activation shape and size before handing values to CEL.
7. Evaluate using a separately governed resource policy.
8. Validate result shape/size before returning it to the workflow runtime.
9. Record bounded diagnostics and compatibility metadata without exposing values
   or secrets.

The spike implements steps 3, 5, 6, and 8 as host-side metadata/validation helpers.
It does not yet provide authentication, tenant resolution, a production policy
engine, execution preemption, or durable AST/Program serialization.

## 2. Typed function binding and declaration parity

CelFunctionBinding no longer accepts caller-created opaque ProgramOption values.
Its unary/binary factories construct the CEL-Java runtime dispatcher registration
from the descriptor's exact overload ID and arity-specific function interface.

GovernedCelLibrary generates checker declarations from the same immutable
descriptors. Overloads sharing a source-level CEL function name are grouped into a
single checker declaration, while each runtime overload is installed with its own
exact overload ID.

The isolated adapter currently supports the primitive descriptor type names:
bool, bytes, double, int, null, string, uint, and dyn. Unsupported or custom type
names fail closed. Extend this mapping only alongside tests for the associated CEL
type-provider and value-conversion behavior.

Unary and binary runtime factories are implemented. Other arities are not exposed
until a dedicated typed factory and tests exist.

## 3. Input and output admission

ExpressionInputValidator accepts configurable limits for:

- UTF-8 source bytes;
- activation variable count;
- nesting depth;
- entries per list/map;
- total visited value nodes;
- individual string/byte-array bytes;
- total estimated encoded activation/result bytes.

Supported host activation values are deliberately narrow: null, booleans, boxed
numeric primitives, BigInteger, BigDecimal, strings, byte arrays, lists, and maps
with string keys. Cyclic graphs and arbitrary host objects are rejected.

validateVariables and validateResult share the same recursive bound model.
The result method must be applied to the provider-adapted native result before it
crosses the expression boundary.

These are admission/materialization-shape limits, not a hard heap quota. They do
not account for object headers, retained references, provider internals, allocations
that happened before validation, or execution work. Avoid describing estimated
encoded bytes as exact JVM memory consumption.

## 4. Artifact envelope

CompiledExpressionArtifact is a versioned immutable metadata envelope containing:

- schema version;
- exact source and source SHA-256;
- expected result type and semantic profile;
- provider ID and exact provider version;
- function registry compatibility fingerprint;
- environment fingerprint;
- variable schema fingerprint;
- compile-options fingerprint;
- policy version;
- aggregate artifact fingerprint.

The aggregate fingerprint uses length-prefixed UTF-8 fields, avoiding delimiter
ambiguity. Constructor validation recomputes source and artifact digests.

requireCompatible checks every compatibility dimension and reports the first
mismatched field with a stable exception type. It intentionally does not expose
the compared values in the exception message.

Important: this envelope does not serialize the CEL Ast, executable Program,
provider class state, or runtime implementation. It is a durable compatibility
record to accompany a separately defined portable source/check artifact. A future
recovery implementation must load/compile only under exact validated compatibility
and must define explicit migration and rollback semantics.

## 5. Fingerprints are not security proofs

Fingerprints detect compatibility changes; they do not authenticate an artifact.
A production store still needs authenticated access, integrity protection/signing,
tenant-scoped authorization, retention/revocation controls, and trusted provenance.
Do not use a hash as a substitute for authorization or signature verification.

## 6. Remaining production gates

- Hard CPU/instruction and memory/allocation governance.
- Cancellation that proves work stops and resources are released.
- End-to-end evaluation deadlines and tenant concurrency admission.
- Output conversion/materialization bounds inside the provider adapter.
- Portable artifact storage, restart recovery, and explicit migration.
- Native-image and multi-process provider qualification.
- Security review for custom type adapters, custom functions, and host values.

A thread timeout or CEL cost estimate alone does not satisfy hard resource governance.
