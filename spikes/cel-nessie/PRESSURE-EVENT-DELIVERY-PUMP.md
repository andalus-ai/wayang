# Pressure event delivery pump

## Phase 4AB scope

The delivery pump is a synchronous, host-invoked bounded batch. It composes
with the Phase 4AA `PressureEventStore` and does not own a thread, scheduler,
timer, or retry policy.

## One delivery pass

1. Claim at most the configured batch size using the configured consumer ID
   and lease duration.
2. Invoke the consumer once for each claim.
3. On successful callback return, acknowledge using the claim's fencing token
   and the current clock instant.
4. On consumer `RuntimeException`, release the claim. If release fails, report
   the rejected release in the batch result.
5. If acknowledgement is rejected (for example, lease expiry or a newer
   fencing token), report it. The event remains eligible for redelivery.

Store failures propagate to the host because the pump cannot safely infer
whether a persistence transition committed.

## Retry and idempotency

The host controls how often `deliverOnce()` is invoked and can implement
backoff, rate limiting, shutdown, and dead-letter policy outside this class.
A consumer side effect may succeed while acknowledgement fails; therefore
consumers must be idempotent using the stable event ID. This is at-least-once,
not exactly-once processing.

A callback that blocks longer than its lease can lose its claim. Another pump
may then reclaim and deliver the same event concurrently. Configure leases
above normal processing latency and use consumer-side idempotency or fencing
for external side effects.

## Operational counters

The pump exposes aggregate process-local counts for claimed, acknowledged,
consumer failures, rejected acknowledgements, and rejected releases. These
counters are not durable, do not include event or stream identifiers, and are
not a billing or audit ledger.

## Boundaries

- No background execution or implicit scheduler.
- No automatic backoff, retry count ceiling, poison-event handling, or DLQ.
- No exactly-once side-effect guarantee.
- No durable guarantee from the in-memory reference store.
- No change to Gamelan workflow lifecycle ownership.
