# API for Studio and Management console (Flutter + Riverpod)

All transports are thin shells over `WayangApi` (`wayang-api-core`). They authenticate, translate, and map
`ApiException` to status codes; they decide nothing. Tenant and roles come from the verified JWT
(`tenant_id` claim; groups `VIEWER|OPERATOR|DESIGNER|ADMIN`). Every query is scoped to the tenant.

| Role | Can |
|---|---|
| VIEWER | read definitions, runs, tasks; subscribe |
| OPERATOR | + start/cancel runs, complete tasks, send messages |
| DESIGNER | + create/edit/publish definitions |
| ADMIN | + fire timers |

## REST (`/api/v1`, JSON; errors `{code,message,details}`)

| Method & path | Purpose |
|---|---|
| `GET /definitions/{kind}` | list (`kind` = process, decision, agent, pipeline) |
| `GET /definitions/{kind}/{id}/draft` | load draft (`revision` field) |
| `PUT /definitions/{kind}/{id}/draft` + `If-Match: <revision>` | save draft; stale revision -> **409**; omit header to create |
| `DELETE /definitions/{kind}/{id}/draft` | delete draft |
| `POST /definitions/{kind}/validate` | `{valid, items[{severity,elementId,message}]}` for live Studio feedback |
| `POST /definitions/{kind}/{id}/publish` | freeze draft as next immutable version; invalid -> **422** with details; processes must reference published decisions/agents |
| `GET /definitions/{kind}/{id}/versions/{latest\|n}` | published version |
| `GET /definitions/process/{id}/bpmn?version=` | BPMN 2.0 XML export |
| `POST /definitions/process/import-bpmn` (application/xml) | XML -> native document (not stored; Studio edits then saves) |
| `POST /runs` `{processId, version?, input}` | start (201) |
| `GET /runs?processId=&limit=` , `GET /runs/{id}`, `POST /runs/{id}/cancel` | list / get / cancel (409 if not cancellable) |
| `GET /tasks`, `POST /tasks/{token}/complete` `{outputs}` | human task inbox |
| `POST /messages` `{name, correlationKey, payload}` | `DELIVERED` or `BUFFERED` |
| `POST /tools/expression`, `POST /tools/decision-test` | evaluate an expression / test a decision document |
| `GET /catalog/nodes` | node palette: type, executor, config JSON-schema (drives the Studio property panel; the same schema is enforced by validate/publish) |
| `GET/POST /projects`, `GET/PUT/DELETE /projects/{id}` | project grouping; `GET /definitions/{kind}?projectId=` filters; delete refused while definitions refer to it |
| `GET /secrets`, `PUT /secrets/{name}` `{value}`, `DELETE /secrets/{name}`, `POST /secrets/rotate` | **write-only** secrets (metadata only is ever returned). Needs `wayang.secrets.key`, else 404. List: DESIGNER/ADMIN; write/delete/rotate: ADMIN. Use as `${secret:NAME}` in service/agent/EIP node config |
| `GET /secrets/keys`, `POST /secrets/keys/reload`, `POST /secrets/archive/export|import` | key rotation status; reload a rotated keyring without a restart (admin); encrypted secrets archive (off unless `wayang.secrets.archive-enabled`, admin). See `docs/WORKSPACE.md` |
| `GET /config`, `PUT|DELETE /config/{key}` (`?project=`) | plain settings for `${config:NAME}` |
| `GET /projects/{id}/export`, `POST /workspace/import` `{bundle, options}` | workspace bundle, dry run by default |
| `GET /templates`, `POST /templates/{id}/instantiate` `{params, dryRun}` | starter projects (`assistant-telegram`); a dry run unless `dryRun` is false; triggers are created disabled |
| `POST /projects/{id}/release/plan`, `POST /projects/{id}/release` `{target, groupId, artifactId, version, package, port}` | what a release of the published versions would contain; download it as a Maven project zip (`docs/RELEASE.md`) |
| `POST /admin/timers/fire` | ADMIN; a ticker also does it every 5s |

## WebSocket `/ws/events`

Client frames: `{"id":"1","op":"subscribe","runId":"r1"}` (omit `runId` = all runs of the tenant),
`{"op":"unsubscribe","runId":"r1"}`, `{"op":"ping"}`. Replies `{"id","ok":true}` / `{"id","ok":false,"error"}`.
Pushes `{"event":{type,runId,nodeId,time,data}}` with type RUN_STARTED, NODE_COMPLETED, TASK_CREATED,
TASK_COMPLETED, RUN_COMPLETED, RUN_FAILED, RUN_CANCELLED, or OVERFLOW (`data.dropped`: you were too slow,
re-fetch via REST). Max 20 subscriptions per socket, 16 KB per frame.

### Collaboration rooms (same socket)

`{"op":"join","room":"definition:process:loan"}` -> `{"ok":true,"members":[...]}`; `leave`; `who`;
`{"op":"say","room":"...","payload":{...}}` broadcasts to the *other* members as
`{"room":{room,type:"joined|left|msg",user,payload}}`. `user` is stamped by the server from the authenticated
principal. Rooms are tenant-namespaced, VIEWER may join/observe but not `say`, max 10 rooms per socket, 50
members per room, 4 KB payload, 20 `say`/s. Ephemeral: nothing is persisted. Single-node only (no cross-node fan-out yet).

## gRPC (`wayang.proto`)

StartRun, GetRun, CancelRun, ListTasks, CompleteTask, SendMessage, WatchRuns (server stream). JSON-text payloads.
Use for service-to-service or native mobile/desktop. **Flutter web cannot use it without grpc-web.**

## Flutter / Riverpod pattern

- REST via `dio`/`http` in a repository; `FutureProvider.family` for reads, `AsyncNotifier` for the draft editor
  holding `revision`; on 409 show "changed by someone else" and reload.
- Live runs: a `StreamProvider` over `WebSocketChannel`; on `OVERFLOW` or reconnect, `ref.invalidate` the REST
  providers (events are notifications, REST is the source of truth).
- Authoring loop: debounce `POST .../validate` while editing; publish only from a valid draft.
- Flutter web: WebSocket auth header is not possible in browsers (see known gaps).

## Deployment

Configuration (`application.properties`):

| Property | Values | Meaning |
|---|---|---|
| `wayang.store` | `memory` (default), `jdbc` | definitions + wait tickets; `jdbc` uses the default Quarkus datasource and creates tables unless `wayang.jdbc.init-schema=false` |
| `wayang.engine` | `reference` (default), `gamelan` | in-memory demo engine, or durable runs on Gamelan (`wayang.gamelan.endpoint`, `wayang.gamelan.api-key`) |
| `wayang.auth.tenant-claim` | default `tenant_id` | JWT claim carrying the tenant |

`memory` + `reference` lose everything on restart. Production = `jdbc` + `gamelan` + OIDC. In `gamelan` mode, definitions are deployed
to Gamelan lazily as `<tenant>~<process>` at the published version, and decisions/agents referenced by a process are resolved from the
definition store by tenant-qualified id.

## Studio live run view

The Runs screen calls `POST /api/v1/ws/ticket`, opens `/ws/events?ticket=…` and sends `{"op":"subscribe","runId":…}`. Each pushed event
triggers an immediate refetch of the run (events are a hint, `GET /runs/{id}` stays the source of truth) and the panel shows an event log.
If the ticket call or socket fails, the panel polls every 2 s as before; with a live socket it polls every 15 s as a safety net.
