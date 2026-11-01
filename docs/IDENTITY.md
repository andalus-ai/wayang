# Identity, tenancy and authorization (Numu)

Wayang does not own users, tokens, tenants or roles. With `wayang.auth.mode=numu` it delegates to **Numu**
(`andalus-ai/numu`, identity module) through its `AuthCheckService` gRPC contract. The default mode (`jwt`) keeps
working unchanged: tenant claim + `VIEWER/OPERATOR/DESIGNER/ADMIN` groups from any OIDC provider.

## Flow (numu mode)

```
Studio / webhook caller                         Wayang API                          Numu identity (gRPC)
  Authorization: Bearer <JWT | pat_...>  --->   NumuPrincipalResolver  --- ResolvePrincipal --->  valid? type, id
  X-Tenant: acme                                  |                      --- CheckPermissions --->  [wayang.* at acme.wayang]
                                                  v
                                   Principal(tenant=acme, user=u-<uuid>|svc-<uuid>, grants={...})
```

* **Credentials**: a user JWT, or a Numu personal access token / service-account token. Triggers, webhooks and
  other automation should use a **service account**, not a person's token.
* **Tenant**: `X-Tenant` is only a *request*. It is granted when the principal holds at least one Wayang permission
  under that tenant's scope. Unknown tenant and non-member give the same 403, so tenants cannot be enumerated.
  Tenant ids may not contain `.`: they are scope-path segments, and `acme.wayang` as a tenant id would otherwise alias
  into tenant `acme`.
* **Permissions**: Numu permission codes `wayang.definition.read|write`, `wayang.run.read|start|cancel`,
  `wayang.task.complete`, `wayang.message.send`, `wayang.secret.list|manage`, `wayang.admin` (admin implies all).
  Create these in Numu and put them in Numu roles.
* **Scopes**: checked at `<tenant>.wayang`. Numu inherits grants down the dot-path, so a role granted at `<tenant>`
  applies to Wayang as well, and one granted at `<tenant>.wayang` applies only to Wayang.
  Per-project grants live at `<tenant>.wayang.project.<id>` (see *Per-project authorization* below).
* **Fail closed**: Numu unreachable, timing out or answering malformed gives 503, never access.
* **Cache**: resolved credentials and grants are cached `wayang.auth.numu.cache-seconds` (default 30). A revoked token
  or role keeps working for up to that long. `0` disables the cache (two remote calls per request).

## Configuration

```
wayang.auth.mode=numu
quarkus.oidc.enabled=false                    # otherwise OIDC rejects PATs before Wayang sees them
quarkus.grpc.clients.numu.host=numu-identity
quarkus.grpc.clients.numu.port=9000
wayang.auth.numu.cache-seconds=30
```

`wayang-api-rest/src/main/proto/auth_check.proto` is a verbatim copy of Numu's contract (commit `2c7e951`); keep it identical.

## Verification and gaps

* **Executed (15 tests, on the JUnit shim)**: `NumuPrincipalResolver` against an in-memory Numu that follows Numu's real
  scope-inheritance rule: inheritance at root and at `.wayang`, other tenants refused, enumeration-equal errors, scope-path
  injection refused, admin implies all, partial grants honoured by the real `WayangApi`, service accounts, fail-closed
  (down and malformed), cache TTL and revocation, invalid credentials not cached. Mutation-checked: removing the tenant
  segment guard, and removing admin-implies-all, each fail a test.
* **Not verified**: the gRPC binding (`NumuGrpcIdentity`, never compiled: no protoc/Quarkus codegen), anything against a
  real Numu, and that Numu's actual permission/role data uses the codes above (they must be created). `Principals` was only
  compiled against hand-written stubs.
* **Gaps**: audit entries are not sent to Numu (its `AuditPort` is an in-JVM interface, no wire contract: see below);
  tenant status is not read from Numu (its `tenancy` module has no remote contract either: local kill switch below); Numu's ABAC policies
  (`PolicyPort`) are not used; no token introspection endpoint so revocation is TTL-bound.

## Per-project authorization

A permission can be granted tenant-wide (`<tenant>` or `<tenant>.wayang`, covers every project) or on one project
(`<tenant>.wayang.project.<projectId>`). In numu mode the resolver lists the tenant's projects (at most 200), asks Numu about
each in batches of 500 checks, and puts the result in `Principal.projectGrants`. A principal with project grants only is
admitted to the tenant; ADMIN on a project implies every permission *in that project*.

`Principal.can(p)` stays **tenant-wide only**; `can(p, projectId)` is tenant-wide OR that project; `canAnywhere(p)` is the baseline
for operations that then filter or check a project. Enforced in `WayangApi`:

| Area | Rule for a project-scoped caller |
|---|---|
| definitions | list shows only readable projects (unassigned definitions are tenant-wide only); get/publish/delete of another project's definition is **404**, not 403; save needs write on the target project **and** on the source project when it moves one |
| projects | list/get/update limited to granted projects; create and delete need tenant-wide write |
| runs, tasks, events | start needs run.start on the process's project; get/list/cancel/complete/subscribe are filtered by the run's process version's project; `subscribe(all)` delivers only events of readable runs |
| messages | may deliver to a waiting run of their project; **buffering** (no waiter) needs tenant-wide message.send |
| process MCP tools | only processes of readable projects are listed |

