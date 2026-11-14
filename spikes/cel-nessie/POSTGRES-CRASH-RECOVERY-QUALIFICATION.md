# PostgreSQL crash recovery and ambiguous outcomes — Phase 4AG

## Purpose

A JDBC call can fail after PostgreSQL has committed. In that case the caller
cannot infer whether the operation took effect from the exception alone.
These qualification tests inject a connection failure *after* the underlying
commit or statement execution has completed, then reconstruct the store and
observe the persisted state.

The tests run only in the PostgreSQL 17 qualification workflow. They do not
change the database-neutral store SPI.

## Recovery matrix

| Operation | Simulated lost response | Recovery expectation |
|---|---|---|
| Append | Commit succeeds, response is lost | Retry the exact same envelope (same event ID, stream, sequence, timestamp and payload); result is DUPLICATE |
| Claim | Claim transaction commits, response is lost | Event remains leased; another worker cannot claim before expiry; after expiry it is reclaimed with a new fencing token and incremented attempt count |
| Acknowledge | Update commits, response is lost | Repeating acknowledgement with the old token returns false; the event is terminal and is not delivered again |
| Release | Update commits, response is lost | A subsequent worker can reclaim the event; the old token cannot release or mutate the new claim |

## Caller rules

- Persist/reuse the original PressureEventEnvelope across append retries. Do not generate a new event ID after an ambiguous append result.
- Treat an exception from append/claim/ack/release as an unknown outcome unless the operation's postcondition has been checked.
- A lost claim response may leave a lease active until expiry. This is intentional: do not bypass the lease or invent a second claim token.
- Acknowledge and release are fenced by the exact consumer ID and claim token. A stale worker must not be able to mutate a later claim.
- The store does not provide exactly-once callback execution. Delivery remains at-least-once; consumers must be idempotent using the durable event identity.

## Explicit limits

- Fault injection occurs after a successful database commit/statement on the same primary. It does not emulate every network partition, server crash, replication lag, failover, or storage corruption mode.
- PostgreSQL commit durability depends on deployment configuration (synchronous_commit, WAL/storage guarantees, replication mode and failover policy). These are not asserted by this test suite.
- The claim lease uses caller-supplied time under the current SPI. Hosts sharing a store must maintain a disciplined clock; database-authoritative lease time requires a separately designed SPI revision.
- A connection failure during commit remains an unknown outcome. The adapter intentionally does not claim to distinguish committed-but-response-lost from a commit that did not become durable solely from the thrown exception.
- These are integration qualification tests, not a proof of exactly-once semantics or production failover certification.