# Phase 4O — Graceful provider shutdown and termination evidence

This phase defines what orderly shutdown means for the isolated CEL provider
coordinator and makes task completion observable only after admission accounting
has been released.

## Coordinator shutdown contract

- `close()` calls orderly executor shutdown. It rejects new submissions and
  drains tasks that were already accepted.
- A task already in the worker queue remains accepted and is allowed to run.
- A provider that is currently executing is not interrupted by coordinator
  shutdown.
- `awaitTermination(Duration)` waits for all accepted tasks to terminate and
  returns `false` when the timeout expires. A zero duration is a non-blocking
  check; a negative duration is rejected.
- `isShutdown()` means no new executor work is accepted.
  `isTerminated()` means all accepted work has finished.
- The coordinator deliberately has no forceful shutdown API. Java thread
  interruption is not reliable provider preemption, and force-releasing an
  admission lease would make capacity accounting dishonest.

## Lease and result publication ordering

A worker now computes its terminal outcome, transitions to `FINISHED`, and
closes its admission lease before completing the caller-visible future. A
caller that receives a terminal result can therefore rely on admission
accounting already reflecting the task's termination.

Caller wait timeout or interruption remains distinct from provider termination.
The caller may receive an `Unknown` outcome while the provider continues to
hold its lease. Shutdown timeout likewise reports lack of termination; it
does not imply cancellation or release.

Provider `RuntimeException` values are converted to value-free failure codes.
Fatal JVM errors are not swallowed; the `finally` path still releases the
lease, while the future may remain incomplete and the caller must treat the
provider state as unknown.

## Admission-controller ownership

The coordinator does not close the admission controller. The controller may be
shared by multiple coordinators or other host components. The application
lifecycle owner must stop admission separately after it has stopped all
producers. Closing admission wakes queued acquirers and rejects new ones, while
leases already granted remain valid and are released by their owning tasks.

Recommended application shutdown order:

1. Stop accepting new external evaluation requests.
2. Call `close()` on every coordinator to stop executor submissions and drain
   accepted work.
3. Await each coordinator for the application-defined grace period.
4. If termination has not occurred, report the still-running provider work to
   the process supervisor; do not decrement admission counters artificially.
5. Close the shared admission controller after producers/coordinators have
   stopped accepting work.
6. Let the process/container supervisor own any hard process-level termination.

## Tests added

- Accepted queued work drains after orderly shutdown.
- New work is rejected after shutdown and its admission lease is released.
- A blocked provider causes `awaitTermination` to return false while its
  admission lease remains active.
- Releasing the provider permits termination and releases the lease.
- A terminal result is not published until lease accounting is complete.
- Negative await timeouts are rejected.

## Qualification limits

These tests qualify coordinator lifecycle semantics only. They do not prove
provider thread safety, CEL interpreter cancellation, hard CPU/heap limits,
process isolation, distributed admission, or native-image compatibility. The
Nessie CEL-Java spike remains isolated; no production runtime dependency is
introduced and FEEL semantics remain unchanged.
