# Deployment lifecycle ledger

**Status: implemented reference state machine, in-memory ledger, JDBC ledger, and target adapter contract. Concrete target adapters and production database integration remain unimplemented.**

The offline package installer establishes that a release has been verified and atomically placed on disk. It does not activate the release. This module introduces a separate, engine-neutral lifecycle vocabulary and append-only event contract.

## Responsibilities and boundaries

- wayang-publish verifies, assembles, and installs release packages.
- wayang-deployment models deployment state transitions and records their audit facts.
- A deployment target adapter is responsible for staging, activating, deactivating a concrete runtime, and performing bounded health checks.
- Wayang Console remains responsible for fleet orchestration and operator-facing management.
- Gamelan remains responsible for workflow-run durability and scheduling; this module is not a workflow engine.

The lifecycle service does not execute shell commands, launch containers, run migrations, probe endpoints, or perform rollback itself. Those operations require separately reviewed adapters with explicit permissions and deadlines.

## State model

INSTALLED -> ACTIVATING -> ACTIVE

Activation health can instead result in DEGRADED or FAILED. Failed or active/degraded deployments may enter ROLLING_BACK, then ROLLED_BACK. Active/degraded deployments may also be deactivated through DEACTIVATING -> DEACTIVATED.

Transitions are allow-listed in DeploymentLifecycleService; unknown transitions fail closed. A health result of UNKNOWN is treated as failure, never as readiness.

## Concurrency and audit contract

Every accepted transition:
- carries actor, reason, correlation ID, idempotency key, and timestamp;
- increments the deployment version exactly once;
- appends an event with a monotonic per-deployment sequence;
- uses an expected version to reject stale commands.

DeploymentLedger.append is the persistence seam. A production implementation must atomically commit the event and new snapshot, enforce uniqueness for (deploymentId, idempotencyKey), and preserve event ordering. The included InMemoryDeploymentLedger is synchronized but process-local; it is not durable, multi-node safe, or a production audit store.

The in-memory reference ledger rejects duplicate idempotency keys. The JDBC ledger enforces uniqueness with a database constraint; an equivalent retry currently returns a conflict rather than replaying the prior result. A future command service can add safe response replay by persisting a canonical command fingerprint and result.

## Verification boundary

Unit tests cover allowed and rejected transitions, stale versions, degraded and failed health outcomes, explicit rollback, event ordering, and duplicate registration/key rejection. They do not prove production database behavior against PostgreSQL, real runtime activation, health-check correctness, or rollback of a live service. JDBC tests use H2 to exercise transaction rollback and event ordering.
