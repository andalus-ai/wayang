# Runtime management host integration

## Status

This is the first Quarkus-host integration slice for M7.7.2. It adds an opt-in heartbeat and inventory reporter, a host-owned credential-store SPI, a restrictive local file implementation, a profile-aware inventory provider, and an explicit command-handler SPI.

It does **not** implement enrollment, command polling/dispatch, deployment execution, credential rotation UX, or live Console integration tests yet.

## Enablement

Management is disabled by default. To enable periodic calls after an operator has enrolled this runtime:

```properties
wayang.management.enabled=true
wayang.management.endpoint=https://console.example.com
wayang.management.credential-file=/var/lib/wayang/management/registration-token
wayang.management.heartbeat-interval=30s
wayang.management.inventory-interval=2m
wayang.management.connect-timeout=5S
wayang.management.request-timeout=10S
```

The registration token file must be mounted on persistent storage writable only by the Wayang process user. The file implementation applies owner-only POSIX permissions where supported and writes via a temporary file followed by atomic replacement. On non-POSIX filesystems, enforce equivalent ACLs outside the application. Do not use this local implementation for multi-replica deployments; provide a shared secret-store implementation of `ManagementCredentialStore`.

The host refuses non-HTTPS management endpoints. A missing credential is not an instruction to enroll: the host logs a low-detail message and waits. Enrollment is intentionally operator-driven until the Console exposes an unambiguous recovery/rotation protocol; a one-time enrollment request must never be blindly retried after an ambiguous network failure.

## Inventory semantics

`ProfileRuntimeManagementInventoryProvider` uses the same profile-filtered `ComponentRegistry` and extension metadata as the component catalog. It reports component capability tuples. Workflow inventory is empty until a durable authoritative deployment registry is connected; the host must not mistake authoring definitions for deployed workflows.

Applications may replace the default provider with their own CDI bean to include deployment-specific metadata.

## Command handling boundary

`RuntimeManagementCommandHandler` is an explicit allow-list SPI, not an execution engine. Implementations must declare supported command types, validate typed payloads, and make execution idempotent using both command ID and idempotency key. The host does not poll or execute commands in this slice. Unknown command types therefore cannot accidentally run.

The next slice will add bounded polling only when at least one handler is registered, STARTED acknowledgement before execution, terminal acknowledgement after verification, deduplication, and shutdown-aware scheduling. APPLY_DEPLOYMENT success must include an independently verified `sha256:<64 hex>` artifact digest.

## Failure handling

- 401/403: stop treating the stored token as usable and require operator re-enrollment; no infinite retry.
- 408/425/429/5xx and transport failures: the next scheduled tick is the retry boundary in this slice. Exponential backoff, jitter, and Retry-After support are still pending.
- Credentials are never included in log messages.
- Heartbeat and inventory jobs use scheduler `SKIP` concurrency to avoid overlapping requests within one process.
