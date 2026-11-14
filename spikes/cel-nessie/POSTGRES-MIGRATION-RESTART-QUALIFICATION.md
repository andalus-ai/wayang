# PostgreSQL migration and restart resilience — Phase 4AH

## Scope

This phase qualifies the existing optional JDBC adapter's schema lifecycle and
application restart behavior. It does not change the database-neutral
`PressureEventStore` SPI, the PostgreSQL schema version, or runtime wiring.

## Qualified behavior

The PostgreSQL integration suite now verifies:

- Reapplying the V1 migration is safe when events already exist.
- Migration reapplication preserves a pending event and its active lease.
- A reconstructed store sees the same schema version and backlog.
- A second worker cannot steal a live lease; after expiry the event is
  reclaimed with a fresh fencing token and incremented delivery attempt.
- The recovered event can be acknowledged normally.
- Database constraints reject non-positive sequence numbers and partial claim
  metadata (for example, a consumer ID without its token and lease expiry).

The test uses a new store instance and a repeated migration against the same
PostgreSQL database. This is **application/store reconstruction**, not a claim
that the test process physically restarted the PostgreSQL server.

## Deployment guidance

- Apply migrations as a controlled deployment step before enabling consumers.
- Back up the database and verify restore procedures before schema changes.
- Keep payload decoders compatible with every event format that may remain
  pending or leased during a rolling deployment.
- Do not downgrade schema metadata or remove columns while older application
  instances may still be running.
- Keep lease duration and host clock discipline aligned with the caller-time
  contract. A future database-authoritative lease clock requires an explicit
  SPI revision.
- Test PostgreSQL restart, primary failover, WAL durability, replication
  consistency, and backup/restore in the target deployment environment. The
  service-backed CI suite is not a substitute for infrastructure certification.

## Explicit non-goals

No automatic migration runner, schema downgrade, failover orchestration,
replication topology, backup scheduler, connection-pool policy, or runtime
activation is introduced. The adapter remains isolated in the CEL qualification
spike and is not selected by default.
