# CEL-Java qualification matrix (Phase 4E)

This matrix is the promotion checklist for the isolated Project Nessie CEL-Java
spike. It is evidence-oriented: a test passing in this spike is not, by itself,
a production qualification.

## Status vocabulary

- **Not tested**: no executable probe or source evidence recorded.
- **Probe**: an isolated test exists; result must be recorded from a real run.
- **Pass**: repeatable evidence exists for the stated bounded claim.
- **Blocked**: the public API or current design does not establish the required guarantee.
- **Not applicable**: explicitly excluded from the intended provider contract.

Do not convert a source/API observation into a runtime pass.

## Qualification gates

| Gate | Required evidence | Current status | Promotion consequence |
|---|---|---|---|
| Custom environment | Only explicitly declared identifiers/operators/functions compile | Probe: baseline tests in `NessieCustomEnvironmentTest`; execution not yet recorded | No promotion until CI/maintainer run |
| Function declaration/runtime parity | Every function has a checker declaration and runtime overload; signature, arity, types and dispatch agree | Not tested | Reject provider |
| Deterministic function profile | Functions are pure, deterministic, bounded, and have no ambient I/O/time/randomness | Not tested | Reject untrusted expressions |
| Missing function behavior | Unknown function fails compilation; no dynamic fallback | Not tested | Reject provider |
| Error mapping | Evaluation errors remain errors and are never coerced to false/null | Not tested | Reject provider |
| Cost estimation | Estimator behavior is measured on representative expressions; estimate is explicitly advisory | Not tested | Never call estimate a hard budget |
| Hard evaluation budget | Runtime stops work before configured CPU/instruction/allocation ceiling | Blocked by current public `Program.eval(Object)` contract; no hard guard demonstrated | Do not run untrusted production expressions |
| Cancellation | Cancellation demonstrably stops evaluation and releases worker/resources | Blocked / not demonstrated | Do not rely on thread interruption or timeout alone |
| Input bounds | Host validates maximum bytes, nesting, collection sizes and variable count before evaluation | Not tested | Reject oversized inputs before CEL |
| Result bounds | Host prevents or bounds materialization of oversized strings/lists/maps | Not tested | Reject provider for untrusted evaluation |
| Artifact recovery | Exact compiler/provider/environment/function versions survive restart; compatibility mismatch fails closed | Not tested | No durable workflow recovery promotion |
| Native image | Quarkus native build and runtime probe, including reflection/resource configuration | Not tested | No native-image claim |
| Concurrency | Parallel evaluations are isolated; shared Env/Program behavior and resource limits are measured | Not tested | No multi-tenant production promotion |
| Differential semantics | CEL-Spec conformance plus Wayang-specific semantic fixtures, stable diagnostics | Not tested | No FEEL replacement; CEL remains opt-in |

## Custom function probe plan

Implement the first custom function only after verifying the exact CEL-Java 0.8.0
API signatures against the pinned dependency/source. Use a tiny pure function with
an integer input and Boolean output (for example, an evenness predicate).

The probe must demonstrate:

1. Compilation fails when the function declaration is absent.
2. Compilation succeeds when the checker declaration is present.
3. Evaluation fails closed if the runtime overload is absent.
4. The declared and runtime signatures agree exactly.
5. Repeated calls with identical inputs produce identical values.
6. Negative values and boundary values are covered.
7. The function does not access clocks, random generators, network, filesystem,
   environment variables, tenant context, or mutable global state.
8. The function cannot return an unbounded or user-controlled object.

Do not add broad standard-library support merely to make the probe pass. Keep the
custom environment allowlisted and explicit.

## Bounded adversarial probes

Use small, deterministic test inputs and explicit upper limits. Record elapsed time
and outcome for diagnostics only; elapsed-time assertions are not a substitute for
hard enforcement.

- Maximum accepted source length and one byte/character above it.
- Maximum accepted variable count and one above it.
- Maximum accepted collection length and one above it.
- Deeply nested expressions at configured parse/check limits.
- Repeated arithmetic / nested conditionals within the accepted source bound.
- Large string/list result attempted only with a host-side output limit.
- Parallel evaluation under a fixed concurrency ceiling.
- Evaluation error, unknown value, and non-Boolean condition behavior.

Every stress test must have a safe, small cap and must not deliberately create
unbounded allocation or hang the CI worker. A test timeout is diagnostic only:
it does not prove the evaluation was cancelled.

## Required run record

Run from `spikes/cel-nessie` with JDK 21 and Maven:

```bash
mvn -B -ntp clean verify
```

For each gate, record: commit SHA, JDK/Maven versions, command, test name,
result, logs/artifact link, and limitations. Keep the report separate from this
matrix so the checklist remains stable.

## Decision rule

- **Restricted trusted-input prototype** may proceed only after custom function
  parity, input validation, error mapping, and repeatable baseline tests pass.
- **Production/untrusted-input provider** requires hard resource governance,
  bounded materialization, deterministic function qualification, exact artifact
  recovery, concurrency isolation, and security review.
- If any mandatory guarantee cannot be established with CEL-Java's public API
  plus a separately verified host mechanism, record it as **Blocked** and reject
  production promotion. Do not treat a heuristic cost estimate, executor timeout,
  or thread interruption as proof of hard cancellation.
- CEL remains additive and opt-in; FEEL behavior and Andalus core are unchanged.
