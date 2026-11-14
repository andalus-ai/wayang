# Deployment orchestration

**Status: orchestration service and fake-target tests implemented; concrete environment adapters and production integration remain unimplemented.**

The orchestration service is the first executable coordinator above the lifecycle ledger and target SPI. It is transport-neutral and has no dependency on Quarkus, Gamelan, Kubernetes, a local process runner, or Wayang Console.

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

## Safety properties and limits

- Command tenant/environment/target/release must match the persisted deployment before the target is called.
- The registry is an explicit allow-list and rejects duplicate target registrations.
- Adapter calls receive a bounded timeout. The adapter must enforce the deadline; the coordinator does not claim that cancelling a Java future can stop a remote operation or child process.
- Adapter runtime exceptions are converted to bounded failure/unknown observations; exception messages and arbitrary process output are not copied into the audit record.
- A health result other than HEALTHY is never represented as ACTIVE.
- Rollback is not automatic. It remains a separate operation requiring explicit policy and authorization; a future increment will coordinate rollback and deactivation with the same audit discipline.
- The in-memory ledger remains unsuitable for multi-node production use. JDBC integration must be configured with a production database and applied migration.

## Verification

Tests use fake in-process target adapters to cover healthy activation, activation rejection, degraded readiness, unknown readiness, scope mismatch, missing target, and invalid timeout. They do not prove a real target's deadline enforcement, process isolation, deployment correctness, or production database behavior. GitHub Actions is the repository validation authority.
