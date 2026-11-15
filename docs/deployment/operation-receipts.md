# Durable deployment operation receipts and recovery

**Status: JDBC receipt store, target observation/reconciliation, atomic exclusive claims, and a reusable durable execution gate are implemented. The existing synchronous orchestration service is not yet automatically receipt-wired; callers must route side effects through the execution gate until that integration is completed.**

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

## Durable execution gate

`DeploymentOperationExecutionGate` is the reusable boundary for receipt-protected side effects:

1. It validates and atomically claims the operation through `claimExclusive`.
2. Only the caller receiving `acquired=true` invokes the supplied side-effect function.
3. A duplicate receives `DeploymentOperationExecutionResult(dispatched=false, result=null, receipt=...)`; it must surface that receipt to its caller and must not dispatch.
4. A successful or explicitly failed result is persisted as a terminal receipt.
5. If dispatch or result classification throws, the receipt is marked UNKNOWN and the exception is propagated. If terminal persistence itself fails, the RUNNING receipt is left for recovery.

The gate accepts a status classifier so target-specific result types remain outside the deployment kernel. Classifiers may return only SUCCEEDED or FAILED. TIMEOUT/UNKNOWN outcomes must be classified conservatively by the adapter integration; do not classify an ambiguous remote outcome as FAILED merely to close the receipt.

Example:

```java
var outcome = executionGate.execute(
    "ACTIVATE",
    command,
    Duration.ofMinutes(2),
    () -> target.activate(command, timeout),
    result -> switch (result.status()) {
        case SUCCEEDED -> DeploymentOperationStatus.SUCCEEDED;
        case REJECTED -> DeploymentOperationStatus.FAILED;
        case TIMED_OUT, FAILED, UNKNOWN -> DeploymentOperationStatus.UNKNOWN;
    },
    result -> result.status().name(),
    TargetOperationResult::detail);
if (!outcome.dispatched()) {
    // Return the existing receipt to the caller; never invoke the target again.
}
```

The gate is intentionally not a replacement for lifecycle orchestration. Its caller remains responsible for scope authorization, lifecycle transition policy, and reconciling a crash between external side effects and lifecycle updates. A receipt may become terminal while the lifecycle snapshot still needs reconciliation if the process crashes between those writes.

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
- Authenticate and authorize before claiming; a receipt fingerprint is not authorization.
- The recovery service reconciles receipt state only; it does not currently reconstruct or advance lifecycle transitions.
- The legacy synchronous orchestration methods are not automatically wrapped by the execution gate yet. Direct callers that bypass the gate do not receive durable dispatch protection.
- Exactly-once execution across a database and an independent target system is not claimed. The protocol provides durable at-most-once dispatch by Wayang plus explicit uncertainty handling, provided all dispatch paths use the gate and the receipt database's uniqueness constraint is enforced.
- If the target cannot support idempotency or observation, a crash in the side-effect/result gap remains UNKNOWN and needs reconciliation.
- Migration execution remains the operator's responsibility.

## Exclusive dispatch claims

Receipt-aware dispatchers must use `DeploymentOperationReceiptService.claimExclusive`, not the legacy replaying `claim` method. The returned `DeploymentOperationClaim` has an `acquired` flag:

- `acquired=true`: this caller won the atomic insert and is the only caller permitted to invoke the target for this operation key.
- `acquired=false`: the receipt already existed or its lease had expired. The caller must not invoke the target. It should surface the existing RUNNING/terminal/UNKNOWN state to the caller or route it to reconciliation.

The JDBC implementation uses the database uniqueness constraint as the cross-process election point. Concurrent inserts for the same deployment/key yield one winner; the losing transaction re-reads the durable receipt and returns `acquired=false`. Stores that do not implement this atomic primitive fail closed rather than falling back to a racy read-then-write claim.
