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

The module exposes a SmallRye readiness check at the Quarkus health endpoint.
It reports DOWN only when reconciliation is enabled but the worker CDI bean is
not resolvable. Disabled reconciliation reports UP. Queue backlog, WAITING work,
UNRESOLVED receipts, and CONFLICT outcomes do not make the application unready.

Micrometer counters are registered under `wayang.deployment.reconciliation.*`:

- `ticks` and `tick.failures`
- `receipts.scanned`, `receipts.claimed`, `receipts.reconciled`
- `receipts.waiting`, `receipts.unresolved`, `receipts.conflicts`, `receipts.skipped`
- `tick.duration` timer

Queue-depth gauges use the `queue` metric with a bounded `status` tag for each
durable work status. They start at `-1` until an optional
`DeploymentLifecycleReconciliationQueueSnapshotProvider` is supplied and a
snapshot succeeds. The provider is called after worker ticks, not on every
metrics scrape. Implementations should return aggregate counts only; never add
tenant IDs, deployment IDs, operation keys, or other high-cardinality labels.

The module registers Micrometer meters; the hosting application chooses and
configures the exporter/registry endpoint (for example, Prometheus) and its
authentication/network exposure.
