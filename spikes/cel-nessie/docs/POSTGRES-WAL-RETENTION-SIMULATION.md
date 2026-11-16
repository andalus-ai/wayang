# PostgreSQL WAL retention simulation — Phase 4AO

## Goal

Phase 4AM computes the union of WAL dependencies across retained backup
manifests. Phase 4AN fingerprints the exact catalog and retained-manifest
snapshot. Phase 4AO adds a non-destructive simulation: apply the planner's
candidate list to a disposable copy of the archive, then run PostgreSQL's
`pg_verifybackup` against **every** retained base backup.

## Simulation helper

`scripts/simulate-postgres-wal-retention.py` accepts a dry-run plan and:

1. Requires `deletion_performed: false`, no missing dependencies, valid
   24-character WAL filenames, and disjoint candidate/required sets.
2. Requires every candidate and required segment to exist in the source archive.
3. Rejects symlinks, non-regular files, an existing destination, and a
   destination nested inside or containing the source archive.
4. Copies the flat archive to a new destination and removes candidate WAL only
   from that copy.
5. Re-hashes the source archive after copying and refuses if its inventory
   changed during the simulation.
6. Emits a JSON report with the source inventory digest, simulated location,
   removed candidates, remaining file count, and an explicit
   `deletion_performed_on_source: false` field.

The helper has a negative-control option,
`--remove-required-wal`, which removes required segments from the disposable
copy as well. It exists only to prove the verifier detects a broken archive.

## PostgreSQL-backed qualification

The integration qualification now creates two retained base backups from one
PostgreSQL 17 cluster, then generates additional WAL after the second backup.
It writes both real `backup_manifest` files into one catalog and runs the
planner against the real archived WAL.

The qualification then:

- Verifies both backups against the original archive.
- Applies only planner candidates to a disposable archive copy.
- Runs `pg_verifybackup --wal-directory` against both backups using the
  simulated post-retention archive; both must pass.
- Creates a second disposable copy with all required WAL removed; both backups
  must fail verification.
- Revalidates the Phase 4AN catalog snapshot fingerprint.

## Safety boundary

- The source archive is never modified; no deletion, move, truncate, or purge
  operation is implemented.
- The destination must not exist before simulation.
- The helper is a simulation utility, not a transactional snapshot facility.
  Production callers must quiesce archive writers or use a filesystem/object
  store snapshot while copying; the before/after inventory check detects
  ordinary concurrent changes but is not a distributed lock.
- `pg_verifybackup` proves the tested backup and WAL set are internally
  verifiable. It does not prove off-site durability, encryption-key recovery,
  replica promotion, or application-level restore correctness.
- No automatic retention scheduler or destructive API is introduced.

## Run

```bash
python3 -m unittest discover -s spikes/cel-nessie/scripts/tests -v
bash spikes/cel-nessie/scripts/qualify-postgres-wal-retention.sh
```

The PostgreSQL-backed workflow runs this qualification with disposable Docker
volumes and uploads the simulation report as a CI artifact.
