# Multi-worker reconciliation recovery tests

`DeploymentReconciliationMultiWorkerIntegrationTest` uses two independent JDBC
store instances against one database to model separate worker processes.

It covers two safety properties:

1. Concurrent workers cannot both acquire the same live reconciliation lease.
2. A replacement worker can reclaim an expired lease after restart, while completed
   work remains terminal and cannot be claimed again.

The test intentionally exercises the shared JDBC work ledger rather than invoking
a deployment target. Reconciliation recovery must never redispatch activate,
deactivate, or rollback operations.

Run with:

```bash
mvn -pl wayang-deployment -am -Dtest=DeploymentReconciliationMultiWorkerIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The test uses H2 to validate transactional and uniqueness behavior. Production
database engines should also run the same contract suite in their integration
profiles before being declared supported for multi-instance deployment.
