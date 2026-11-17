# Wayang deployment — Quarkus integration

This module provides the Quarkus scheduling boundary for the scheduler-neutral
`wayang-deployment` lifecycle reconciliation worker.

## Configuration

```properties
# Disabled until the application provides a fully configured worker.
wayang.deployment.reconciliation.enabled=false

# Quarkus scheduler interval syntax.
wayang.deployment.reconciliation.interval=30s

# Bounded receipts per tick (1–1000).
wayang.deployment.reconciliation.batch-size=100
```

Enable the task only after the application provides a CDI
`DeploymentLifecycleReconciliationWorker` bean backed by the JDBC receipt/work
stores and a durable context store configured with an authenticated-encryption
codec. There is intentionally no plaintext context fallback.

The scheduler uses `concurrentExecution=SKIP` to prevent overlapping ticks in one
process. The JDBC work ledger's expiring claims provide cross-process exclusion.
Each invocation performs one bounded batch; there is no private thread, polling
loop, or unbounded executor.

## Operational boundaries

- The worker never dispatches activate/deactivate/rollback target operations.
- The scheduler logs aggregate counts only; it does not log context, receipt details,
  adapter exception messages, or credentials.
- A failed tick leaves claimed work to be reclaimed after its lease expires.
- Keep the worker lease longer than its configured readiness-check timeout and
  expected database latency.
- This module does not define application-specific datasource, tenant routing,
  encryption key management, health/readiness policy, or metrics export. Those
  belong in the hosting application/integration layer.

## Health and metrics

When SmallRye Health is enabled by this module, the readiness check is exposed
through the application's standard `/q/health/ready` endpoint.

- Disabled integration reports UP; it is an opt-in capability.
- Enabled integration reports DOWN only when the reconciliation worker CDI bean
  is unavailable.
- WAITING, UNRESOLVED, and CONFLICT work do not make the application unready.
  They are durable work outcomes and should be monitored/handled operationally.
- This readiness check validates bean availability only. It does not prove that
  the database, encryption key provider, or target health adapters are reachable.

Micrometer exports low-cardinality cumulative counters:

- `wayang.deployment.reconciliation.scanned`
- `wayang.deployment.reconciliation.claimed`
- `wayang.deployment.reconciliation.reconciled`
- `wayang.deployment.reconciliation.waiting`
- `wayang.deployment.reconciliation.unresolved`
- `wayang.deployment.reconciliation.conflicts`
- `wayang.deployment.reconciliation.skipped`
- `wayang.deployment.reconciliation.failed_ticks`
- `wayang.deployment.reconciliation.last.success.epoch.millis` (gauge)

No deployment IDs, tenant IDs, operation keys, exception messages, or context
values are used as metric labels.
