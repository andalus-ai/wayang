# PostgreSQL divergent timeline retention qualification — Phase 4AT

## Objective

Qualify retention planning when multiple retained backups depend on sibling
PostgreSQL timeline branches. A branch is a distinct recovery path: two child
timelines may share an ancestor but must not be treated as one linear chain.

## Fixture

The planner contract test creates two retained backup entries:

- Branch A follows timeline 1 to timeline 2, which forked from timeline 1 at
  LSN `0/01000000`.
- Branch B follows timeline 1 to timeline 3, which independently forked from
  timeline 1 at LSN `0/02000000`.

Each retained backup has its own recovery ranges and manifest. Both PostgreSQL
history files are present and are parsed independently. The expected plan is
the union of the WAL dependencies for both retained paths, with both history
files protected.

## Required assertions

- The planner unions shared-ancestor and branch-specific WAL dependencies
  across every retained backup.
- Each retained branch validates its own parent/child fork boundary.
- A missing history file for either retained branch fails closed.
- A malformed history file fails closed rather than being treated as a valid ancestry.
- A history file that names a sibling instead of the expected parent fails closed.
- Missing WAL on either branch fails closed; the other branch's available WAL cannot mask the gap.
- A fork-boundary gap in one branch fails even if the other branch is valid.
- A sibling branch not referenced by any retained backup is not required to
  validate another branch's ancestry; its unreferenced WAL can be reported as
  an eligible candidate, while unknown/history metadata remains protected.
- Existing dry-run behavior is unchanged: no files are deleted.

## Scope and limitations

This phase is a deterministic planner failure-injection qualification using
PostgreSQL-format timeline history fixtures and deliberately incomplete WAL
archives. It does not execute a physical restore for each injected failure,
validate cross-cluster archive replication, or execute production retention.
The live sibling promotion and restore paths are covered by separate PostgreSQL
17 integration qualification jobs. The planner remains dry-run only.

## Run

```bash
python3 -m unittest spikes.cel-nessie.scripts.tests.test_plan_postgres_wal_divergent_branches -v
python3 -m unittest discover -s spikes/cel-nessie/scripts/tests -v
```
