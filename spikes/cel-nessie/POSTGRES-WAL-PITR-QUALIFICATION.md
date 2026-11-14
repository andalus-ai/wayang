# PostgreSQL WAL point-in-time recovery qualification — Phase 4AK

## Purpose

Exercise a real PostgreSQL 17 physical base backup and archived-WAL recovery to
a timestamp chosen between two committed states. This complements (but does not
replace) the logical `pg_dump`/restore and service-restart checks.

The qualification runs in a disposable Docker environment on a Linux CI runner.
It does not modify the runner's PostgreSQL service or require a production
database.

## Procedure

1. Start PostgreSQL 17 with `wal_level=replica`, `archive_mode=on`,
   `archive_timeout=1s`, and a filesystem-backed `archive_command`.
2. Apply the pressure-event schema migration and create a small qualification
   marker table.
3. Insert a baseline marker, then take a physical `pg_basebackup` with streamed
   WAL.
4. Record a database-generated UTC recovery target after the base backup.
5. Commit a second marker after that target and force a WAL segment switch.
6. Wait for the archiver's last successful archive timestamp to reach/past the
   recovery target and confirm an archive file exists.
7. Restore the physical backup into a second PostgreSQL 17 container, configure
   `restore_command` and `recovery_target_time`, and let recovery promote.
8. Verify the server has left recovery, the baseline marker exists, and the
   post-target marker does not.

This demonstrates recovery to an earlier point in the WAL stream, not merely
restoring a backup containing the latest committed state.

## What this qualifies

- PostgreSQL 17 physical base-backup creation.
- WAL archiving to a separate mounted filesystem path.
- Recovery from the base backup using archived WAL.
- Timestamp-based recovery and promotion.
- Positive and negative data assertions around the recovery target.

## Boundaries and operational caveats

- This is an automated qualification harness, not a production backup system.
- It uses one PostgreSQL major version and a local Docker filesystem; it does
  not qualify cross-major upgrades, object-storage archives, encryption/key
  recovery, off-site copies, replication failover, storage corruption, or
  disaster recovery across regions.
- It does not establish an RPO/RTO. Those require workload-specific backup
  cadence, archive durability, restore throughput, and operational exercises.
- The script uses a short-lived test database and disposable credentials.
  Production credentials and archive paths must be managed independently.
- The marker table validates PostgreSQL recovery mechanics. It does not claim
  that application-level event consumers are exactly-once; pressure-event
  delivery remains at-least-once and consumers must be idempotent.
- The PostgreSQL adapter remains optional and isolated from Wayang's default
  runtime and database-neutral persistence SPI.

## Failure interpretation

The job fails closed if WAL is not archived through the target, recovery does
not promote, baseline data is missing, or post-target data appears. A successful
run is evidence for this specific PostgreSQL 17 container configuration only;
it is not a substitute for recurring restore drills against the deployment's
actual storage and retention configuration.
