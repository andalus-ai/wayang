# Durable deployment operation receipts and recovery

**Status: JDBC receipt store, target observation SPI, and an expired-operation reconciliation service are implemented. The synchronous orchestration service is not yet wired to claim/complete receipts; do not treat the current coordinator as protected by durable idempotency.**

## Why receipts are separate from lifecycle events

The lifecycle ledger records state transitions. An operation receipt records the identity and outcome of a requested side effect. A process can crash after a target accepts activation but before Wayang appends ACTIVE/FAILED. Replaying the lifecycle command alone cannot establish whether the external operation already happened.

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

A recovery worker scans expired RUNNING receipts using `findExpiredRunning(now, limit)`. It resolves the deployment's registered target and invokes the read-only `observeOperation(receipt, timeout)` method. The receipt's stable `targetOperationKey` is the adapter lookup key.

- `SUCCEEDED` and `FAILED` observations are conditionally persisted as terminal receipt outcomes.
- `IN_PROGRESS` remains RUNNING and can be observed again on a later bounded scan.
- `UNKNOWN`, a missing deployment/adapter, or an adapter error becomes UNKNOWN. Exception messages are not persisted.
- Competing recovery workers may both perform the read-only observation. The receipt store's conditional terminal update elects one result writer; a losing worker is counted as conflicted.

The default target observation implementation returns UNKNOWN. An adapter must explicitly implement observation to enable automatic reconciliation. Observation must never start, retry, or otherwise mutate the external operation.

## Integration boundary and limitations

- Persist the receipt before invoking a target adapter.
- Pass the stable `target_operation_key` to adapters that support remote idempotency.
- Persist the target result before updating the deployment lifecycle snapshot.
- The recovery service reconciles receipt state only; it does not currently reconstruct or advance lifecycle transitions.
- The orchestration service is not yet wired to receipt claim/complete, so the full end-to-end guarantee is not yet active.
- Exactly-once execution across a database and an independent target system is not claimed. The intended guarantee is durable at-most-once dispatch by Wayang plus explicit uncertainty handling.
- If the target cannot support idempotency or observation, a crash in the side-effect/result gap remains UNKNOWN and needs operator/policy resolution.
- Migration execution remains the operator's responsibility.
