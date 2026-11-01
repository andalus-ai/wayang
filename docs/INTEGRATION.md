# Integration: mapper, formats, routing

Everything here is pure and bounded (no I/O in the mapper or formats; outbound calls go through connectors).
Durable waiting, timers, retries and parallelism are the engine's job (Gamelan), not the pipeline's.

## Mapper

A JSON-shaped spec. A **string is an expression**; use `{"$const": ...}` for a literal string.

| Spec | Meaning |
|---|---|
| `"body.a + body.b"` | expression |
| `{"customer.name": "body.n"}` | object; dotted keys set nested paths (collisions are errors) |
| `{"$const": v}` | literal |
| `{"$template": "Hi ${body.name}"}` | interpolation (`$${` is a literal `${`) |
| `{"$if": e, "$then": s, "$else": s}` | conditional |
| `{"$each": e, "$as": "l", "$where": e, "$map": s}` | map/filter a list; `index` is bound |
| list / number / boolean / null | list of specs / literal |

Limits: nesting 32, `$each` 10 000 items, expression step budget.

### On service nodes (input and output transformation)

```yaml
type: SERVICE_TASK
config:
  connector: http
  inputMapping:  { url: {$template: "https://api/x/${order.id}"}, "body.qty": "order.qty" }   # overrides 'inputs'
  outputMapping: { tier: "result.data.tier", ok: "result.status = 200" }   # scope = variables + result
  outputMode: replace      # or merge (keep raw output too)
  omitNull: true
```
Mapping errors fail the node without retry.

## Pipeline steps (`EIP_TASK`)

`parse`/`serialize` (json, csv, xml), `map` (mapping, headers, merge, remove, omitNull), `filter`, `transform`,
`splitter`, `router`, `recipientList`, `choice` (inline branches, `otherwise`, `onNoMatch: fail|pass|drop`),
`multicast` (`gather: true` = scatter-gather into `{branch: [bodies]}`), `guard` (error handling / dead-letter:
failure emits the original message with `error` and `routeTo`, or runs a `handler`), `call` (enricher or
`tap: true` wire tap through a connector, with `input`/`output` mappers and `into`), `aggregate`
(`incomplete: fail|drop|emit`), `resequence`, `idempotent`. Sub-pipelines nest to depth 8; 500 steps max.

Formats: size ≤ 5 MB, depth ≤ 64, CSV ≤ 100 000 rows. XML forbids DOCTYPE (no XXE). CSV output prefixes string
cells starting with `= + - @` with `'` (formula injection) unless `escapeFormulas: false`. XML model:
`@attr`, `#text`, repeated children become lists.

## Expression gotchas the examples turned up
* Function names are the FEEL spellings: `lower case(x)`, `upper case(x)`, `string length(x)`, not `lower(x)`. An unknown function name is now a
  **parse error with a suggestion** (it used to evaluate to null, so a typo in a `when` silently never matched).
* Lists are 1-based: `classify.messages[1]` is the first message.
* `examples`: `IssueTriage` (GitHub issue webhook, pipeline classifier, Todoist task and GitHub comment), `RssDigest` (RSS poller, validate + idempotent + keyword choice, Telegram message) and `PersonalAssistant`.

## Verified vs not
Unit-tested (62 integration + 7 runtime tests, shim runner, not Maven) and mutation-checked (13 mutants killed).
The remaining patterns (validate, claim check, loop, routing slip, switch, load balancer, retry, circuit breaker, throttle, delay,
batch, correlate, lookup, tokenize, durable `collect`) are in `docs/EIP.md`. Parallel multicast is opt-in (`parallel: true`). Hard timeouts for `collect` groups are opt-in with `sweepTo` (see `docs/EIP.md`); without it groups close lazily and abandoned ones are purged after a retention period. `call` has been tested with fake connectors only.

## Studio mapper editor
`POST /api/v1/mapper/preview` (DEFINITION_READ; no side effects; `call` steps are refused because no connectors are wired).
Body: `{spec, vars, omitNull}` (field mapping; add a `result` variable to try an output mapping) or `{pipeline, body, headers}`.
Mistakes come back as 400 with the parser message. The Studio's **Mapper** screen runs it live (600 ms debounce,
stale answers dropped) with three examples. Tested at service level (4 tests); the REST resource compiles against stubs;
the Dart screen is parse-checked only, never compiled or run.
