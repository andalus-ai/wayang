# Deployment lifecycle reconciliation worker

The lifecycle reconciliation worker closes the gap between a terminal durable operation receipt and
the lifecycle snapshot/event ledger. It is deliberately separate from target-operation recovery.

## Responsibilities

A worker tick calls `DeploymentOperationReceiptStore.findTerminalReceipts(limit)` with a hard-bounded
page size (1–1000), then attempts an exclusive lease for each receipt through
`DeploymentLifecycleReconciliationWorkStore`.

For a successfully claimed receipt, the worker:

1. loads the immutable encrypted request context through
   `DeploymentLifecycleReconciliationContextStore`;
2. verifies deployment ID, operation key, operation, and canonical command fingerprint;
3. calls `DeploymentLifecycleReconciliationService` with the original trusted command and audit context;
4. records the outcome in the reconciliation work ledger.

The worker never invokes `activate`, `deactivate`, or `rollback` on a target. The reconciliation service
may perform a read-only readiness check for successful activation or rollback receipts. A missing or
unavailable health observation leaves the lifecycle pending and schedules a retry.

## Cross-process leases

Migration `V4__deployment_lifecycle_reconciliation_work.sql` adds a work row keyed by deployment ID
and operation key. The database primary key elects one initial claimant. A claim can be acquired again
only when a WAITING retry is due or a previous CLAIMED lease has expired. Terminal work states are
not claimable. A fingerprint mismatch for an existing work key is a security conflict, not a replay.

The terminal receipt scan excludes work already in a terminal work state and includes WAITING or expired
CLAIMED rows only when due according to the worker's injected clock. This prevents an old page of
completed receipts from starving newer work. The worker bounds each scan and each lease. The application
owns scheduling, concurrency, back-pressure, and shutdown behavior; this module does not create a background thread or assume a particular scheduler.
Run multiple worker instances safely against the same JDBC database.

## Outcome handling

| Worker status | Meaning | Automatic retry |
| --- | --- | --- |
| RECONCILED | Lifecycle transition applied | No |
| ALREADY_RECONCILED | Matching lifecycle event already committed | No |
| NOT_APPLICABLE | Receipt was rejected before target dispatch | No |
| WAITING | Context/health/storage is temporarily unavailable | Yes, after retryAt |
| UNRESOLVED | Receipt outcome is UNKNOWN or otherwise non-definitive | No; operator intervention |
| CONFLICT | Receipt/context/scope/lifecycle identity mismatch | No; investigate |

A worker crash after applying the lifecycle event but before completing its work row is safe: the lease
expires, another worker claims the row, and the reconciliation service detects the already-committed
idempotency event rather than appending a duplicate lifecycle event.

A worker crash before applying the lifecycle event simply leaves the lease to expire and be retried.
A worker crash while the health observation is in progress may repeat that read-only observation, but
never repeats the original target operation.

## Operational guidance

- Use a lease comfortably longer than the configured readiness-check timeout and expected database
  latency; the constructor caps it at 15 minutes.
- Choose a retry delay based on target health-check and database recovery behavior (0–1 hour).
- Keep scan limits small enough to avoid long-running transactions; no transaction is held while calling
  a target health-check adapter.
- Alert on growing WAITING, UNRESOLVED, and CONFLICT counts.
- Treat UNKNOWN and CONFLICT as operator queues, not automatic retry queues.
- Do not log request context, command parameters, ciphertext, adapter exceptions, or credential-bearing
  health details.
- Configure a production authenticated-encryption codec for the context store; the worker requires a
  context store and does not provide a plaintext fallback.

This worker provides durable, leased lifecycle reconciliation. It does not provide exactly-once delivery
for external side effects and must not be used to replay a target operation.
