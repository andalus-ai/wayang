# Phase 4J — Resource governance and evaluation admission

Status: host-side admission implementation and bounded concurrency probes.
This is still an isolated CEL-Java qualification spike, not a production runtime
API. It does not establish hard CPU, instruction, heap, or allocation ceilings.

## 1. Responsibility boundary

Wayang owns admission to expression evaluation and the local guarantees around
that boundary. Gamelan continues to own durable workflow lifecycle, retries,
timeouts, cancellation orchestration, checkpoints, and process recovery.

The admission controller is intentionally independent of CEL-Java. It can wrap
a provider call, but it cannot stop a provider call that is already executing.

## 2. Admission contract

The implementation introduces:

- `EvaluationAdmissionPolicy`: global active limit, global queue limit,
  per-tenant active limit, per-tenant queue limit, and maximum queue wait.
- `EvaluationRequestContext`: trusted tenant identity, correlation ID, optional
  absolute request deadline, and a cooperative cancellation token.
- `EvaluationAdmissionController`: bounded FIFO wait queue with per-tenant
  concurrency accounting and aggregate diagnostics.
- `EvaluationAdmissionController.Lease`: idempotent `AutoCloseable` permit.
- `EvaluationCancellationToken`: a host-side cancellation signal.
- `EvaluationAdmissionException`: stable, value-free rejection codes.

Callers must resolve tenant identity from authenticated server-side context.
The expression activation, CEL source, user-provided fixture, and tool arguments
are never authoritative tenant sources.

Recommended usage:

```java
try (var lease = admissionController.acquire(trustedRequestContext)) {
    cancellationToken.throwIfCancelled();
    inputValidator.validateVariables(activation);
    Object rawResult = program.eval(activation);
    inputValidator.validateResult(rawResult);
    return rawResult;
}
```

The lease must enclose the complete provider invocation and result validation.
Never release the lease immediately after dispatching work to another thread.

## 3. Queueing, fairness, and quotas

Admission uses a fair lock and bounded global FIFO queue. Among waiters that can
currently be admitted under their tenant quota, earlier eligible waiters proceed
first. A tenant at its active limit does not prevent a different eligible tenant
from using otherwise available global capacity.

A request can be rejected for:

- `GLOBAL_QUEUE_FULL`
- `TENANT_QUEUE_FULL`
- `QUEUE_WAIT_TIMEOUT`
- `REQUEST_DEADLINE_EXCEEDED`
- `EVALUATION_CANCELLED`
- `ADMISSION_CONTROLLER_CLOSED`

The controller does not include tenant IDs or request values in exception
messages or aggregate snapshots. The snapshot reports active count, queued count,
and closed state only. Tenant-specific counters, if needed for metrics, must be
exported through an authenticated and appropriately redacted telemetry path.

Queue cancellation is observed through bounded polling (at most a 50 ms wait
slice) because the token is deliberately independent of controller internals.
This is cancellation of admission waiting only. It does not interrupt or stop a
CEL-Java `Program.eval` already in progress.

## 4. Deadlines and interruption

Queue wait is measured with monotonic elapsed time. The optional request deadline
is an absolute `Instant` evaluated using the controller clock. The earlier of
the queue deadline and request deadline bounds each wait.

Thread interruption removes the waiter and propagates `InterruptedException`.
The caller must preserve interruption semantics and let the surrounding
orchestrator decide whether to cancel or retry the workflow activity.

An admission deadline is not a provider execution deadline. Once a lease is
granted, the provider may continue until it returns unless it implements and
passes a separately qualified cooperative/preemptive cancellation mechanism.

## 5. Cost and memory governance

CEL-Java cost estimation remains advisory. It may be useful for telemetry or a
conservative pre-admission heuristic, but it is not a hard CPU/instruction budget.
This phase does not claim that a cost estimate can interrupt execution.

The existing `ExpressionInputValidator` bounds source bytes and host activation
shape. Its result validation must run after provider result conversion and before
the value crosses the Wayang expression boundary. It cannot prevent allocations
that already occurred during provider evaluation or conversion. A strict memory
ceiling requires an independently verified isolation mechanism (for example,
a process/container boundary with enforced resource limits).

## 6. Recovery and retry semantics

Admission state is ephemeral and process-local. Leases are never serialized and
must not be restored after a crash.

A process crash while a pure deterministic expression is executing may still
leave the caller uncertain whether the result was produced or persisted. Gamelan
must use its own activity/idempotency and checkpoint semantics to decide whether
to retry. Do not infer exactly-once execution from an admission lease.

Only functions admitted by the expression-safe pure/deterministic profile are
eligible for a retry-safe classification, and even then the surrounding activity
must be idempotent or safely replayable. Contextual, nondeterministic, and
side-effecting operations belong in explicit workflow nodes with their own
idempotency/compensation contract.

## 7. Qualification and limitations

The tests exercise bounded queue capacity, global and per-tenant active limits,
progress for a different tenant while one tenant is quota-limited, queue timeout,
request deadline, pre-cancelled requests, controller close, and idempotent lease
release.

They do not prove:

- hard CPU, instruction, heap, or allocation limits;
- cancellation of a running CEL-Java evaluation;
- safe sharing of CEL Env/Program instances under concurrency;
- production multi-tenant isolation or fairness under adversarial load;
- distributed admission across multiple Wayang processes;
- persistence/recovery of executable CEL artifacts;
- provider-side result allocation bounds.

For a multi-process deployment, this in-memory controller is only a per-process
guard. A distributed quota service would need its own availability, lease-expiry,
partition, and crash-consistency contract. Do not silently treat local counters
as cluster-wide quotas.

## 8. Promotion rule

Keep this implementation in the isolated spike until concurrency stress tests,
provider thread-safety qualification, cancellation/resource-release evidence,
tenant authorization review, and deployment-level quota semantics are complete.
A passing unit suite validates only the tested local admission behavior.
