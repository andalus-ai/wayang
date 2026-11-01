# Enterprise Integration Patterns in Wayang

A pipeline is a list of steps applied to **messages** (`headers` + `body`). It runs inside an `EIP_TASK` node (executor
`wayang-eip`, config `pipeline`), or in the Studio mapper preview. Durable waiting, timers, parallelism and process-level
retries belong to the engine (Gamelan); a pipeline is a short, in-memory transformation. Steps nest (depth 8, 500 steps).

```yaml
pipeline:
  delivery: EFFECTIVELY_ONCE        # needs an idempotent step
  steps:
    - {type: validate, required: [id], types: {amount: number}, onInvalid: route, deadLetter: invalid}
    - {type: idempotent, key: body.id}
    - {type: retry, attempts: 3, backoffMs: 200, steps: [{type: call, connector: crm, input: {id: body.id}, into: customer}]}
```

## Catalog (Hohpe & Woolf) and where each pattern lives

| Pattern | Wayang |
|---|---|
| Message, Message Channel, Pipes and Filters | `Message`, `Pipeline` |
| Message Translator, Content Filter, Envelope Wrapper, Normalizer | `map`, `transform`, `parse`/`serialize`, `contentFilter`, `envelope`, `switch` |
| Content Enricher | `call` (+`into`), `lookup` (reference table) |
| Message Filter, Selective Consumer | `filter` |
| Content-Based Router, Message Router | `router`, `choice`, `switch` |
| Dynamic Router, Routing Slip | `routingSlip` (the message carries its itinerary) |
| Recipient List, Publish-Subscribe | `recipientList`, `multicast`; fan-out to people/systems is also triggers + events |
| Splitter, Composed Message Processor | `splitter`, `tokenize`; splitter + `choice` + `aggregate` |
| Aggregator | `aggregate` (one batch) and **`collect` (across runs, durable)** |
| Scatter-Gather | `multicast` with `gather: true`; add `parallel: true` to run branches concurrently |
| Resequencer | `resequence` |
| Wire Tap | `call` with `tap: true` |
| Idempotent Receiver | `idempotent` (durable with `wayang.store=jdbc`) |
| Claim Check | `claimCheck` store / retrieve |
| Dead Letter Channel, Invalid Message Channel | `guard`, `retry` handler, `validate` with `onInvalid: route` (headers `error`, `routeTo`) |
| Message Validator | `validate` |
| Correlation Identifier, Message History | `correlate`, `label` |
| Load Balancer | `loadBalance` |
| Retry / Circuit Breaker / Throttler / Delayer | `retry`, `circuitBreaker`, `throttle`, `delay` |
| Batch / Message Sequence | `batch`, `splitter` headers |
| Loop | `loop` |
| Process Manager | BPMN itself |
| Guaranteed Delivery, Durable Subscriber | the durable engine and triggers (cursor-based pollers, at-least-once) |
| Polling Consumer, Event-Driven Consumer, Messaging Gateway, Service Activator, Messaging Bridge | triggers (poll, webhook), `call`, service tasks |
| Control Bus, Message Store | audit log (`GET /api/v1/audit`), run events; not pipeline steps |
| Return Address | `headers.replyTo` convention; set it with `map` |
| Smart Proxy, Test Message, Channel Purger, Datatype Channel, Point-to-Point Channel | not applicable as steps (infrastructure or modelling concerns) |

## The new steps

**validate** `{rules: [{when, message}], required: [paths], types: {path: number|string|boolean|object|array}, onInvalid: fail|drop|route, deadLetter}`.
`fail` is a *permanent* error (retry steps do not retry it). `route` passes the message on with `error` and `routeTo`.

**contentFilter** `{keep: [paths], remove: [paths]}` on an object body. **envelope** `{wrap: key, with: mapper}` / `{unwrap: key}`.

**claimCheck** `{action: store|retrieve, ttlSeconds (default 3600, max 7 days), header: claimCheck, maxBytes (1 MB), consume, dropIfMissing}`.
`store` puts the body in the claim store and replaces it by a 128-bit random token in the header; `retrieve` restores it. Claims
are private to *tenant + process + node*: another tenant (or another process) holding the token gets "unknown or expired".

**loop** `{times | while | until, maxIterations (default 100, max 1000), steps}`. The body must produce at most one message;
header `iteration` is the 0-based index. `while`/`until` that never finish fail at `maxIterations`.

**routingSlip** `{slip: expr (list or "a,b,c"), stages: {name: [steps]}, skipUnknown}` (max 20 hops).
**switch** `{on: expr, cases: {value: [steps]}, default, onNoMatch}`; values compare as text, so `1` and `1.0` are the same case.
**loadBalance** `{strategy: roundRobin|hash|random, key (hash), targets: {name: [steps]}, failover, name}`; header `target` names the chosen one.

**retry** `{attempts 1..10, backoffMs 0..2000 (doubling, capped at 2 s), steps, handler, deadLetter}`. Gives up at once on a permanent
failure (a connector error marked non-retryable, a validation failure). With a `handler`, exhaustion routes instead of failing.
**circuitBreaker** `{name, failureThreshold, openSeconds, steps, fallback}`: after N consecutive failures calls are refused for the
open period (or answered by `fallback`), then exactly one probe is let through. **throttle** `{name, perSecond, burst, onExceed: fail|drop|wait, maxWaitMs ≤ 5000}`
(token bucket). **delay** `{ms ≤ 10000}` pauses once per batch, or `{notBefore: expr}` only stamps a header for a durable timer to use.

