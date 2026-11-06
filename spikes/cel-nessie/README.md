# Project Nessie CEL-Java qualification spike

This is a standalone Maven project and is intentionally **not** registered in
the Wayang parent reactor. It pins the upstream CEL-Java BOM at 0.8.0 and uses
exactly one generated protobuf runtime artifact.

## Run

From this directory, with JDK 21 and Maven installed:

```bash
mvn -B -ntp clean verify
```

## Current executable probes

- A custom environment compiles and evaluates a Boolean literal.
- A custom environment rejects an expression requiring undeclared standard
  operator overloads.
- An expression referencing an undeclared variable is rejected during
  compilation, before evaluation.

These are baseline isolation/type-check probes, not a claim of production
qualification.

## Explicitly not proven

The spike does not yet prove:
- a hard evaluation cost/instruction budget;
- cancellation that stops CPU and allocation work;
- bounded input or result materialization;
- deterministic custom function registration and overload parity;
- serialized artifact compatibility across process restarts or engine versions;
- Quarkus native-image compatibility.

The public `Program.eval(Object)` API has no cancellation token. A timeout
around a worker thread is not considered hard cancellation unless the worker
is guaranteed to stop and release resources. Do not expose this adapter to
untrusted production expressions based on these baseline tests.

## Promotion rule

This project must remain outside the normal reactor until the mandatory
resource-governance and artifact-recovery gates are demonstrated. A successful
`mvn verify` here only validates the spike itself; it does not qualify the
provider for production.
