# PostgreSQL pressure event store

## Phase 4AD — optional JDBC adapter

`PostgresPressureEventStore` implements the existing `PressureEventStore`
SPI without adding a PostgreSQL or pool dependency to the Wayang core. The
host supplies a `javax.sql.DataSource` and a `PressureEventCodec`.

Apply `src/main/resources/db/migration/V1__pressure_event_store.sql` before
constructing the adapter. The migration enforces event identity and
(stream, sequence) uniqueness, positive sequence numbers, and all-or-none
claim metadata.

## Transaction and concurrency behavior

- Append uses one transaction and `INSERT .. ON CONFLICT DO NOTHING`.
  A conflict is read back and accepted as DUPLICATE only when identity,
  stream, sequence, timestamp, and encoded payload match exactly. Conflicting
  key reuse fails closed. A companion nanosecond field preserves the full Java `Instant` precision beyond PostgreSQL timestamp microseconds.
- Claim runs in a transaction with `FOR UPDATE SKIP LOCKED`, orders by
  stream and sequence, and assigns a fresh UUID fencing token while incrementing
  delivery attempts. Concurrent claimers use separate row locks and cannot
  receive the same live claim.
- Acknowledge requires matching consumer and token and an unexpired lease.
  Release requires matching consumer and token; it deliberately permits a
  current token to release after expiry, but a reclaimed token cannot release
  the replacement claim.
- All transitions are committed before the method returns. An ambiguous
  network failure during commit may leave the caller uncertain; callers must
  retry append with the exact same envelope and treat delivery as at-least-once.

## Codec and compatibility

The codec is host-supplied so this optional spike does not choose a JSON,
Protobuf, or Java-serialization dependency for Wayang. It must be deterministic
and version its payload format. Keep old decoders available for as long as
unacknowledged rows may exist. Never use Java native serialization for
untrusted or long-lived event payloads.

## Operational boundaries

- This adapter uses caller-provided `Instant now` values to preserve the SPI.
  Multi-host deployments must discipline clocks and choose lease durations
  that tolerate expected skew. Database-clock authority should be a deliberate
  SPI evolution rather than silently changing these semantics.
- The migration intentionally has no automatic retention policy. Delete only
  acknowledged rows after a separately governed retention period.
- The adapter is PostgreSQL-specific and remains in the isolated qualification
  spike. It is not wired into the Wayang runtime or selected by default.
- The reusable in-memory contract remains necessary but is not proof of
  multi-process correctness. `PostgresPressureEventStoreTest` exercises real
  PostgreSQL append deduplication/conflicts, concurrent claimers, restart via
  a new store instance, lease expiry/fencing, durable acknowledgement, and
  rollback after a decode failure. The dedicated
  `.github/workflows/cel-postgres-qualification.yml` workflow runs these tests
  against PostgreSQL 17. Ambiguous network commits, clock skew across hosts,
  retention operations, failover, and backup/restore still require deployment-
  level qualification before production use.
