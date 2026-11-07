# Custom function registration qualification

Status: executable registration fixture added; local Maven execution remains pending.

## Purpose

Determine whether the isolated CEL-Java 0.8.0 spike can expose a Wayang-owned,
pure function without turning arbitrary Java methods into CEL capabilities.

This is a spike only. It does not add CEL to the Wayang runtime, change FEEL, or
qualify the provider for production.

## Verified public API

The preferred composition boundary is CEL-Java's `Library` interface:

- `getCompileOptions()` supplies declarations and other compile-time options.
- `getProgramOptions()` supplies runtime implementation options.
- `Library.Lib(library)` adapts the library into an `EnvOption`, installing both
  compile-time and program-time configuration.
- `Env.newCustomEnv(...)` creates an environment without implicitly installing
  the standard CEL function and macro library.
- `Decls.newFunction(name, overloads...)` declares a function.
- `Decls.newOverload(id, argumentTypes, resultType)` defines a global overload contract.
- `ProgramOption.functions(Overload...)` installs runtime overloads.
- `Overload.unary(overloadId, UnaryOp)`, `Overload.binary(overloadId, BinaryOp)`,
  and the corresponding arity-specific factories register fixed-arity functions.
  `Overload.function(overloadId, FunctionOp)` is the generic varargs form. The
  dispatcher key is the checker overload ID, not the source-level function name.
  For a one-argument function, use `Overload.unary`; registering it as
  `Overload.function` does not satisfy the planner's unary dispatch path.

The lower-level declaration and runtime APIs remain available, but a reusable Wayang
function bundle should implement `Library` so compile-time and runtime halves cannot
be accidentally configured in unrelated call sites.

## Executable fixture

`NessieCustomFunctionTest` adds the first executable fixture:

`wayang_is_even(int) -> bool`

The fixture verifies:
1. The function is unavailable in an empty custom environment.
2. The declared and implemented function evaluates for positive, negative, zero,
   even, and odd values.
3. Repeated evaluation of the same immutable program/input yields the same result.
4. The fixture is installed explicitly through `Library.Lib(...)`.

The implementation only reads the CEL integer argument and returns a CEL boolean.
It does not access workflow services, tenant context, clocks, files, network, or
mutable state. Parity uses remainder and is safe for the signed 64-bit minimum value.

## Registration policy

The production-facing registry must be explicit and allowlisted. Function names and
overload IDs are stable identifiers; they are not arbitrary Java method names. Each
registered function needs a versioned descriptor recording argument/result CEL types,
purity, determinism, and implementation version. Only functions marked pure and
deterministic may enter the expression evaluation profile.

Do not use reflection-based discovery or expose all methods of a service. Side effects
belong in workflow nodes and must remain outside CEL evaluation.

## Remaining qualification gates

This fixture does not establish:
- behavior for deliberately unchecked/malformed ASTs or host-level runtime misuse;
- hard CPU or memory budgets;
- preemptive cancellation;
- bounded result size;
- durable serialized-program recovery;
- native-image compatibility;
- production-grade error normalization under arbitrary extension failures.

CEL's non-Turing-completeness is not evidence of a hard CPU or memory budget.
Do not promote the provider or claim bounded execution until each gate has executable
evidence in the consuming application.

## Run

From this directory, with JDK 21:

```bash
mvn -B -ntp clean verify
```

Execution has not been performed in this environment.
