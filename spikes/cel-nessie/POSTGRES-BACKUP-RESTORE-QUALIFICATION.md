# PostgreSQL backup and restore qualification — Phase 4AJ

## Purpose

This phase qualifies a logical backup/restore path for the optional PostgreSQL
pressure-event store. It deliberately uses a separate restore database so the
test does not mutate the live database used by the subsequent physical restart
scenario.

## Workflow

1. Apply the versioned pressure-event schema migration to a clean PostgreSQL 17
   service database.
2. Run the seed JVM, which appends a stable event and creates an active lease.
3. Create a PostgreSQL custom-format dump with pg_dump.
4. Create a separate restore database and load the dump with pg_restore.
5. Verify the restored schema version, unacknowledged event row, and lease
   metadata (consumer, claim token, expiry) directly from the restored database.
6. Restart the original PostgreSQL service container.
7. Use a fresh JVM/store instance to verify restart recovery and finish the
   event delivery.
8. Run the PostgreSQL-backed store integration suite.

## Guarantees exercised

- Migration is applied before any store operation (the earlier Phase 4AI
  workflow omission is corrected here).
- A committed pending/leased event is present in the logical backup.
- Schema metadata and lease columns survive restore.
- The restore is isolated from the original restart-recovery database.

## Limits

This is a same-version logical backup/restore smoke qualification. It does not
prove point-in-time recovery from WAL archives, recovery from physical base
backups, cross-major PostgreSQL upgrades, encryption/key recovery, backup
retention, off-site storage, replica promotion, or production RPO/RTO. It also
does not prove that a restored lease should remain valid in a deployment whose
clock or worker identity changed; the current SPI uses caller-supplied time and
consumer IDs.

## Operational requirements before production

- Establish RPO/RTO and backup retention requirements.
- Protect backup artifacts with encryption and access controls in the actual
  deployment environment.
- Test base backup plus WAL/PITR restoration, not only logical pg_dump.
- Validate codec compatibility for every event payload that may still be
  pending in a backup.
- Restore into an isolated environment, verify schema and health diagnostics,
  and only then allow consumers to claim events.
- Decide whether restored active leases should be preserved, expired, or
  explicitly cleared as part of a controlled recovery procedure.
- Never treat backup restoration as exactly-once delivery; consumer callbacks
  must remain idempotent.

The PostgreSQL adapter remains optional and isolated from default Wayang
runtime selection.
