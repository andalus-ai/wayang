# Project Nessie CEL-Java qualification spike

This is a standalone Maven project and is intentionally **not** registered in
the Wayang parent reactor. It pins the upstream CEL-Java BOM at 0.8.0.

## Run

From this directory, with JDK 21 and Maven:

```bash
mvn -B -ntp clean verify
```

## Current probes

- Custom environment literal evaluation and isolation checks.
- Undeclared variable and function rejection.
- Explicit custom function registration through CEL-Java's `Library`.
- Checker declaration without runtime implementation produces a CEL error.
- Wrong argument type is rejected by static checking.
- Pure function evaluation for positive, negative, zero, and signed 64-bit minimum values.
- Repeatability across repeated evaluations.
- Versioned function descriptor validation and compatibility fingerprints.
- Explicit allowlist behavior, duplicate overload rejection, builder freeze, and
  expression-safe profile rejection of non-pure/non-deterministic functions.

## Function governance

See [FUNCTION-REGISTRY-CONTRACT.md](FUNCTION-REGISTRY-CONTRACT.md) for the
provider-neutral Wayang function descriptor, identity, effect/determinism policy,
declaration/runtime parity, fingerprinting, versioning, and security contract.

The current Java classes are a qualification spike, not yet a public Wayang API.
Do not wire them into the parent reactor or production runtime until the adapter,
compatibility, resource, and recovery gates are qualified.

## Explicitly not proven

The spike does not prove:
- hard CPU or memory budgets;
- preemptive cancellation that stops CPU and allocation work;
- bounded input or result materialization;
- exact runtime overload-ID to descriptor parity across multiple overloads;
- durable compiled-artifact recovery across process/provider versions;
- Quarkus native-image compatibility;
- production-grade tenant isolation or concurrency admission.

A timeout around a worker thread is not hard cancellation unless the worker is
guaranteed to stop and release resources. CEL's non-Turing-completeness and
heuristic cost estimates do not prove bounded CPU or memory use.

## Promotion rule

Keep the provider isolated until mandatory resource governance, exact artifact
compatibility, concurrency isolation, and security review have executable evidence.
A successful `mvn verify` validates only this spike; it does not qualify a
production provider.
