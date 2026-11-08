# CEL-Java qualification matrix (Phase 4H)

This matrix is evidence-oriented. A source/API observation is not a runtime pass,
and a passing isolated test is not production qualification.

## Status vocabulary

- **Not tested**: no executable probe or source evidence recorded.
- **Probe added**: an executable test exists; actual run evidence is pending.
- **Pass**: repeatable evidence exists for the stated bounded claim.
- **Blocked**: current public API/design does not establish the required guarantee.
- **Not applicable**: explicitly excluded from the intended provider contract.

## Qualification gates

| Gate | Required evidence | Current status | Promotion consequence |
|---|---|---|---|
| Custom environment | Only explicitly declared identifiers/operators/functions compile | Pass: JDK 21 isolated workflow | No promotion until CI/maintainer run |
| Function registration | Explicit library installs compile declarations and runtime implementations | Pass: JDK 21 isolated workflow | No implicit/global function exposure |
| Declaration/runtime parity | Function name, overload ID, arity, argument and result types agree | Typed binding + descriptor-derived declaration probes added; CI pending | Reject provider until CI passes |
| Missing runtime binding | Declaration compiles but missing runtime implementation yields CEL error | Pass: JDK 21 isolated workflow | Must fail closed |
| Wrong argument type | Checker rejects mismatched argument type | Pass: JDK 21 isolated workflow | No silent coercion |
| Function identity | Stable descriptor includes semantic and implementation versions | Pass: JDK 21 isolated workflow | No unversioned function artifacts |
| Registry allowlist | Unknown/duplicate overload rejected; immutable after build | Pass: JDK 21 isolated workflow | No reflection discovery |
| Effect/determinism | Expression-safe profile accepts only pure + deterministic functions | Pass: JDK 21 isolated workflow | Contextual/side-effecting functions excluded |
| Registry fingerprint | Stable across order; changes when contract/implementation changes | Pass: JDK 21 isolated workflow | Recovery requires exact match |
| Error mapping | CEL errors, unknown, null and host failures remain distinct | Not tested | Reject provider |
| Cost estimation | Representative estimates measured; advisory semantics documented | Not tested | Never call estimate a hard budget |
| Hard evaluation budget | Runtime stops work before configured CPU/instruction/allocation ceiling | Blocked / not demonstrated | Do not run untrusted production expressions |
| Cancellation | Cancellation stops evaluation and releases worker/resources | Blocked / not demonstrated | No timeout-only guarantee |
| Input bounds | Host validates source bytes, nesting, variable count, collection sizes | Probe added: source, variables, depth, node, collection, string and total-byte limits | Host admission only; not provider heap quota |
| Result bounds | Host bounds string/list/map materialization | Same bounded value validator can validate adapted results; test added | Apply after conversion and before returning results |
| Artifact recovery | Exact provider/environment/function versions survive restart; mismatch fails closed | Versioned metadata envelope and exact compatibility tests added; executable Program/Ast persistence not implemented | No durable workflow recovery promotion |
| Native image | Quarkus native build/runtime probe including reflection/resources | Not tested | No native-image claim |
| Concurrency | Parallel evaluation isolation, shared Env/Program safety, per-tenant admission | Not tested | No multi-tenant promotion |
| Differential semantics | CEL conformance and Wayang-specific fixtures | Not tested | FEEL unchanged; CEL remains opt-in |

## Phase 4I executable probes

- Typed unary/binary binding factories construct runtime ProgramOptions internally.
- Checker declarations are generated from the same descriptors as runtime bindings.
- Multiple overloads sharing a CEL function name are grouped into one declaration and dispatched by exact overload ID.
- Unsupported custom type names fail closed in this isolated adapter.
- Compiled artifact source and aggregate fingerprints are verified at construction.
- Provider, registry, environment, variable schema, compile options, semantic profile,
  expected result type, and policy version mismatches fail closed.
- Source bytes, activation variable count, nested depth, collection entries, visited
  nodes, string/byte-array sizes, total estimated bytes, cycles, and unsupported host
  values are bounded/rejected.

These remain host-side admission/compatibility probes. They do not establish hard
execution CPU/memory limits, cancellation, durable executable artifact recovery,
or native-image behavior.

## Current executable function tests

- Function absent without explicit registration.
- Declaration without runtime implementation evaluates to CEL error.
- Registered pure function evaluates to CEL Boolean for even/odd/zero/negative inputs.
- Wrong argument type is rejected by the checker.
- Signed 64-bit minimum and repeated evaluation are covered.
- Descriptor identity, validation, registry allowlist, duplicate rejection,
  expression-safe profile, builder freeze, and fingerprint behavior are covered.

The isolated JDK 21 workflow completed successfully for commit
`881251f71a7fae86e50bd840bbbcd18241dd5214`:
[workflow run 42427567914](https://github.com/andalus-ai/wayang/actions/runs/42427567914).
The 22-test suite passed, including custom function registration, registry,
governed adapter, and condition-outcome probes. This is a pass for the tested
spike behaviors only, not production qualification.

## Adversarial/resource probes still required

Use deterministic, bounded fixtures. Elapsed-time assertions are diagnostic only.

- Maximum source length and one character above.
- Maximum variable count and one above.
- Maximum collection cardinality and one above.
- Parse/check depth boundary.
- Bounded large string/list output with host-side rejection.
- Fixed concurrency ceiling and per-tenant admission.
- Error, unknown, null, and non-Boolean condition behavior.
- Evaluation worker termination and resource release under cancellation.

Do not create unbounded allocations or hanging CI tests. A test timeout does not
prove that evaluation was cancelled.

## Run record

Run from this directory with JDK 21:

```bash
mvn -B -ntp clean verify
```

Record commit SHA, JDK/Maven versions, command, test names, results, logs, and
limitations. Recorded run: commit `881251f71a7fae86e50bd840bbbcd18241dd5214`, JDK 21,
command `mvn -B -ntp -f spikes/cel-nessie/pom.xml clean verify`,
workflow run 42427567914, success. The main repository CI has separate jobs and
may still report unrelated dependency/submodule failures.

## Promotion rule

A restricted trusted-input prototype requires function parity, input validation,
error mapping, and repeatable baseline tests.

A production/untrusted-input provider additionally requires hard resource governance,
bounded materialization, exact artifact recovery, concurrency isolation, and security
review. If a mandatory guarantee cannot be demonstrated with CEL-Java plus a verified
host mechanism, mark it **Blocked** and reject production promotion.

CEL remains additive and opt-in. FEEL behavior and Andalus core remain unchanged.
