# Phase 4W — Bounded asynchronous pressure event dispatch

`BoundedPressureEventDispatcher` adapts the synchronous pressure-event listener to an optional, process-local, single-consumer queue.

## Admission and overflow

- Queue capacity is explicit and must be positive.
- `REJECT_NEWEST` preserves already queued events and counts an incoming event as rejected when full.
- `DROP_OLDEST` removes the oldest queued (not currently executing) event to make room; the removal is counted as dropped.
- Calls after close are rejected. Enqueue and close are serialized so no event can be accepted after the close boundary.
- Enqueue is non-blocking; it never waits for consumer capacity.

## Delivery and shutdown

The consumer invokes the delegate serially. A delegate `RuntimeException` is counted and isolated; later events continue. `close()` stops acceptance and drains accepted queued events. `awaitTermination(Duration)` allows a host to bound its shutdown wait. A delegate that blocks forever can prevent termination; this adapter does not forcibly interrupt it.

The daemon worker is started by construction. Hosts should own the dispatcher lifecycle and close it during application shutdown. Do not create one dispatcher per event or per request.

## Snapshot

The immutable snapshot exposes queue depth and aggregate accepted, delivered, dropped, rejected, listener-failure, closed, and terminated values. Counts are process-local and weakly consistent; they contain no tenant or event payload labels.

## Boundaries

This is best-effort in-memory delivery, not durable messaging. A process crash loses queued events. There is no retry, persistence, cross-process ordering, backpressure to the pressure tracker, or exactly-once guarantee. Hosts needing durable delivery should bridge events to their own broker/outbox using their own lifecycle and security policies.
