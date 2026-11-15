# Runtime Management Client Integration Plan

## Current slice

The `wayang-management-client` module is a transport-only Java client for the current Console REST/JSON v1 management API. It deliberately does not depend on Console classes, persistence, or the protobuf-generated Java types.

| Console operation | Client API | Credential |
|---|---|---|
| `POST /api/v1/runtime/enroll` | `enroll(EnrollRequest)` | One-time token in body; no bearer header |
| `POST /api/v1/runtime/heartbeat` | `heartbeat(token, health)` | Registration bearer |
| `POST /api/v1/runtime/inventory` | `reportInventory(token, inventory)` | Registration bearer |
| `POST /api/v1/runtime/commands/poll` | `pollCommands(token, limit)` | Registration bearer |
| `POST /api/v1/runtime/commands/{id}/ack` | `acknowledge(token, id, ack)` | Registration bearer |

The client maps the actual Console response records. In particular, command `typedPayload` is a JSON string, not an arbitrary object, and successful deployment acknowledgement must include the matching `sha256:<64 hex>` verified artifact digest.

## Security and lifecycle requirements for the host integration

1. Store the registration bearer token in a host-provided secret store; never log it or expose it in diagnostics.
2. Enrollment is one-time and must not be automatically retried after an ambiguous transport failure.
3. The current Console API has no credential refresh endpoint. A 401/403 is an operator-visible credential failure, not an infinite retry condition; issue a new enrollment credential and explicitly re-enroll according to the Console lifecycle.
4. Use HTTPS, bounded request/connect timeouts and bounded exponential backoff with jitter for retryable network/HTTP failures (408, 425, 429 and 5xx). Respect server `Retry-After` when supplied by a future contract revision.
5. Send periodic health heartbeats and full inventory replacement reports. Do not report an empty inventory unless the runtime intentionally has no registered capabilities/workflows.
6. Poll only when a command handler is registered. Acknowledge `STARTED` before execution and a terminal status afterward. For `APPLY_DEPLOYMENT`, success requires independently verifying the artifact digest against the deployed artifact.
7. On shutdown, stop scheduled work and do not acknowledge a command as successful unless execution and verification completed.
8. Treat delivery as at-least-once. Use `commandId` and `idempotencyKey` to make command handling idempotent.

## Follow-up integration slices

- Add a host-owned credential store with explicit persistence and rotation semantics.
- Add the inventory provider that derives capabilities from the active runtime profile and workflow references from the runtime's authoritative registry.
- Add a command-handler SPI and deployment adapter; do not execute unknown command types.
- Wire scheduling behind an explicit opt-in configuration in the Quarkus host.
- Add cross-repository integration tests that run the Console API and a Wayang client against the same test environment.
- Add the management client to the Console/runtime compatibility matrix only after those tests run in CI.

## Ownership

The client DTOs and HTTP adapter live in Wayang. Enrollment records, token hashing, tenant/environment authorization, leases, inventory persistence, command state, audit events and rollout/reconciliation policy remain in Console. Shared Core extraction remains gated on the transport decision and actual end-to-end conformance.
