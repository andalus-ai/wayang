# Phase 4P — Shared admission and multi-coordinator lifecycle

This phase qualifies the host-side contract when independently managed
execution coordinators share one `EvaluationAdmissionController`.

## Shared capacity contract

The admission controller is the single authority for global and per-tenant
active/queued limits. Coordinator worker pools are local execution resources;
they do not establish separate admission budgets. Every task must acquire a
lease from the shared controller before it is submitted to a worker pool.

Consequences:

- Two coordinators sharing one controller cannot exceed the controller's
  global active limit, even when each coordinator has idle worker capacity.
- Tenant limits are also shared across coordinators because tenant accounting
  belongs to the controller, not to an individual worker pool.
- A coordinator may reject work because its local executor is full even when
  shared admission capacity exists. Local worker queue limits and shared
  admission limits are distinct backpressure layers.
- Admission queue limits bound callers waiting for a lease; coordinator queue
  limits bound already-admitted tasks waiting for a worker.

## Lifecycle isolation

Closing one coordinator shuts down only that coordinator's executor. It does
not close the shared admission controller and does not affect another
coordinator using it.

Closing the shared admission controller is an application-level operation.
It wakes queued acquirers and rejects future admissions. Leases already granted
remain valid; their owners must release them after their provider tasks finish.
The controller must not be closed merely because one coordinator is stopping.

## Recommended ownership model

The application/runtime composition root owns the shared admission controller.
Individual coordinator instances own only their worker pools. The composition
root should:

1. Stop ingress and new evaluation producers.
2. Close all coordinators.
3. Await each coordinator's termination within the configured grace period.
4. Close the shared admission controller once no new coordinator submissions
   or external producers can call it.
5. Observe remaining active leases and escalate at the process boundary if
   providers fail to terminate.

A shared controller is process-local. This phase does not make its limits
distributed across JVMs, containers, or hosts.

## Tests added

- A global concurrency limit is enforced across two coordinators.
- Closing one coordinator does not stop another coordinator sharing the
  controller.
- Closing the shared controller rejects queued admission while preserving an
  already granted provider lease until that provider returns.

## Qualification limits

These tests qualify in-process sharing and lifecycle boundaries only. They do
not prove distributed admission, multi-process coordination, provider
thread-safety, forced cancellation, hard CPU/heap bounds, or crash recovery.
The CEL-Java implementation remains an isolated qualification spike. No
production CEL dependency is introduced; FEEL semantics and Andalus core remain
unchanged.
