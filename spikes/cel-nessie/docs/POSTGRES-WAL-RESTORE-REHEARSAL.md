# PostgreSQL retained-backup restore rehearsal — Phase 4AP

## Goal

Phase 4AO proved that each retained backup passes `pg_verifybackup` after the
planner's candidate WAL is removed from a disposable archive copy. Phase 4AP
raises the gate from file/manifest verification to an actual PostgreSQL
recovery and query rehearsal.

## Scenario

The PostgreSQL 17 qualification creates two retained physical base backups
from one source cluster. It commits marker rows at controlled points:

1. `retained-backup-baseline` before backup 1.
2. `second-retained-backup` after backup 1 and before backup 2.
3. `post-backup-unreferenced-wal` after backup 2.
4. `must-not-exist-at-target` after the chosen recovery target timestamp.

Both backups are verified and restored using the **simulated post-retention
archive**, not the original archive. Each catalog entry also declares an
explicit `recovery_wal_ranges` interval from that backup's WAL start through
the approved recovery target LSN. The planner unions these intervals with the
manifest's own `WAL-Ranges`; the catalog snapshot fingerprint covers the
recovery ranges, so changing the promised PITR horizon invalidates an earlier
approval. The manifest's own ranges alone are not treated as a promise of
future PITR coverage. Recovery uses `recovery.signal`,
`restore_command`, a timestamp target, exclusive target semantics, and
promotion after recovery.

## Assertions for both restores

- PostgreSQL becomes ready and exits recovery (promotion completed).
- The baseline marker is present.
- The second-backup marker is present.
- The post-backup marker is present, proving WAL replay advanced beyond backup 2.
- The marker committed after the target timestamp is absent.

The same assertions are run independently for backup 1 and backup 2. This
proves each retained backup can recover through the simulated retention result
to the chosen point-in-time state. The test also creates a later WAL segment
outside the approved recovery range, so the planner has a genuine candidate to
remove while retaining all WAL needed to reach the target.

## Safety boundary

- Restore containers and volumes are disposable CI resources.
- The source WAL archive remains unchanged.
- The simulator still removes candidates only from a copied archive.
- No destructive retention operation, production restore automation, or
  scheduler is introduced.
- The test is a controlled single-cluster/timeline rehearsal; it does not
  qualify cross-region object-store consistency, encryption-key recovery,
  failover orchestration, or application-level service readiness.

## Run

```bash
bash spikes/cel-nessie/scripts/qualify-postgres-wal-retention.sh
```

The PostgreSQL qualification workflow uploads the restore assertion report as
a CI artifact.
