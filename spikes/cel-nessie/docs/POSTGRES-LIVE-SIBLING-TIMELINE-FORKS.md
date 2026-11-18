# PostgreSQL live sibling timeline forks — Phase 4AU

## Objective

Exercise two independent PostgreSQL promotions from the same timeline-1
physical base backup. The branches must have distinct timeline identities,
independent data directories, and one common timeline-1 fork point.

## Harness

`qualify-postgres-sibling-timeline-forks.sh` uses PostgreSQL 17 containers:

1. Initialize timeline 1, create common markers, and take one physical base
   backup.
2. Stop the source to freeze the ancestor.
3. Clone the same base backup into branch A and promote it. It must create
   timeline 2.
4. Clone the same base backup independently into branch B, pin recovery to
   timeline 1, and promote it. With timeline-2 history already in the shared
   archive, PostgreSQL must allocate timeline 3.
5. Read the actual PostgreSQL-generated `.history` files and require both
   branches to identify the same timeline-1 fork point.
6. Build a catalog with two retained recovery paths, run the WAL dependency
   planner, and simulate retention into a separate destination.

## Assertions

- Both branches originate from the exact same base-backup volume.
- Branches have separate PostgreSQL data volumes.
- PostgreSQL generates timeline 2 and timeline 3 independently.
- Both history files identify the same timeline-1 fork point.
- The planner preserves parent WAL and the WAL/history dependencies for both
  sibling paths.
- The simulation writes to a separate directory; source archive is untouched.

## Limitations

This qualifies live sibling promotion and dependency union, not failover
between physical hosts, streaming replication slots, archive object-store
consistency, or restore of each sibling backup. The retention planner remains
dry-run only; no production deletion is introduced.

## Local run

```bash
bash spikes/cel-nessie/scripts/qualify-postgres-sibling-timeline-forks.sh
```
