# Pressure event store conformance

## Phase 4AC

`PressureEventStoreContract` is a reusable JUnit behavioral contract. A
persistent adapter should extend it and implement `newStore()` so every
adapter is tested against the same semantics.

The current reference suite verifies:

- exact-envelope append idempotency;
- fail-closed event-ID and stream-sequence uniqueness;
- concurrent append races produce exactly one APPENDED result;
- concurrent claims do not assign one event to two active workers;
- lease expiry permits reclaim with a fresh fencing token;
- stale workers cannot acknowledge a replacement claim;
- release makes a claim available again and increments delivery attempts.

## Requirements for durable adapters

The same tests must pass against a store shared by multiple adapter instances
and, before production qualification, multiple processes. Adapter-specific
integration tests must additionally cover:

- process restart between append, claim, side effect, and acknowledgement;
- database/broker transaction rollback and ambiguous commit outcomes;
- clock skew and lease-time authority;
- concurrent claimers using separate connections;
- persistence uniqueness constraints and index behavior;
- retention and deletion while events are pending or leased;
- store unavailability and recovery.

The reference implementation is not proof of distributed correctness. It is
only the executable baseline for API semantics.

## Ordering boundary

Claim results are ordered by stream ID and sequence within one claim call.
The SPI does not guarantee strict per-stream completion order across multiple
consumers. If a consumer requires ordered side effects, the durable adapter or
host must enforce one active consumer per stream or an equivalent partition
ownership protocol.

## Phase 4AB validation correction

The Phase 4AB delivery-pump tests passed in the isolated run, but the complete
isolated suite failed in the pre-existing ServiceLoader fixture because its
service descriptor contained a literal backslash-n instead of a line ending.
This phase corrects that descriptor. The failure was not caused by the pump.
