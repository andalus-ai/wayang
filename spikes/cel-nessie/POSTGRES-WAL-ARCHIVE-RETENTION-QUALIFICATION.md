# PostgreSQL WAL archive integrity and retention qualification — Phase 4AL

## Purpose

A retained base backup is only useful while every WAL segment needed to make it
consistent remains available. This qualification checks the relationship
between a physical backup manifest and its archived WAL, and demonstrates that
a simulated purge of required WAL is detected.

## Procedure

1. Start a disposable PostgreSQL 17 instance with WAL archiving enabled.
2. Apply the pressure-event schema migration and create a qualification marker.
3. Take a physical base backup with `pg_basebackup -X none`, deliberately
   keeping the backup's required WAL outside the backup directory.
4. Force a WAL segment switch and wait for PostgreSQL to archive the required
   range.
5. Run `pg_verifybackup --wal-directory` against the base backup and archive.
6. Produce a SHA-256 inventory of archived files for the qualification run.
7. Run the verifier again with an empty archive volume. The check must fail;
   success would mean the harness has not demonstrated that the backup depends
   on the archive.

## Retention rule demonstrated

A WAL segment must not be removed merely because it is older than a time-based
retention threshold. It may still be required by one or more retained base
backups. A production retention process should:

- determine the oldest WAL segment required by every retained base backup;
- preserve all required segments, including timeline history files;
- apply age/space-based retention only to segments outside every retained
  backup's dependency range;
- verify each retained backup against the remaining archive before committing
  a purge;
- record archive inventories and purge outcomes without exposing event payloads.

The test's empty-archive negative case is a destructive simulation against a
separate disposable volume. It never deletes from the positive-test archive.

## What this qualifies

- PostgreSQL 17 backup-manifest verification against a separate WAL archive.
- Detection of a missing required WAL archive.
- SHA-256 inventory generation for archived files.
- A fail-closed gate suitable for use before a retention purge.

## Boundaries

- This is a qualification harness, not a production archive manager or retention
  scheduler.
- It does not implement object-store lifecycle rules, multi-region replication,
  encryption/key management, archive upload atomicity, or a backup catalog for
  multiple retained backups.
- The archive uses a local Docker named volume; it does not establish durability
  across host loss.
- A successful run does not establish production RPO/RTO. Real deployments must
  run the same verification against their own archive destination and every
  retained base backup.
- The PostgreSQL adapter remains optional and isolated from Wayang's default
  runtime and database-neutral persistence SPI.
