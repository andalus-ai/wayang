# Durable deployment operation receipts and recovery

**Status: JDBC receipt store and reference tests implemented. The existing orchestration service is not yet wired to this receipt boundary; target adapters must not assume exactly-once side effects.**

## Why receipts are separate from lifecycle events

The lifecycle ledger records state transitions. An operation receipt records the identity and outcome of a requested side effect. They solve different problems: a process can crash after a target accepts activation but before Wayang appends ACTIVE/FAILED. Replaying the lifecycle command alone cannot establish whether the external operation already happened.

## Idempotency identity

A receipt is unique by `(deployment_id, operation_key)`. It stores a SHA-256 command fingerprint over the operation name, deployment/tenant/environment/target/release scope, and parameters sorted by key. Parameter map insertion order does not change identity. Reusing the same key with a different fingerprint or operation is rejected.

The fingerprint is not an authorization token. Callers must still authenticate and authorize the actor and validate tenant/environment scope before claiming or reading a receipt.

## State protocol

- `RUNNING`: the durable claim exists before the target side effect may begin; a bounded lease is recorded.
- `SUCCEEDED` / `FAILED`: terminal result and bounded result metadata are durable and replayable.
- `UNKNOWN`: the lease expired or the caller explicitly marked the outcome uncertain. This is intentionally not retried automatically.
- `ACCEPTED`: reserved for a future queued dispatcher; the current synchronous claim path creates RUNNING directly.

A repeated claim with the same fingerprint returns the existing receipt. It never increments the attempt or starts another operation. A different fingerprint under the same idempotency key is rejected.

## Crash recovery

A recovery worker can scan expired RUNNING receipts using `findExpiredRunning(now, limit)`. It must reconcile the target's observed state using the stable `target_operation_key` and deployment identity. If the target can prove success or failure, the worker can record a terminal outcome through an authorized reconciliation path. If it cannot prove the outcome, mark the receipt UNKNOWN and require operator/policy resolution.

The JDBC store converts an expired RUNNING receipt to UNKNOWN when it is claimed again. It never assumes that lease expiry means the external operation failed, and never reissues the target call. Lease expiry is a signal to reconcile, not permission to duplicate a side effect.

## Integration boundary and limitations

- Persist the receipt before invoking a target adapter.
- Pass the stable `target_operation_key` to adapters that support remote idempotency.
- Persist the target result before updating the deployment lifecycle snapshot.
- If the target does not support idempotency or observation, a crash in the side-effect/result gap remains UNKNOWN and needs reconciliation.
- Exactly-once execution across a database and an independent target system is not claimed. The design provides durable at-most-once dispatch by Wayang and explicit uncertainty handling.
- The current JDBC implementation uses the existing deployment database and migration V2. Migration execution remains the operator's responsibility.
