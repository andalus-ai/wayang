# PostgreSQL WAL dependency catalog and retention planner — Phase 4AM

## Goal

Phase 4AL proved that one base backup depends on an external WAL archive and
that `pg_verifybackup` detects a missing required WAL segment. Phase 4AM adds
the missing multi-backup planning layer: the union of every retained backup's
WAL dependencies determines which archived segments are protected.

## Catalog input

The JSON catalog is intentionally small and explicit:

```json
{
  "wal_segment_size_bytes": 16777216,
  "backups": [
    {
      "backup_id": "nightly-2026-11-15",
      "retained": true,
      "manifest": "backups/nightly-2026-11-15/backup_manifest"
    },
    {
      "backup_id": "nightly-2026-11-01",
      "retained": false,
      "manifest": "backups/nightly-11-01/backup_manifest"
    }
  ]
}
```

Manifest paths are relative to the catalog file. Retained entries must have a
valid PostgreSQL `backup_manifest` containing non-empty `WAL-Ranges` entries.
The segment size is explicit because PostgreSQL clusters can be initialized
with a non-default WAL segment size; obtain it from `pg_controldata` for the
cluster that produced the backups.

## Planner behavior

`scripts/plan-postgres-wal-retention.py`:

1. Loads every retained backup manifest and expands each timeline/LSN range to
   its inclusive PostgreSQL WAL segment filenames.
2. Builds the union of those segment names across all retained backups.
3. Requires every referenced WAL segment to exist in the archive.
4. Preserves every timeline `.history` file and requires the history file for
   every referenced timeline greater than 1.
5. Marks only old, syntactically valid WAL segment files outside the dependency
   union as eligible candidates.
6. Protects recent WAL, unknown files, metadata, history files, and symlinks.
   A symlink anywhere in the archive causes planning to fail closed.
7. Emits a JSON dry-run report. It never deletes, moves, truncates, or rewrites
   archive data.
8. Includes a SHA-256 snapshot fingerprint over the exact catalog bytes and
   each retained backup manifest. An optional expected fingerprint rejects a
   stale plan when the catalog or any retained manifest changes.
9. Rejects absolute, parent-traversing, and symlinked manifest paths so a
   catalog cannot silently read a manifest outside its own directory.

The planner deliberately does not infer a dependency range from backup age,
file timestamps, or the oldest segment in the archive.

## Run

```bash
python3 -m unittest discover -s spikes/cel-nessie/scripts/tests -v
python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \
  --archive-dir /srv/postgres/wal-archive \
  --catalog /srv/postgres/backup-catalog.json \
  --older-than-days 30
```

To revalidate an approval against the same retained-backup snapshot, pass the
fingerprint emitted in the plan:

```bash
python3 spikes/cel-nessie/scripts/plan-postgres-wal-retention.py \\
  --archive-dir /srv/postgres/wal-archive \\
  --catalog /srv/postgres/backup-catalog.json \\
  --older-than-days 30 \\
  --expected-snapshot-sha256 <sha256-from-approved-plan>
```

If catalog or retained-manifest bytes change, the command exits non-zero and
requires a fresh plan and approval. The fingerprint does not lock the catalog
or archive; callers must still prevent concurrent archive mutation during any
future destructive operation.

The catalog's `wal_segment_size_bytes` is authoritative for filename
expansion. A production caller must generate/validate catalog entries from the
actual backup manifests and keep manifest files immutable for the lifetime of
the retained backup.

## Safety limits

- This phase is a planner only; there is no purge command or deletion API.
- Missing manifests, malformed ranges, absent required segments, missing
  timeline history, invalid segment size, empty retained catalog, duplicate
  backup IDs, and archive symlinks all fail closed.
- All `.history` files are conservatively protected, including files not
  currently referenced by a retained backup.
- Unknown archive files are never candidates.
- The planner does not prove that an archive copy is durable, replicated,
  encrypted, or recoverable after host loss.
- It does not resolve cross-cluster catalogs. A catalog must contain backups
  from one PostgreSQL system/timeline lineage and one segment-size setting.
- Before any future purge implementation, each retained backup must pass
  `pg_verifybackup --wal-directory` against a simulated post-purge archive.
  Timeline ancestry, WAL archive upload completion, backup expiration races,
  and concurrent backup creation must also be qualified.

## Acceptance criteria

- Multiple retained manifests produce a union, not a single-backup cutoff.
- An old segment referenced by any retained backup is protected.
- An old unreferenced WAL segment is reported as eligible, not deleted.
- Timeline history and unknown files are protected.
- Missing required data prevents the planner from emitting a deletion plan.
- Automated unit tests run in the isolated PostgreSQL qualification workflow.
- PostgreSQL-backed qualification exercises successful revalidation and proves
  that a changed catalog is rejected against the previous fingerprint.
