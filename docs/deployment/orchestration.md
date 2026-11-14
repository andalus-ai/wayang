# Deployment orchestration

**Status: orchestration service and fake-target tests implemented; concrete environment adapters and production integration remain unimplemented.**

The orchestration service is the executable coordinator above the lifecycle ledger and target SPI. It is transport-neutral and has no dependency on Quarkus, Gamelan, Kubernetes, a local process runner, or Wayang Console.

## Activation protocol

1. Load the deployment snapshot and verify tenant, environment, target, and release identity against the immutable command.
2. Resolve the target only through the explicitly populated DeploymentTargetRegistry. No user-controlled class loading or adapter discovery is performed.
3. Validate the per-call timeout (greater than zero, at most ten minutes).
4. Record INSTALLED -> ACTIVATING before invoking the target.
5. Invoke DeploymentTarget.activate.
6. If activation is not SUCCEEDED, record FAILED and do not run readiness checks.
7. If activation succeeds, invoke healthCheck.
8. Record ACTIVE only for HEALTHY; record DEGRADED for DEGRADED; record FAILED for UNHEALTHY or UNKNOWN.

Every lifecycle event uses a deterministic phase-specific key derived from the command idempotency key. Reusing the same command key therefore conflicts at the ledger boundary rather than silently executing a second activation.

## Deactivation protocol

1. Verify immutable deployment scope and resolve the trusted target.
2. Record ACTIVE/DEGRADED -> DEACTIVATING before invoking the adapter.
3. Invoke the adapter's bounded `deactivate` operation.
4. Record DEACTIVATED only for a `SUCCEEDED` acknowledgement; rejected, failed, timed-out, unknown, missing, or exceptional outcomes become FAILED.
5. Deactivation does not run the ordinary readiness probe because a stopped deployment is not expected to be healthy. A target adapter should report success only after it has observed the desired stopped condition.

## Rollback protocol

1. Verify immutable deployment scope and resolve the trusted target.
2. Record the eligible ACTIVE/DEGRADED/FAILED -> ROLLING_BACK transition.
3. Invoke the adapter's bounded `rollback` operation.
4. If the operation is not `SUCCEEDED`, record FAILED and do not claim restoration.
5. If it succeeds, run the target health check against the restored release.
6. Record ROLLED_BACK only for HEALTHY; DEGRADED, UNHEALTHY, or UNKNOWN readiness records FAILED.

Rollback is deliberately not automatic after failed activation or unhealthy readiness. The caller must make an explicit, authorized request and supply a reason and correlation ID.

## Safety properties and limits

- Command tenant/environment/target/release must match the persisted deployment before the target is called.
- The registry is an explicit allow-list and rejects duplicate target registrations.
- Adapter calls receive a bounded timeout. The adapter must enforce the deadline; the coordinator does not claim that cancelling a Java future can stop a remote operation or child process.
- Adapter runtime exceptions are converted to bounded failure/unknown observations; exception messages and arbitrary process output are not copied into the audit record.
- A health result other than HEALTHY is never represented as ACTIVE.
- Rollback is not automatic. It remains a separate operation requiring explicit policy and authorization; a future increment will coordinate rollback and deactivation with the same audit discipline.
- The in-memory ledger remains unsuitable for multi-node production use. JDBC integration must be configured with a production database and applied migration.

## Verification

Tests use fake in-process target adapters to cover healthy activation, activation rejection, degraded readiness, unknown readiness, scope mismatch, missing target, invalid timeout, successful/failed deactivation, successful rollback, and rollback with uncertain readiness. They do not prove a real target's deadline enforcement, process isolation, deployment correctness, or production database behavior. GitHub Actions is the repository validation authority.