Everything not listed (secrets, triggers, OAuth connections, MCP servers from OpenAPI, collaboration rooms, `fireTimers`) still
checks tenant-wide permissions, so project-scoped callers are refused there. That is deliberate: those objects are not
project-owned yet.

Limits: grants for projects created after the cache entry are seen after the cache TTL; beyond 200 projects per tenant the
extras get no project grants (fail closed). JWT mode has no project claims, so it is unchanged (tenant-wide roles).
Verified with 9 API tests, 6 resolver tests against the Numu fake and mutation checks (14 mutants killed); not against a real
Numu or Quarkus.

## Audit log and tenant suspension

Numu's `AuditPort` and `tenancy` module are Java interfaces inside Numu's own JVM; there is no gRPC/HTTP contract Wayang could
call, and inventing one would be guesswork. So Wayang keeps its own:

* **Audit** (`GET /api/v1/audit?limit=&before=`, tenant-wide ADMIN, own tenant only, newest first). Recorded after each
  response by a JAX-RS filter: every non-read request (POST/PUT/PATCH/DELETE) and every 401/403 of an *authenticated* caller:
  time, actor, method, **route template** (never the URL or query string), path parameters (those named like
  token/secret/key/state/code/password are masked as `***`), status, outcome `OK|DENIED|FAILED`. No bodies. Reads are not
  logged except denials. Unauthenticated requests are not recorded (unproven tenant; anyone could flood the log). A failing
  audit store never fails the request (counted in `AuditService.failures()`). Storage follows `wayang.store`: `jdbc` uses
  table `wayang_audit` (INSERT/SELECT only; the app has no update/delete, grant it no more), `memory` is bounded
  (`wayang.audit.memory-entries-per-tenant`, default 5000) and lost on restart. No retention job yet.
  Not covered: WebSocket and gRPC calls, webhook-triggered runs (they have their own run records), and anything done directly
  against the database.
* **Tenant suspension**: `wayang.tenants.suspended=acme,globex` (restart to change). A suspended tenant gets 403 on every REST
  request (checked right after authentication, both modes), its poll triggers stop being polled and its webhooks are refused
  (same answer as a bad secret). Runs already in flight and Gamelan-side timers are not interrupted. This is a local kill
  switch, not a sync with Numu's tenant lifecycle.
* Verified: 7 audit/gate unit tests, an `AuditStore` contract (also run against PG16: 8 of 8 JDBC ITs pass) and a trigger test;
  mutation-checked (read filtering, masking, admin check, ordering, bound, gate in poll and webhook). The REST filter,
  resource and config compile against stubs only; never run in Quarkus.

## WebSocket tickets and gRPC in numu mode

* **WebSocket**: browsers cannot send `Authorization` on the upgrade. `POST /api/v1/ws/ticket` (normal bearer auth, needs run or
  definition read somewhere) returns `{ticket, expiresAt, expiresInSeconds: 30}`; connect to `/ws/events?ticket=...`. A ticket is
  256 random bits, **single use**, valid 30 s, bound to the principal resolved when it was issued, stored only as a hash,
  capped at 20 outstanding per principal. Unknown, expired and reused tickets answer identically. The tenant gate is checked again
  at connect. Native clients may still send the bearer header instead. Limits: tickets live in one node's memory (use sticky
  routing behind several nodes); the ticket is in the URL, so keep request-URL logging off for `/ws/*` (the audit filter
  never records query strings); an open socket keeps the permissions it was admitted with until it closes (no periodic
  re-check, no max age yet). The Studio does not use the socket yet, so there is no Dart client for the ticket.
* **gRPC**: `@Authenticated` is gone from `WayangGrpcService` because with `quarkus.oidc.enabled=false` (numu mode) it would refuse
  every call. Every RPC resolves its principal itself (it already did); `GrpcAuthInterceptor` copies the `authorization` and
  `x-tenant` metadata into the gRPC context and `Principals` reads it when there is no HTTP request. `watchRuns` resolves on a
  worker thread via the application-scoped `GrpcPrincipals`. Send `authorization: Bearer <JWT|pat_...>` and `x-tenant: acme`.
* **Verified**: the ticket book (7 tests, mutation-checked: single use, expiry, cap, purge, TTL). The WebSocket resource and
  `Principals`/`GrpcPrincipals` compile against stubs. **Not compiled at all**: `GrpcAuthInterceptor` and the gRPC service
  edits (no gRPC jars here). **Never run**: that Quarkus propagates the gRPC `Context` into `@Blocking` worker threads (I
  believe it does, I have not seen it), and that `handshakeRequest().query()` is the right websockets-next accessor. If the
  context does not propagate, numu-mode gRPC answers UNAUTHENTICATED (fails closed), so it would be noticed at once.
