# Custom function registration qualification

Status: source/API inspection; executable qualification is still pending.

## Purpose

Determine whether the isolated CEL-Java 0.8.0 spike can safely expose a Wayang-owned,
pure function without turning arbitrary Java methods into CEL capabilities.

This is a spike only. It does not add CEL to the Wayang runtime, change FEEL, or
qualify the provider for production.

## API facts verified against Project Nessie cel-java 0.8.0 source

- A declaration is created with `Decls.newFunction(name, overloads...)`.
- An overload declaration uses `Decls.newOverload(id, argumentTypes, resultType)`.
- Runtime implementations are supplied through `ProgramOption.functions(Overload...)`.
- `Overload.function(operator, FunctionOp)` accepts a `FunctionOp`.
- `FunctionOp.invoke(Val... values)` is the invocation boundary.

Declarations and implementations are separate. A declaration alone does not implement a function;
runtime registration alone does not make a function statically declared. Qualification must
test both together and test each failure mode.

## Proposed minimal qualification fixture

Use a single pure function with one CEL integer argument and CEL boolean result:

`wayang_is_even(int) -> bool`

The implementation must:
1. Accept exactly one integer CEL value.
2. Return a CEL boolean, not a Java Boolean or an unchecked object.
3. Have no access to workflow services, tenant context, clocks, files, network, or mutable state.
4. Be deterministic for the same input.
5. Avoid arithmetic overflow; parity can be evaluated with remainder.
6. Convert unexpected input or implementation exceptions into CEL errors rather than throwing
   across the evaluator boundary.

## Required tests before qualification

| Case | Expected |
|---|---|
| expression without declaration | compile/check rejection |
| declaration without runtime overload | compile succeeds; evaluation produces CEL error |
| declaration and matching runtime overload | evaluation produces CEL boolean |
| even, odd, zero, negative input | stable expected booleans |
| repeated evaluation of same input | identical result |
| wrong argument type / arity | compile rejection or CEL error; never host exception escape |
| runtime overload signature mismatch | explicit error; no coercion surprise |
| function omitted from approved registry | unavailable |

## Registration policy

The production-facing registry must be explicit and allowlisted. Function names and overload IDs
are stable identifiers; they are not arbitrary Java method names. Each registered function
needs a versioned descriptor recording argument/result CEL types, purity, determinism,
and implementation version. Only functions marked pure and deterministic may enter the
expression evaluation profile.

Do not use reflection-based discovery or expose all methods of a service. Side effects belong
in workflow nodes and must remain outside CEL evaluation.

## Qualification boundary

This source/API inspection establishes the registration mechanism, not these properties:
- hard CPU or memory budget;
- preemptive cancellation;
- bounded result size;
- durable serialized-program recovery;
- native-image compatibility;
- production-grade error normalization.

The existing Nessie qualification matrix remains authoritative for those unresolved gates.
Do not promote the provider or claim bounded execution until each gate has executable evidence.

## Run

From this directory, with JDK 21:

```bash
mvn -B -ntp clean verify
```

Execution has not been performed in this environment.