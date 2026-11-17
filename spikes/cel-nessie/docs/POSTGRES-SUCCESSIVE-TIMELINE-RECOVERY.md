# PostgreSQL successive timeline recovery qualification — Phase 4AS

## Objective

Prove that an original physical base backup on timeline 1 can be recovered
through two real PostgreSQL promotions (timeline 1 → 2 → 3), using only the
WAL archive remaining after the dry-run retention simulation.

## Integration sequence

The PostgreSQL 17 qualification script uses disposable Docker volumes:

1. Initialize timeline 1, create baseline and timeline-1 markers, and take the
   original physical base backup with `pg_basebackup -X none`.
2. Stop the timeline-1 source and recover a clone from the archived WAL.
   Promote it and verify PostgreSQL creates timeline 2 and
   `00000002.history`.
3. Create timeline-2 markers and take a second physical backup using streamed
   WAL. Force and wait for timeline-2 WAL archiving.
4. Stop the timeline-2 writer. Start the second backup in standby recovery,
   replay archived timeline-2 WAL, and promote to timeline 3.
5. Create a timeline-3 marker, capture a PITR target, then create markers after
   the target and force WAL switches.
6. Read both actual PostgreSQL history files and build recovery ranges from
   their fork LSNs:
   - timeline 1 ends at the byte before the first fork;
   - timeline 2 starts at the first fork and ends at the byte before the
     second fork;
   - timeline 3 starts at the second fork and ends at the target LSN.
7. Run the retention planner and simulate candidate removal into a separate
   archive directory. The source archive is never modified.
8. Remove `00000003.history` from a separate negative-control copy and require
   the planner to refuse the catalog.
9. Run `pg_verifybackup` against the simulated archive and restore the
   original timeline-1 backup to the captured target with
   `recovery_target_timeline=3`.

## Required assertions

- PostgreSQL itself creates timelines 2 and 3 and both corresponding history
  files.
- Both history files are included in the planner's protected dependencies.
- The catalog covers the parent byte immediately before each fork and the
  child fork LSN itself, including the intermediate timeline 2 range.
- Missing timeline-3 history is rejected even when WAL files remain.
- `pg_verifybackup` accepts the simulated archive for the original base
  backup.
- The PITR restore reaches the target on timeline 3, then promotes to a new
  writable timeline whose recorded parent is timeline 3. It contains baseline,
  timeline-1, timeline-2, and timeline-3 markers, but excludes both post-target
  markers.
- Restore reads only from the simulated archive; no source archive deletion is
  performed.

## Scope and limitations

This is an integration qualification for one linear sequence with two
successive promotions. It does not qualify divergent concurrent branches,
more than two promotions, timeline ancestry across independent clusters,
cross-region object-store replication, or automatic production retention.
The planner remains dry-run only and the simulation writes only to a
disposable copy.

## Run

```bash
bash spikes/cel-nessie/scripts/qualify-postgres-successive-timeline-recovery.sh
```
