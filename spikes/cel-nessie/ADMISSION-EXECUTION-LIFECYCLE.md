# Phase 4L — Admission lease and provider task lifecycle

Status: design and integration contract. This phase closes the unsafe gap identified
between Phase 4J admission and Phase 4K caller wait. Do not treat the current
standalone bounded executor as an admission-integrated production path.

## Invariant

An admission lease represents provider work consuming capacity. Therefore it
must be released when the provider task reaches a terminal host-observable state,
not when the caller stops waiting.

| Event | Caller outcome | Lease |
|---|---|---|
| admission rejected | Rejected | none acquired |
| task submission rejected | Rejected | release immediately |
| cancelled/deadline before provider starts | CancelledBeforeStart / DeadlineBeforeStart | release |
| provider returns or throws | Completed / Failed | release in worker finally |
| caller wait expires after submission | Unknown | retain while task queued/running |
| caller interrupted after submission | Unknown | retain while task queued/running |
| provider hangs indefinitely | Unknown to caller | retain; capacity remains occupied |
| executor shutdown while provider runs | no false terminal claim | retain until actual worker exit |

## Required coordinator state machine

A future admission-integrated coordinator should use a single task object with
atomic states:

`QUEUED -> RUNNING -> FINISHED`
`QUEUED -> CANCELLED_BEFORE_START`

Only one transition owns lease release:
- queued cancellation wins: remove task from queue where possible and release;
- worker start wins: queued cancellation cannot release; worker finally releases;
- submission rejection: coordinator releases;
- timeout/interruption of caller: neither transition nor release occurs.

A lease's idempotent close is a safety net, not the state machine itself.
Do not call `Future.cancel(false)` and infer that a queued task was removed or
that its lease can safely be released. A custom runnable/task state and queue
removal protocol are required to make that claim.

## Caller wait and request cancellation

Caller wait timeout is a reporting boundary, not execution cancellation. It
returns an Unknown outcome because the provider may be running or may still be
queued. If the request cancellation token is set while queued, the task may
transition to cancelled-before-start and release its lease. If the provider has
started, the token is cooperative only; keep the lease until provider return.

The coordinator must not automatically retry Unknown. Gamelan remains the
durable activity/retry owner and must apply its idempotency/recovery contract.

## Thread-safety and isolation

The coordinator does not prove CEL Env, Program, activation, or custom function
thread safety. Qualification must explicitly choose:
- per-evaluation Env/Program/activation;
- serialized access to shared provider state; or
- a provider-specific concurrent reuse model backed by tests.

A Java worker pool is not a hard CPU, heap, or wall-clock isolation boundary.
A provider that never returns can permanently consume a worker and admission
lease. Strict enforcement requires a separately managed process/container with
termination evidence and resource controls; thread interruption is not evidence.

## Tests required before calling the integration qualified

1. Caller timeout returns Unknown while active admission remains occupied.
2. Provider completion releases exactly one lease and restores capacity.
3. Provider exception releases exactly one lease without leaking its message.
4. Submission rejection releases the lease.
5. Queued cancellation races worker start; exactly one path releases.
6. Request deadline expires while queued; task never invokes provider and lease
   is released.
7. Caller interruption returns Unknown and does not release a running task.
8. Provider never returns; active count remains occupied and further admission
   obeys the configured limit.
9. Close/rejection does not report running provider work as terminated.
10. Concurrent tenant queueing retains Phase 4J fairness and bounds.

## Scope and non-goals

This is an isolated CEL spike. No production CEL engine is promoted, no parent
reactor dependency is added, and FEEL semantics are unchanged. This phase does
not claim hard CPU/heap limits, forcibly cancellable provider execution,
distributed quotas, native-image support, or production readiness.