**batch** `{size}` (a batch of messages becomes lists of ≤ size bodies). **correlate** `{key?, force}` sets `correlationId`.
**label** `{name}` appends to the `history` header (max 100). **lookup** `{table, key, into, default, failIfMissing}` (≤ 10 000 entries).
**tokenize** `{from?, separator (literal), trim, skipEmpty, limit}`.

**collect** `{key: expr, size | completeWhen, timeoutSeconds, onTimeout: emit|drop, maxGroup}`: each *run* adds its message to the
group of its key; the run that completes the group (count reached, or `completeWhen` true over `count`, `bodies`, `last`) emits one
message whose body is the list, with `correlationId` and `aggregateSize`; every other run emits nothing. Closing a group is a single
atomic `claim`, so with several replicas exactly one emits. A group older than `timeoutSeconds` is closed **when the next message
with that key arrives** (emitted with `incomplete: true`, or dropped). For a **hard deadline** add `sweepTo: <processId>` (needs
`timeoutSeconds` and `onTimeout: emit`): a background sweeper closes every group whose *oldest* message is older than the timeout
(a newer message does not keep it alive) and **starts a new run of that process** with the variable
`aggregate = {key, bodies, size, incomplete: true}`. It cannot resume the run that was waiting at the collect node, because those runs
ended when they added their message, so the late path is a separate process of your own (notify, escalate, process the partial batch).
The group is claimed atomically (one replica wins) and put back, with its original age, if the process cannot be started, so it is retried
on the next sweep; a crash between claim and start loses it. The target is read from the **latest published** version of the process that
owns the node. A suspended tenant's groups wait. Ticks: every `wayang.collect.sweep-interval` (default 15 s) in the server; in a release,
on the same tick as timers (service: every few seconds; the GAMELAN target sweeps its own JVM's groups). A one-shot `run` CLI exits before
any sweep. Publishing refuses a `sweepTo` naming a process that is not published in the tenant (publish the late process first); an import publishes the target before the process that names it (a cycle fails at publish). A release refuses a `sweepTo` naming a process it does not carry. Without `sweepTo` behaviour is unchanged (lazy only).
Groups that never complete are not kept forever: `AggregationStore.purge` drops entries older than a retention period
(JDBC: `wayang.eip.collect-retention-days`, default 30, checked on every 32nd append; in memory: bounded to 10 000 groups).

## What is shared and what is per process

| State | In memory | `wayang.store=jdbc` |
|---|---|---|
| Idempotency keys | per process, bounded (100 000) | table `wayang_eip_seen` (SHA-256 keys, purged after `wayang.eip.idempotency-retention-days`, default 30) |
| Claim checks | per process | table `wayang_eip_claim` |
| `collect` groups | per process | table `wayang_eip_agg` (an advisory lock per group on PostgreSQL keeps `maxGroup` exact) |
| Circuit breakers, throttle buckets, round-robin counters | per process | **per process**: with N replicas each has its own breaker and rate budget |

Everything is keyed by **tenant | process | node**, so identical keys in two tenants, processes or nodes never collide. (Before this
change the idempotency store was shared by all tenants; that was a bug, now fixed and tested.)

## Limits and honesty

* Steps run in order on one thread; `multicast` branches run in order unless `parallel: true` (see below), and `delay`/`throttle wait`/`retry` backoff block that
  thread (bounded at 10 s / 5 s / 2 s). For real waiting use BPMN timers and the engine's retry policy.
* The Studio has no visual pipeline editor; pipelines are JSON in the node configuration (the Mapper screen previews them, with
  throw-away in-memory stores, no connector calls).
* Tested with JUnit-style tests on a shim runner (62 integration + 7 runtime), mutation-checked, and the JDBC stores against PostgreSQL 16.
  Not run under Quarkus, not run against more than one replica (the claim race is tested with threads on one connection pool).

## Parallel multicast

`{type: multicast, parallel: true, timeoutSeconds: 30, gather: true, branches: {...}}` runs every branch on its own virtual thread and waits for all.
Output is identical to a sequential run (declaration order, not completion order). At most 16 branches; `timeoutSeconds` 1..120 (default 30).
The first failing branch (in declaration order) fails the step, the others are interrupted. Branches share the message and scope, which are
immutable, and the evaluation step budget (now synchronized). Use it for independent I/O (`call` steps); a branch that uses `delay`, `throttle` or
`retry` still blocks only its own thread. Breakers, throttles and the idempotency store are shared by the branches and were already thread-safe
under the in-memory implementations, but this has been tested with fakes and a handful of threads only, not under load.

## Studio pipeline builder

The Studio's **Mapper** screen, in Pipeline mode, has a **Builder** view next to the raw **JSON** view: add a step from a catalog of all 33 step types
(grouped Format, Transform, Quality, Routing, Reliability, Integration), move it up or down, edit one step as JSON, remove it. The JSON stays the
single source of truth, so both views and the live preview always agree; the builder only edits `steps` and keeps other top-level keys. Nested steps
(inside `choice`, `guard`, `multicast`...) are edited in that step's JSON dialog, not drawn as a tree.

The catalog is `studio/assets/pipeline_steps.json`. A Java test (`StudioPipelineCatalogTest`) builds every template with the real `EipLoader`,
checks the catalog offers exactly the step types the loader knows (adding a step type to the server fails the build until it is in the catalog) and that
every icon name is mapped in the Dart widget. **The Dart code (`pipeline_model.dart`, `pipeline_builder.dart`, `test/pipeline_model_test.dart`) is
parse-checked only: never compiled, never run, no widget test.**
