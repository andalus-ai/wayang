# PostgreSQL successive timeline ancestry validation — Phase 4AR

## Objective

Strengthen the dry-run WAL retention planner so a recovery catalog spanning successive PostgreSQL timeline promotions is checked against the actual `.history` ancestry files. A filename-presence check alone is insufficient: a truncated, malformed, reordered, or inconsistent history file must not be accepted as proof of recoverability.

## History-file validation

For every required timeline greater than 1, the planner now:

- Requires the corresponding `TTTTTTTT.history` file.
- Parses PostgreSQL's ASCII history rows as parent timeline, fork LSN, and non-empty reason.
- Rejects malformed rows, invalid or non-increasing parent timeline IDs, and fork LSNs that move backwards.
- Retains every history file as a protected archive dependency.

## Recovery-range boundary validation

When a backup has explicit `recovery_wal_ranges` spanning more than one timeline, the planner validates every fork in each selected timeline's ancestry:

- The parent timeline must include the byte immediately before the fork LSN.
- The child timeline must include the fork LSN itself.
- Every intermediate timeline in a successive-fork ancestry chain must have an explicit recovery range.
- Coverage is checked against individual ranges, not a min/max envelope, so disjoint ranges cannot hide a gap.

This preserves PostgreSQL's boundary rule: the fork LSN belongs to the child timeline, not the parent. For a fork at `0/01000000`, the parent range must reach `0/00FFFFFF`, while the child range must include `0/01000000`.

## Tests

The planner contract suite covers:

- A valid timeline 1 → 2 → 3 ancestry chain.
- Protection of both `00000002.history` and `00000003.history`.
- Rejection when the intermediate timeline range is omitted.
- Rejection of malformed history content.
- Existing single-fork, missing-history, snapshot-fingerprint, and fail-closed archive checks.

## Safety boundary and limitations

This phase validates history-file syntax, ancestry ordering, and catalog range coverage. It does not cryptographically authenticate history files, infer recovery ranges automatically, prove a physical PostgreSQL restore across two successive promotions, or delete archive files. The planner remains read-only/dry-run; production retention execution remains out of scope.

A subsequent integration qualification should create two real promotions and restore the original base backup onto timeline 3 using only the simulated retention archive.
