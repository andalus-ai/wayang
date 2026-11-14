# Physical PostgreSQL restart qualification — Phase 4AI

## What this verifies

The dedicated PostgreSQL workflow now performs a real database container restart between two separate Java process invocations:

1. The seed process appends a stable event envelope and claims it with a five-second lease.
2. The workflow restarts the PostgreSQL 17 service container without deleting its data volume.
3. The recovery process opens a new DataSource/store instance and verifies schema health and the persisted pending event.
4. It verifies the active lease is not stolen, then advances the SPI caller-supplied clock beyond expiry and reclaims the event with a fresh fencing token and incremented delivery-attempt count.
5. It acknowledges the recovered event.

The event ID and stream identity are fixed for this isolated workflow, which uses a fresh service database for each run. The normal PostgreSQL integration suite runs after the restart scenario.

## Why this is stronger than store reconstruction

Phase 4AH re-applied the migration and reconstructed the store while PostgreSQL remained available. This phase terminates and restarts the actual service process while retaining its container data directory, then uses a separate JVM invocation to recover the event. It exercises PostgreSQL's normal crash-recovery/startup path and verifies persisted event and lease state across the process boundary.

## Boundaries

This is a controlled single-node container restart, not production HA certification. It does not prove primary failover or replica promotion, synchronous replication or zero data loss under site failure, backup/restore or point-in-time recovery, storage-device failure/corruption, production connection-pool/DNS/load-balancer/orchestration behavior, or correctness under multi-host clock skew.

The store continues to use caller-supplied time values as required by the current SPI. Lease expiry is advanced deterministically in the recovery process; this avoids a timing-sensitive sleep and tests persisted lease metadata rather than real-time clock progression.

## Operational recommendation

Before production adoption, qualify the actual deployment topology with documented RPO/RTO objectives, WAL and replication settings, backup retention, restore drills, clock synchronization, and connection-pool retry behavior. Keep the PostgreSQL adapter optional and do not enable it by default based only on this single-node restart test.
