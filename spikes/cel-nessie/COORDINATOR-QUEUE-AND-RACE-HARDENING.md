# Phase 4N — Coordinator queue and race hardening

This phase hardens the admission-integrated coordinator after its first merged
implementation.

## Queue capacity is exact

A configured execution queue of zero now uses a direct-handoff
`SynchronousQueue`. It does not silently allocate a one-element queue.
Positive queue capacities use `ArrayBlockingQueue` with the exact configured
capacity. Saturation returns `EXECUTION_QUEUE_FULL` and releases the newly
acquired admission lease.

## Cancellation semantics

Cancellation is cooperative before provider invocation. A queued task checks
its request token after it wins the worker-start transition and before calling
the provider. If cancelled, it returns `CancelledBeforeStart` and releases
its lease in the task's terminal path. The worker-start transition and queued
cancellation transition share one atomic state, so only one can win.

This does not remove a queued task immediately when its token changes. The
bounded worker queue may retain it until a worker becomes available; the lease
also remains held until the task is dequeued and observes cancellation. This is
intentional and bounded, and avoids pretending that a task was removed when it
was not.

## Shutdown

`close()` performs orderly shutdown: it stops new submissions and drains
accepted tasks. It does not interrupt providers or release leases for work that
has not actually terminated. A permanently blocked provider can therefore keep
a worker and lease occupied. A future process supervisor must own forceful
termination and termination evidence.

## Tests added

- Zero queue capacity rejects a second task while the sole worker is occupied.
- Rejection releases only the rejected task's lease.
- A task is deterministically placed in the queue before its cancellation token
  is changed; it must not invoke the provider when later dequeued.
- Existing caller-timeout lease-retention and provider-failure tests remain.

## Qualification limits

These tests establish host coordinator behavior only. They do not establish
CEL Env/Program concurrency safety, hard execution budgets, provider preemption,
distributed admission, or native-image behavior. Run the isolated Maven spike
on JDK 21 CI before promoting any qualification status.
