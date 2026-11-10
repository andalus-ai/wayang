# Pressure event durability contract

## Phase 4AA scope

This phase defines a backend-neutral persistence and delivery contract for
committed pressure transitions. It does not connect the tracker directly to a
database or broker and does not change the existing best-effort listener path.

## Event envelope

`PressureEventEnvelope` adds:

- a stable UUID event identity, generated once before the first append;
- a host-defined stream identity;
- a positive, monotonically assigned sequence within that stream;
- a recorded timestamp;
- the immutable pressure transition payload.

A retry must reuse the same envelope and event ID. A new event ID must not be
generated for each delivery attempt. Sequence allocation belongs to the host
or durable store; the in-memory spike does not claim to coordinate distributed
sequence generation.

## Store semantics

`PressureEventStore` defines:

1. Idempotent append by event ID and by (stream ID, sequence).
2. Bounded claims for one consumer, with a lease and unique fencing token.
3. Acknowledgement only by the current consumer and current, unexpired token.
4. Release to pending using the current token.
5. Reclaim after lease expiry with a new token and incremented delivery attempt.

A repeated append of the exact same envelope returns `DUPLICATE`. Reuse of
either uniqueness key for different content must fail. Implementations must
make these operations atomic across all processes sharing the store.

## Delivery guarantee

The SPI supports **at-least-once delivery attempts** when a durable
implementation persists append and lease state correctly. It does not promise
exactly-once side effects. Consumers must be idempotent, normally using
`eventId` as their deduplication key. A consumer that performs a side effect
and crashes before acknowledgement may receive the same event again.

Lease tokens fence stale workers: once a lease expires and another worker
claims the event, the previous worker cannot acknowledge or release the newer
claim. Acknowledgement after lease expiry is rejected even if no replacement
worker has claimed yet.

## Reference implementation

`InMemoryPressureEventStore` is a synchronized reference implementation for
contract tests only. It is intentionally volatile and not suitable for
production durability or multi-process coordination.

## Explicit non-goals

- No Kafka, JDBC, Redis, filesystem, or cloud SDK dependency.
- No exactly-once guarantee or distributed transaction with consumer effects.
- No event payload enrichment with tenant IDs, expressions, activations, or
  provider exception details.
- No implicit sequence allocator, scheduler, retry backoff, dead-letter queue,
  retention policy, or replay API yet.
- No change to Gamelan's ownership of workflow execution lifecycle.

## Production implementation gates

A production store adapter must pass shared contract tests for concurrent
append uniqueness, atomic claim, lease expiry, fencing, acknowledgement,
release, restart recovery, and cross-process coordination. Persistence
retention, encryption, access control, and operational recovery must be
specified by the host integration.
