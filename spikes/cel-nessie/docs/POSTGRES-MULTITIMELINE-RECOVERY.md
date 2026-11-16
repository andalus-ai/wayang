# PostgreSQL multi-timeline WAL recovery qualification — Phase 4AQ

## Objective

Prove that a retained physical base backup can be recovered through a PostgreSQL
timeline fork, and that the WAL retention planner protects every required
parent/child timeline segment plus the child's timeline history file.

## Integration scenario

The qualification uses PostgreSQL 17 and disposable Docker volumes:

1. Create a timeline-1 base backup with `pg_basebackup -X none`.
2. Commit a parent-timeline marker and archive its WAL.
3. Stop the original writer, restore the backup, replay archived timeline-1
   WAL, and promote the clone.
4. Confirm promotion creates timeline 2 and archives `00000002.history`.
5. Commit a child-timeline marker, record a PITR target, then commit markers
   after that target and force WAL switches.
6. Build an explicit recovery catalog containing two ranges:
   - timeline 1 from the backup's manifest start through the fork LSN;
   - timeline 2 from the fork LSN through the PITR target LSN.
7. Run the planner, simulate candidate removal in a disposable archive copy,
   and restore the original backup to the target on timeline 2.

## Required assertions

- Parent and child WAL ranges are both included in the plan.
- `00000002.history` is protected.
- Removing the history file makes planning fail closed, even if child WAL
  segments remain.
- PITR follows timeline 2, includes baseline, parent, and child markers, and
  excludes markers committed after the target.
- The simulated retention archive—not the source archive—is used for restore.

## Scope and limitations

This is a controlled single-parent, single-child timeline-fork qualification.
It does not qualify multiple successive forks, timeline-ID reuse across
independent clusters, cross-region archive replication, object-store
consistency, or automatic retention execution. Timeline history is treated as
a required dependency when a recovery range references a timeline greater
than 1; this phase does not parse and independently authenticate the history
file's ancestry metadata.

No source archive deletion or production restore automation is introduced.

## Run

```bash
bash spikes/cel-nessie/scripts/qualify-postgres-multitimeline-recovery.sh
```
