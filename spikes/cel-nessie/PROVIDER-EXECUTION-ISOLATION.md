# Phase 4K — Provider execution isolation

Status: bounded host worker adapter and outcome contract added to the isolated CEL
spike. Provider interruption, preemption, thread-safety, and hard resource limits
remain unqualified.

## Why the result is not simply success/failure

A provider call can start, exceed the caller's wait, and later finish. The caller
then cannot truthfully report "cancelled" or "failed before execution". The
outcome model distinguishes:

- **Completed** — provider returned a result.
- **Rejected** — no execution was admitted (for example, queue full or executor
  closed).
- **CancelledBeforeStart** — cancellation was observed before provider invocation.
- **DeadlineBeforeStart** — the request deadline expired before provider invocation.
- **Failed** — provider/wrapper returned or raised a known failure.
- **Unknown** — provider execution began or may have begun, but the caller lost
  certainty after timeout/interruption.

Unknown outcomes must not be converted into automatic retries. Gamelan's activity
idempotency, checkpoint and recovery contract determines whether replay is safe.

## Bounded worker adapter

`BoundedEvaluationExecutor` uses a fixed-size worker pool and bounded queue.
The caller's wait is capped by `callerWaitTimeout`. A saturated queue rejects
rather than adding unbounded work.

This worker pool is process-local and is not an isolation boundary against
malicious or runaway provider code. A worker can remain occupied after the
caller receives Unknown. The adapter deliberately does not free that worker
permit until the task exits.

### Cancellation caveat

The adapter checks the cancellation token and deadline immediately before the
provider call. During a running call, neither token nor caller timeout forcibly
stops the provider. `Future.cancel(false)` only cancels a task that has not
started; it does not interrupt a running evaluation.

The executor's `close()` uses orderly shutdown. It does not guarantee that
provider work has terminated. A future process-level executor should expose
termination evidence and quarantine/kill semantics separately.

## Deadline semantics

- Admission queue deadline: limits waiting for a lease.
- Request deadline: checked before execution begins.
- Caller wait timeout: limits how long this thread waits for the provider outcome.
- Provider execution deadline: **not established** by this implementation.

A strict execution deadline requires a provider-supported cancellation primitive
that has been tested, or a separate process/container boundary with enforceable
resource controls. Thread interruption alone is not evidence of termination.

## Thread safety and provider reuse

This adapter does not establish that CEL `Env`, compiled `Program`, function
bindings, or custom activation objects are safe for concurrent use. The caller
must qualify each provider and artifact reuse model before sharing objects
across worker threads. Until that evidence exists, use isolated per-evaluation
provider state or serialize access.

## Admission integration

The admission lease from Phase 4J must be held around submission and the entire
provider task lifetime—not merely around the caller's wait. Since this adapter
does not yet own a lease or reliably observe the task's final termination,
do not compose it with the Phase 4J controller in production. A follow-up must
tie lease release to task completion, not caller timeout.

## Qualification checklist

- Queue saturation rejects with bounded queue depth.
- Worker count never exceeds configured maximum.
- Caller timeout returns Unknown while worker remains occupied.
- Pre-cancelled request never invokes provider.
- Request deadline is checked before provider invocation.
- Provider failure does not leak exception messages or activation data.
- Close/rejection semantics are documented.
- Lease release occurs only when provider work truly exits.
- Provider thread safety, cancellation, result-size and process isolation remain
  separate qualification gates.

## Non-goals

This phase does not claim hard CPU/instruction/heap bounds, running-evaluation
cancellation, distributed concurrency quotas, native-image support, or
production readiness. CEL remains opt-in and isolated; FEEL semantics and
Andalus core are unchanged.
