# PostgreSQL pressure-store operations — Phase 4AF

## Bounded retention

`PostgresPressureEventStore` implements the optional `PressureEventRetention`
capability. `purgeAcknowledgedBefore(cutoff, maxEvents)` deletes only rows
whose acknowledgement timestamp is strictly earlier than the cutoff. Each
invocation locks and deletes at most `maxEvents` rows using
`FOR UPDATE SKIP LOCKED`. Pending and currently leased rows are never
eligible. The host owns the retention period, scheduling, audit requirements,
and backup policy; no background deletion task is started by the adapter.

## Health and schema compatibility

`inspectHealth()` returns aggregate counts, oldest pending timestamp,
database-observed time, and schema version. It never exposes payloads, stream
IDs, consumer IDs, event IDs, or SQL error text. A missing/unreadable schema
metadata table is reported as an unavailable snapshot with a stable failure
code rather than leaking database details.

The migration records schema version 1. A future incompatible schema change
must increment the version and update the adapter's compatibility rules; do
not silently interpret a newer schema as compatible.

## Boundaries

- Health is a point-in-time diagnostic, not a liveness guarantee for the next
  operation and not a substitute for connection-pool monitoring.
- Retention is explicit and bounded per call; it is not automatic and does not
  guarantee deletion of every eligible row in one pass.
- This remains an optional JDBC spike. It is not runtime-wired and does not
  introduce a database dependency into Wayang core.
- PostgreSQL failover, backup/restore, ambiguous commit recovery, clock skew,
  and deployment-specific retention/legal-hold policy still need operational
  qualification.
