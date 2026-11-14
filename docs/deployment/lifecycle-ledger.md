# Deployment lifecycle ledger

**Status: implemented reference state machine, in-memory ledger, JDBC ledger, and target adapter contract. Concrete target adapters and production database integration remain unimplemented.**

The offline package installer establishes that a release has been verified and atomically placed on disk. It does not activate the release. The deployment module provides an engine-neutral lifecycle vocabulary, append-only event contract, durable ledger adapter, target SPI, and orchestration coordinator.

## Responsibilities and boundaries

- wayang-publish verifies, assembles, and installs release packages.
- wayang-deployment models deployment state transitions, records audit facts, and coordinates activation with a trusted target adapter.
- A deployment target adapter is responsible for staging, activating, deactivating a concrete runtime, and performing bounded health checks.
- Wayang Console remains responsible for fleet orchestration and operator-facing management.
- Gamelan remains responsible for workflow-run durability and scheduling; this module is not a workflow engine.

The orchestration service does not execute shell commands, launch containers, run migrations, or probe endpoints itself. Those operations require separately reviewed adapters with explicit permissions and deadlines. See [orchestration.md](orchestration.md).

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

The in-memory reference ledger rejects duplicate idempotency keys. The JDBC ledger enforces uniqueness with a database constraint; an equivalent retry currently returns a conflict rather than replaying the prior result. The orchestration coordinator derives deterministic phase keys from the command key, so retrying a partially or fully processed command cannot silently start a second activation. Durable response replay and command-fingerprint storage remain future work.

## Verification boundary

Unit tests cover allowed and rejected transitions, stale versions, degraded and failed health outcomes, explicit rollback, event ordering, and duplicate registration/key rejection. Orchestration tests use fake targets to exercise activation success/failure, readiness outcomes, scope checks, timeout validation, deactivation success/failure, and rollback success/uncertain readiness. They do not prove production database behavior against PostgreSQL, real runtime activation, adapter deadline enforcement, or rollback of a live service. JDBC tests use H2 to exercise transaction rollback and event ordering.
